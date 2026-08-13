package com.lpsm.player

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.lpsm.player.data.*
import com.lpsm.player.databinding.ActivityMainBinding
import com.lpsm.player.model.*
import com.lpsm.player.ui.CategoryAdapter
import com.lpsm.player.ui.CategoryRow
import com.lpsm.player.ui.MediaAdapter
import com.lpsm.player.ui.PlayerActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: SecureStore
    private lateinit var api: LpsmApi

    /*
     * Banner configurado pelo painel.
     *
     * É criado por código para não mexer novamente
     * no activity_main.xml e não ocupar espaço quando
     * nenhuma imagem estiver configurada.
     */
    private var homeBannerView:
        ImageView? = null

    private val pool =
        Executors.newSingleThreadExecutor()

    private var entries =
        listOf<MediaEntry>()

    private var epg =
        emptyMap<String, String>()

    private var filter:
        ContentType? = null

    private var favoritesOnly =
        false

    private var selectedGroup:
        String? = null

    /*
     * Usado na TV ao vivo para:
     * primeiro OK = selecionar
     * segundo OK = tela cheia
     */
    private var selectedEntry:
        MediaEntry? = null

    /*
     * SÉRIES
     */
    private var selectedSeriesName:
        String? = null

    private var selectedSeason:
        Int? = null

    /*
     * CONTROLE PARENTAL
     *
     * A senha protege somente grupos/itens
     * claramente pornográficos. Classificação
     * +18 por si só NÃO pede senha.
     *
     * O desbloqueio vale somente enquanto
     * o aplicativo estiver aberto.
     */
    private val adultPin =
        "02020"

    private val unlockedAdultGroups =
        mutableSetOf<String>()

    private var entriesByType =
        emptyMap<
            ContentType,
            List<MediaEntry>
            >()

    private var groupsAll =
        emptyMap<
            String,
            List<MediaEntry>
            >()

    private var groupsByType =
        emptyMap<
            ContentType,
            Map<
                String,
                List<MediaEntry>
                >
            >()

    private var lastConfig:
        DeviceConfig? = null

    /*
     * ATIVAÇÃO
     */
    private val activationHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val activationCheck =
        Runnable {
            silentActivate()
        }

    private val macAddress by lazy {
        store.installMac
    }

    /*
     * PLAYER PEQUENO
     */
    private var previewPlayer:
        ExoPlayer? = null

    private var previewPlayerView:
        PlayerView? = null

    private var previewPlayingUrl:
        String? = null

    /*
     * Atraso da prévia.
     *
     * Evita abrir dezenas de streams
     * enquanto a pessoa passa rápido
     * pelas capas usando o controle.
     */
    private val previewHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var previewRunnable:
        Runnable? = null

    private var previewTarget:
        String? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        b =
            ActivityMainBinding
                .inflate(
                    layoutInflater
                )

        setContentView(
            b.root
        )

        /*
         * Ajusta o layout para o tamanho real
         * do celular, tablet, TV Box ou TV.
         */
        applyResponsiveSizing()

        /*
         * Prepara a área do banner na HOME.
         * Se o painel não tiver banner configurado,
         * essa área permanece totalmente escondida.
         */
        prepareHomeBanner()

        store =
            SecureStore(this)

        api =
            LpsmApi(store)

        b.deviceId.text =
            macAddress

        b.simpleDeviceId.text =
            macAddress

        b.simpleLogo
            .setOnLongClickListener {

                showServerSetup()

                true
            }

        b.code.visibility =
            View.GONE

        b.activateButton.text =
            "ATUALIZAR"

        b.server.setText(
            store.serverUrl
        )

        preparePreviewPlayerView()

        /*
         * LISTA PRINCIPAL
         */
        b.list.layoutManager =
            GridLayoutManager(
                this,
                1
            )

        b.list.adapter =
            MediaAdapter(
                store,

                /*
                 * OK / CLICK
                 */
                { item ->

                    handleMediaClick(
                        item
                    )
                },

                /*
                 * ITEM DESTACADO PELO
                 * CONTROLE REMOTO
                 */
                { item ->

                    if (item != null) {
                        handleMediaFocus(
                            item
                        )
                    }
                }
            )

        /*
         * Ajusta capas e episódios quando
         * entram na tela.
         */
        installResponsiveItemSizing()

        /*
         * CATEGORIAS
         */
        b.categoryList.layoutManager =
            LinearLayoutManager(this)

        b.categoryList.adapter =
            CategoryAdapter {
                    category ->

                handleCategoryClick(
                    category
                )
            }

        /*
         * BOTÃO ABAIXO DA PRÉVIA
         */
        b.previewWatch
            .setOnClickListener {

                currentPreviewEntry()
                    ?.let {
                        openPlayer(it)
                    }
            }

        /*
         * HOME
         */
        b.homeLive
            .setOnClickListener {

                showBrowser(
                    ContentType.LIVE
                )
            }

        b.homeVod
            .setOnClickListener {

                showBrowser(
                    ContentType.VOD
                )
            }

        b.homeSeries
            .setOnClickListener {

                showBrowser(
                    ContentType.SERIES
                )
            }

        b.activateButton
            .setOnClickListener {
                activate()
            }

        b.retryButton
            .setOnClickListener {
                loadConfig()
            }

        b.cancelButton
            .setOnClickListener {
                finishAffinity()
            }

        configureRemoteNavigation()
        bindFilters()

        showActivation()

        if (
            store.token != null
        ) {
            loadConfig()
        }
    }

    /*
     * =====================================================
     * CONTROLE REMOTO
     * =====================================================
     */

    private fun configureRemoteNavigation() {

        /*
         * DESTAQUE VISUAL DOS BOTÕES
         */
        listOf(
            b.homeLive,
            b.homeVod,
            b.homeSeries,
            b.all,
            b.live,
            b.vod,
            b.series,
            b.favorites,
            b.previewWatch
        ).forEach { button ->
            button.isFocusable = true
            button.isFocusableInTouchMode = false
            button.backgroundTintList = null
            button.setBackgroundResource(
                R.drawable.tv_button_background
            )
        }

        /*
         * HOME - esquerda / direita
         */
        b.homeLive.nextFocusRightId = b.homeVod.id
        b.homeVod.nextFocusLeftId = b.homeLive.id
        b.homeVod.nextFocusRightId = b.homeSeries.id
        b.homeSeries.nextFocusLeftId = b.homeVod.id

        /*
         * FILTROS SUPERIORES - esquerda / direita
         */
        b.all.nextFocusLeftId = b.favorites.id
        b.all.nextFocusRightId = b.live.id

        b.live.nextFocusLeftId = b.all.id
        b.live.nextFocusRightId = b.vod.id

        b.vod.nextFocusLeftId = b.live.id
        b.vod.nextFocusRightId = b.series.id

        b.series.nextFocusLeftId = b.vod.id
        b.series.nextFocusRightId = b.favorites.id

        b.favorites.nextFocusLeftId = b.series.id
        b.favorites.nextFocusRightId = b.all.id

        /*
         * Qualquer filtro sobe para a busca.
         */
        listOf(
            b.all,
            b.live,
            b.vod,
            b.series,
            b.favorites
        ).forEach { button ->
            button.nextFocusUpId = b.search.id
        }

        /*
         * BUSCA
         */
        b.search.isFocusable = true
        b.search.isFocusableInTouchMode = true
        b.search.setBackgroundResource(
            R.drawable.tv_search_background
        )

        b.search.setOnKeyListener { _, keyCode, event ->
            if (
                event.action !=
                android.view.KeyEvent.ACTION_DOWN
            ) {
                return@setOnKeyListener false
            }

            when (keyCode) {

                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {

                    preferredTopFilter()
                        .requestFocus()

                    true
                }

                android.view.KeyEvent.KEYCODE_BACK -> {

                    b.search.clearFocus()

                    preferredTopFilter()
                        .requestFocus()

                    true
                }

                else ->
                    false
            }
        }

        b.categoryList.isFocusable =
            true

        b.list.isFocusable =
            true

        b.previewWatch.isFocusable =
            true

        b.previewWatch.isFocusableInTouchMode =
            false
    }

    private fun preferredTopFilter():
        View {

        return when {

            favoritesOnly ->
                b.favorites

            filter ==
                ContentType.LIVE ->
                b.live

            filter ==
                ContentType.VOD ->
                b.vod

            filter ==
                ContentType.SERIES ->
                b.series

            else ->
                b.all
        }
    }

    private fun updateTopSelection() {

        b.all.isSelected =
            filter == null &&
                !favoritesOnly

        b.live.isSelected =
            filter ==
                ContentType.LIVE &&
                !favoritesOnly

        b.vod.isSelected =
            filter ==
                ContentType.VOD &&
                !favoritesOnly

        b.series.isSelected =
            filter ==
                ContentType.SERIES &&
                !favoritesOnly

        b.favorites.isSelected =
            favoritesOnly
    }

    /*
     * =====================================================
     * CLIQUE / OK NO CONTEÚDO
     * =====================================================
     */

    private fun handleMediaClick(
        item: MediaEntry
    ) {

        if (
            isPornProtectedEntry(item) &&
            !isAdultUnlocked(item)
        ) {

            showAdultPinDialog(
                unlockKey = adultUnlockKey(item)
            ) {

                openMediaAfterAdultCheck(
                    item
                )
            }

            return
        }

        openMediaAfterAdultCheck(
            item
        )
    }

    private fun openMediaAfterAdultCheck(
        item: MediaEntry
    ) {

        when (filter) {

            ContentType.LIVE -> {

                /*
                 * Sem prévia fixa: OK abre o canal
                 * diretamente em tela cheia.
                 */
                selectedEntry =
                    item

                openPlayer(
                    item
                )
            }

            ContentType.VOD -> {

                showMediaDetails(
                    item = item,
                    series = false
                )
            }

            ContentType.SERIES -> {

                if (
                    selectedSeriesName ==
                    null
                ) {

                    showMediaDetails(
                        item = item,
                        series = true
                    )

                } else {

                    openPlayer(
                        item
                    )
                }
            }

            else -> {

                openPlayer(
                    item
                )
            }
        }
    }

    /*
     * =====================================================
     * FOCO DO CONTROLE
     * =====================================================
     */

    private fun handleMediaFocus(
        item: MediaEntry
    ) {

        /*
         * Na grade não iniciamos vídeo automaticamente.
         * Isso evita espaço de prévia e também reduz
         * consumo de rede enquanto o cliente navega.
         *
         * Dentro dos episódios apenas atualizamos
         * as informações internas do item.
         */
        if (
            filter ==
                ContentType.SERIES &&
            selectedSeriesName !=
                null
        ) {

            showPreviewInfo(
                item
            )
        }
    }

    /*
     * =====================================================
     * CATEGORIAS / TEMPORADAS
     * =====================================================
     */

    private fun handleCategoryClick(
        category: String
    ) {

        if (
            filter ==
                ContentType.SERIES &&
            selectedSeriesName !=
                null
        ) {

            selectedSeason =
                if (
                    category ==
                    "Episódios"
                ) {

                    null

                } else {

                    category
                        .removePrefix(
                            "Temporada "
                        )
                        .toIntOrNull()
                }

            stopPreview()

            renderSeriesDetail()

            focusFirstMedia()

            return
        }

        if (
            isPornProtectedText(category) &&
            !isAdultGroupUnlocked(category)
        ) {

            showAdultPinDialog(
                unlockKey = adultGroupKey(category)
            ) {

                selectCategoryAfterAdultCheck(
                    category
                )
            }

            return
        }

        selectCategoryAfterAdultCheck(
            category
        )
    }

    private fun selectCategoryAfterAdultCheck(
        category: String
    ) {

        favoritesOnly =
            category ==
                "Favoritos"

        selectedGroup =
            category.takeUnless {

                it ==
                    "Tudo" ||

                it ==
                    "Favoritos"
            }

        selectedEntry =
            null

        stopPreview()

        resetPreviewText()

        render()

        focusFirstMedia()
    }

    /*
     * =====================================================
     * POSIÇÃO DO PREVIEW
     * =====================================================
     */

    private fun updatePreviewPosition() {

        /*
         * Não reservamos mais área fixa para prévia
         * na grade. Assim categorias e conteúdo usam
         * toda a largura em celular, TV Box e TV.
         *
         * A prévia de filmes/séries fica para a tela
         * de detalhes após o OK.
         */
        b.previewTopHost.visibility =
            View.GONE

        b.previewSideHost.visibility =
            View.GONE

        b.previewPanel.visibility =
            View.GONE

        stopPreview()
    }

    private fun movePreviewTo(
        host: FrameLayout,
        compact: Boolean
    ) {

        val currentParent =
            b.previewPanel.parent
                as? FrameLayout

        if (
            currentParent !==
            host
        ) {

            currentParent
                ?.removeView(
                    b.previewPanel
                )

            host.removeAllViews()

            host.addView(
                b.previewPanel,

                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val videoFrame =
            b.previewPanel
                .getChildAt(
                    0
                )

        val videoHeight =
            if (
                compact
            ) {

                compactPreviewVideoHeightDp()

            } else {

                sidePreviewVideoHeightDp()
            }

        videoFrame?.layoutParams =
            videoFrame?.layoutParams
                ?.apply {

                    height =
                        dp(
                            videoHeight
                        )
                }

        if (
            compact
        ) {

            b.previewPanel
                .setPadding(
                    0,
                    0,
                    0,
                    0
                )

            b.previewWatch.visibility =
                View.GONE

            b.previewTitle.textSize =
                when {

                    isVeryCompactScreen() ->
                        12f

                    isCompactScreen() ->
                        14f

                    else ->
                        16f
                }

            b.previewGroup.textSize =
                when {

                    isVeryCompactScreen() ->
                        9f

                    isCompactScreen() ->
                        10f

                    else ->
                        12f
                }

        } else {

            b.previewPanel
                .setPadding(
                    dp(
                        if (
                            isCompactScreen()
                        ) {
                            4
                        } else {
                            10
                        }
                    ),
                    0,
                    0,
                    0
                )

            b.previewWatch.visibility =
                View.VISIBLE

            b.previewTitle.textSize =
                when {

                    isVeryCompactScreen() ->
                        13f

                    isCompactScreen() ->
                        15f

                    else ->
                        20f
                }

            b.previewGroup.textSize =
                when {

                    isVeryCompactScreen() ->
                        10f

                    isCompactScreen() ->
                        11f

                    else ->
                        14f
                }
        }
    }

    /*
     * =====================================================
     * RESPONSIVIDADE
     * =====================================================
     */

    private fun screenWidthDp():
        Int {

        val configured =
            resources.configuration
                .screenWidthDp

        if (
            configured >
            0
        ) {
            return configured
        }

        return (
            resources.displayMetrics
                .widthPixels /
                resources.displayMetrics
                    .density
            )
            .toInt()
    }

    private fun screenHeightDp():
        Int {

        val configured =
            resources.configuration
                .screenHeightDp

        if (
            configured >
            0
        ) {
            return configured
        }

        return (
            resources.displayMetrics
                .heightPixels /
                resources.displayMetrics
                    .density
            )
            .toInt()
    }

    private fun isVeryCompactScreen():
        Boolean {

        return (
            screenHeightDp() <=
                380 ||
            screenWidthDp() <
                650
            )
    }

    private fun isCompactScreen():
        Boolean {

        return (
            screenHeightDp() <=
                520 ||
            screenWidthDp() <
                950
            )
    }

    private fun categoryWidthDp():
        Int {

        /*
         * Mantém as categorias legíveis também
         * em celulares em modo paisagem.
         */
        return when {

            screenWidthDp() <
                650 ->
                165

            screenWidthDp() <
                800 ->
                180

            screenWidthDp() <
                1000 ->
                200

            screenWidthDp() <
                1300 ->
                220

            else ->
                245
        }
    }

    private fun topPreviewWidthDp():
        Int {

        return when {

            screenWidthDp() <
                650 ->
                155

            screenWidthDp() <
                800 ->
                180

            screenWidthDp() <
                1000 ->
                215

            screenWidthDp() <
                1300 ->
                285

            else ->
                390
        }
    }

    private fun topPreviewHeightDp():
        Int {

        return when {

            screenHeightDp() <=
                340 ->
                78

            screenHeightDp() <=
                380 ->
                88

            screenHeightDp() <=
                430 ->
                100

            screenHeightDp() <=
                520 ->
                118

            screenHeightDp() <=
                700 ->
                150

            else ->
                210
        }
    }

    private fun sidePreviewWidthDp():
        Int {

        return when {

            screenWidthDp() <
                650 ->
                130

            screenWidthDp() <
                800 ->
                155

            screenWidthDp() <
                1000 ->
                190

            screenWidthDp() <
                1300 ->
                255

            else ->
                340
        }
    }

    private fun compactPreviewVideoHeightDp():
        Int {

        return when {

            screenHeightDp() <=
                340 ->
                50

            screenHeightDp() <=
                380 ->
                58

            screenHeightDp() <=
                430 ->
                68

            screenHeightDp() <=
                520 ->
                80

            screenHeightDp() <=
                700 ->
                108

            else ->
                160
        }
    }

    private fun sidePreviewVideoHeightDp():
        Int {

        return when {

            screenHeightDp() <=
                340 ->
                60

            screenHeightDp() <=
                380 ->
                70

            screenHeightDp() <=
                430 ->
                82

            screenHeightDp() <=
                520 ->
                102

            screenHeightDp() <=
                700 ->
                145

            else ->
                190
        }
    }

    private fun applyResponsiveSizing() {

        b.categoryList.layoutParams =
            b.categoryList.layoutParams
                .apply {

                    width =
                        dp(
                            categoryWidthDp()
                        )
                }

        b.previewTopHost.layoutParams =
            b.previewTopHost.layoutParams
                .apply {

                    width =
                        dp(
                            topPreviewWidthDp()
                        )

                    height =
                        dp(
                            topPreviewHeightDp()
                        )
                }

        b.previewSideHost.layoutParams =
            b.previewSideHost.layoutParams
                .apply {

                    width =
                        dp(
                            sidePreviewWidthDp()
                        )
                }

        if (
            isCompactScreen()
        ) {

            b.homeWelcome.textSize =
                if (
                    isVeryCompactScreen()
                ) {
                    18f
                } else {
                    21f
                }

            val homeButtonText =
                if (
                    isVeryCompactScreen()
                ) {
                    12f
                } else {
                    15f
                }

            b.homeLive.textSize =
                homeButtonText

            b.homeVod.textSize =
                homeButtonText

            b.homeSeries.textSize =
                homeButtonText
        }
    }

    private fun installResponsiveItemSizing() {

        b.list
            .addOnChildAttachStateChangeListener(

                object :
                    RecyclerView.OnChildAttachStateChangeListener {

                    override fun onChildViewAttachedToWindow(
                        view: View
                    ) {

                        resizeMediaCard(
                            view
                        )
                    }

                    override fun onChildViewDetachedFromWindow(
                        view: View
                    ) {
                    }
                }
            )
    }

    private fun resizeVisibleMediaCards() {

        b.list.post {

            for (
                index in
                0 until b.list.childCount
            ) {

                resizeMediaCard(
                    b.list.getChildAt(
                        index
                    )
                )
            }
        }
    }

    private fun resizeMediaCard(
        view: View
    ) {

        val poster =
            view.findViewById<ImageView?>(
                R.id.poster
            )

        if (
            poster !=
            null
        ) {

            val manager =
                b.list.layoutManager
                    as? GridLayoutManager

            val columns =
                manager?.spanCount
                    ?.coerceAtLeast(
                        1
                    )
                    ?: 1

            val listWidth =
                b.list.width

            if (
                listWidth >
                0
            ) {

                val targetWidth =
                    (
                        listWidth /
                            columns -
                            dp(10)
                        )
                        .coerceAtLeast(
                            dp(82)
                        )

                view.layoutParams =
                    view.layoutParams
                        .apply {

                            width =
                                targetWidth
                        }

                val posterContainer =
                    poster.parent
                        as? View

                posterContainer
                    ?.layoutParams =
                    posterContainer
                        ?.layoutParams
                        ?.apply {

                            height =
                                (
                                    targetWidth *
                                        1.42f
                                    )
                                    .toInt()
                        }
            }

            view.findViewById<TextView?>(
                R.id.posterTitle
            )
                ?.textSize =
                when {

                    isVeryCompactScreen() ->
                        10f

                    isCompactScreen() ->
                        12f

                    else ->
                        16f
                }

            view.findViewById<TextView?>(
                R.id.posterStar
            )
                ?.let {
                    star ->

                    if (
                        isCompactScreen()
                    ) {

                        val size =
                            if (
                                isVeryCompactScreen()
                            ) {
                                26
                            } else {
                                32
                            }

                        star.textSize =
                            if (
                                isVeryCompactScreen()
                            ) {
                                16f
                            } else {
                                20f
                            }

                        star.layoutParams =
                            star.layoutParams
                                .apply {

                                    width =
                                        dp(
                                            size
                                        )

                                    height =
                                        dp(
                                            size
                                        )
                                }
                    }
                }

            return
        }

        val imageContainer =
            view.findViewById<View?>(
                R.id.mediaImageContainer
            )

        if (
            imageContainer ==
                null ||
            !isCompactScreen()
        ) {
            return
        }

        val rowHeight =
            when {

                screenHeightDp() <=
                    340 ->
                    70

                screenHeightDp() <=
                    380 ->
                    76

                screenHeightDp() <=
                    430 ->
                    84

                else ->
                    96
            }

        val imageWidth =
            when {

                screenHeightDp() <=
                    340 ->
                    88

                screenHeightDp() <=
                    380 ->
                    100

                screenHeightDp() <=
                    430 ->
                    112

                else ->
                    132
            }

        view.layoutParams =
            view.layoutParams
                .apply {

                    height =
                        dp(
                            rowHeight
                        )
                }

        imageContainer.layoutParams =
            imageContainer.layoutParams
                .apply {

                    width =
                        dp(
                            imageWidth
                        )

                    height =
                        dp(
                            rowHeight -
                                10
                        )
                }

        view.findViewById<TextView?>(
            R.id.title
        )
            ?.textSize =
            if (
                isVeryCompactScreen()
            ) {
                12f
            } else {
                14f
            }

        view.findViewById<TextView?>(
            R.id.subtitle
        )
            ?.textSize =
            if (
                isVeryCompactScreen()
            ) {
                9f
            } else {
                11f
            }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics
                    .density
            )
            .toInt()
    }

    /*
     * =====================================================
     * PLAYER PEQUENO
     * =====================================================
     */

    private fun preparePreviewPlayerView() {

        val previewFrame =
            b.previewPanel
                .getChildAt(
                    0
                )
                as? FrameLayout
                ?: return

        previewPlayerView =
            PlayerView(
                this
            ).apply {

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )

                useController =
                    false

                isFocusable =
                    false

                isClickable =
                    false

                resizeMode =
                    AspectRatioFrameLayout
                        .RESIZE_MODE_FIT
            }

        previewFrame.addView(
            previewPlayerView
        )
    }

    private fun schedulePreview(
        entry: MediaEntry,
        delay: Long
    ) {

        previewRunnable
            ?.let {

                previewHandler
                    .removeCallbacks(
                        it
                    )
            }

        previewTarget =
            entry.url

        val runnable =
            Runnable {

                if (
                    previewTarget ==
                    entry.url
                ) {

                    playPreviewNow(
                        entry
                    )
                }
            }

        previewRunnable =
            runnable

        previewHandler
            .postDelayed(
                runnable,
                delay
            )
    }

    private fun playPreviewNow(
        entry: MediaEntry
    ) {

        cancelPendingPreview()

        if (
            previewPlayingUrl ==
                entry.url &&
            previewPlayer !=
                null
        ) {

            return
        }

        if (
            previewPlayer ==
            null
        ) {

            previewPlayer =
                ExoPlayer
                    .Builder(
                        this
                    )
                    .build()

            previewPlayerView
                ?.player =
                previewPlayer
        }

        previewPlayingUrl =
            entry.url

        previewPlayer
            ?.apply {

                stop()

                clearMediaItems()

                setMediaItem(
                    MediaItem
                        .fromUri(
                            entry.url
                        )
                )

                prepare()

                playWhenReady =
                    true
            }
    }

    private fun showPreviewInfo(
        entry: MediaEntry
    ) {

        b.previewTitle.text =
            displayTitle(
                entry
            )

        b.previewGroup.text =
            when (
                entry.type
            ) {

                ContentType.LIVE ->
                    entry.group

                ContentType.VOD ->
                    "Filme • ${entry.group}"

                ContentType.SERIES -> {

                    buildString {

                        if (
                            entry.season !=
                            null
                        ) {

                            append(
                                "Temporada ${entry.season}"
                            )
                        }

                        if (
                            entry.episode !=
                            null
                        ) {

                            if (
                                isNotEmpty()
                            ) {

                                append(
                                    " • "
                                )
                            }

                            append(
                                "Episódio ${entry.episode}"
                            )
                        }

                        if (
                            isEmpty()
                        ) {

                            append(
                                entry.group
                            )
                        }
                    }
                }
            }
    }

    private fun cancelPendingPreview() {

        previewRunnable
            ?.let {

                previewHandler
                    .removeCallbacks(
                        it
                    )
            }

        previewRunnable =
            null

        previewTarget =
            null
    }

    private fun stopPreview() {

        cancelPendingPreview()

        previewPlayingUrl =
            null

        previewPlayer
            ?.apply {

                stop()

                clearMediaItems()
            }
    }

    private fun releasePreview() {

        cancelPendingPreview()

        previewPlayingUrl =
            null

        previewPlayerView
            ?.player =
                null

        previewPlayer
            ?.release()

        previewPlayer =
            null
    }

    private fun currentPreviewEntry():
        MediaEntry? {

        val url =
            previewPlayingUrl
                ?: return selectedEntry

        return entries
            .firstOrNull {

                it.url ==
                    url
            }
            ?: selectedEntry
    }

    /*
     * =====================================================
     * ATIVAÇÃO
     * =====================================================
     */

    private fun activate() {

        store.serverUrl =
            b.server.text
                .toString()
                .trim()

        showLoading(
            "Verificando ativação..."
        )

        pool.execute {

            try {

                store.token =
                    api.activate(
                        macAddress,
                        ""
                    )

                runOnUiThread {

                    loadConfig()
                }

            } catch (
                e: Exception
            ) {

                runOnUiThread {

                    showActivation()

                    toast(
                        e.message
                            ?: "MAC ainda não autorizado no painel"
                    )
                }
            }
        }
    }

    /*
     * =====================================================
     * CARREGAR CONFIGURAÇÃO / M3U
     * =====================================================
     */

    private fun loadConfig() {

        showLoading(
            "Carregando configuração e aparência..."
        )

        pool.execute {

            val config =
                try {

                    api.config()

                } catch (
                    e: Exception
                ) {

                    runOnUiThread {

                        val reason =
                            e.message
                                ?: ""

                        if (
                            reason.contains(
                                "autoriz",
                                true
                            ) ||

                            reason.contains(
                                "inativo",
                                true
                            ) ||

                            reason.contains(
                                "expirado",
                                true
                            )
                        ) {

                            store.token =
                                null

                            showActivation()

                        } else {

                            showFailure(
                                lastConfig,

                                "Não foi possível conectar ao painel. Verifique a rede e tente novamente."
                            )
                        }

                        toast(
                            e.message
                                ?: "Falha de conexão"
                        )
                    }

                    return@execute
                }

            lastConfig =
                config

            val wallpaper =
                loadBitmap(
                    config.appearance
                        .wallpaperUrl,
                    4
                )

            val banner =
                loadBitmap(
                    config.appearance
                        .bannerUrl,
                    2
                )

            runOnUiThread {

                applyAppearance(
                    wallpaper,
                    banner
                )

                b.loadingLabel.text =
                    "Carregando ${config.playlists.size} lista(s)..."
            }

            val all =
                ArrayList<MediaEntry>(
                    8_192
                )

            val guides =
                mutableMapOf<
                    String,
                    String
                    >()

            val errors =
                mutableListOf<String>()

            val uniquePlaylists =
                config.playlists
                    .distinctBy {

                        it.url
                            .trim()
                            .lowercase()
                    }

            for (
                playlist in
                uniquePlaylists
            ) {

                try {

                    val remaining =
                        (
                            120_000 -
                                all.size
                            )
                            .coerceAtLeast(
                                0
                            )

                    if (
                        remaining ==
                        0
                    ) {

                        break
                    }

                    val parsed =
                        api.downloadPlaylist(
                            playlist.url,
                            remaining
                        )

                    if (
                        parsed.isEmpty()
                    ) {

                        throw IllegalStateException(
                            "lista vazia ou formato inválido"
                        )
                    }

                    all +=
                        parsed

                    if (
                        playlist.xmltvUrl
                            .isNotBlank()
                    ) {

                        try {

                            guides +=
                                XmlTvParser
                                    .current(
                                        api.download(
                                            playlist.xmltvUrl
                                        )
                                    )

                        } catch (
                            _: Exception
                        ) {
                        }
                    }

                } catch (
                    e: Throwable
                ) {

                    errors +=
                        "${playlist.name}: ${e.message ?: "indisponível"}"
                }
            }

            runOnUiThread {

                if (
                    all.isEmpty()
                ) {

                    val detail =
                        if (
                            config.playlists
                                .isEmpty()
                        ) {

                            "Nenhuma lista foi vinculada a este MAC."

                        } else {

                            "A lista está indisponível. Corrija a URL no painel e toque em tentar novamente."
                        }

                    showFailure(
                        config,
                        detail,
                        banner
                    )

                } else {

                    entries =
                        all.toList()

                    rebuildIndex()

                    epg =
                        guides

                    showContent(
                        config,
                        errors.size
                    )
                }
            }
        }
    }

    /*
     * =====================================================
     * BANNER DA HOME
     * =====================================================
     */

    private fun prepareHomeBanner() {

        if (
            homeBannerView !=
            null
        ) {

            return
        }

        val bannerView =
            ImageView(
                this
            ).apply {

                scaleType =
                    ImageView.ScaleType
                        .CENTER_CROP

                visibility =
                    View.GONE

                isFocusable =
                    false

                isClickable =
                    false

                contentDescription =
                    "Banner LPSM"

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }

        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(
                    homeBannerHeightDp()
                )
            ).apply {

                setMargins(
                    dp(8),
                    dp(6),
                    dp(8),
                    dp(5)
                )
            }

        bannerView.layoutParams =
            params

        /*
         * HOME atualmente possui:
         *
         * 0 = BEM-VINDO
         * 1 = texto "Escolha..."
         * 2 = botões TV / Filmes / Séries
         *
         * Inserimos o banner entre o texto
         * e os botões.
         */
        val position =
            2.coerceAtMost(
                b.homePanel.childCount
            )

        b.homePanel.addView(
            bannerView,
            position
        )

        homeBannerView =
            bannerView
    }


    private fun homeBannerHeightDp():
        Int {

        return when {

            screenHeightDp() <=
                340 ->
                58

            screenHeightDp() <=
                380 ->
                68

            screenHeightDp() <=
                430 ->
                82

            screenHeightDp() <=
                520 ->
                96

            screenHeightDp() <=
                700 ->
                125

            else ->
                155
        }
    }


    /*
     * =====================================================
     * APARÊNCIA
     * =====================================================
     */

    private fun applyAppearance(
        wallpaper: Bitmap?,
        banner: Bitmap?
    ) {

        /*
         * PAPEL DE PAREDE
         */
        b.wallpaper
            .setImageBitmap(
                wallpaper
            )

        /*
         * BANNER NA HOME
         *
         * Sem banner configurado:
         * não reserva nenhum espaço.
         *
         * Com banner:
         * aparece automaticamente entre
         * o título de boas-vindas e os botões.
         */
        homeBannerView
            ?.apply {

                if (
                    banner !=
                    null
                ) {

                    setImageBitmap(
                        banner
                    )

                    visibility =
                        View.VISIBLE

                } else {

                    setImageDrawable(
                        null
                    )

                    visibility =
                        View.GONE
                }
            }

        /*
         * Continua utilizando o mesmo banner
         * também na tela de falha.
         */
        b.failureBanner
            .setImageBitmap(
                banner
            )
    }

    private fun loadBitmap(
        url: String,
        sampleSize: Int
    ): Bitmap? {

        if (
            url.isBlank()
        ) {

            return null
        }

        return try {

            val connection =
                URL(
                    url
                )
                    .openConnection()
                    as HttpURLConnection

            connection.connectTimeout =
                10_000

            connection.readTimeout =
                15_000

            connection
                .inputStream
                .use {

                    BitmapFactory
                        .decodeStream(
                            it,
                            null,

                            BitmapFactory
                                .Options()
                                .apply {

                                    inSampleSize =
                                        sampleSize
                                }
                        )
                }

        } catch (
            _: Exception
        ) {

            null
        }
    }

    /*
     * =====================================================
     * TELAS DE ESTADO
     * =====================================================
     */

    private fun showActivation() {

        activationHandler
            .removeCallbacks(
                activationCheck
            )

        releasePreview()

        b.mainContainer.visibility =
            View.GONE

        b.activationSimple.visibility =
            View.VISIBLE

        b.activation.visibility =
            View.GONE

        b.content.visibility =
            View.GONE

        b.failureState.visibility =
            View.GONE

        b.loadingState.visibility =
            View.GONE

        activationHandler
            .postDelayed(
                activationCheck,
                2_000
            )
    }

    private fun silentActivate() {

        if (
            b.activationSimple.visibility !=
            View.VISIBLE
        ) {

            return
        }

        pool.execute {

            try {

                store.token =
                    api.activate(
                        macAddress,
                        ""
                    )

                runOnUiThread {

                    loadConfig()
                }

            } catch (
                _: Exception
            ) {

                runOnUiThread {

                    if (
                        b.activationSimple.visibility ==
                        View.VISIBLE
                    ) {

                        activationHandler
                            .postDelayed(
                                activationCheck,
                                10_000
                            )
                    }
                }
            }
        }
    }

    private fun showLoading(
        label: String
    ) {

        activationHandler
            .removeCallbacks(
                activationCheck
            )

        releasePreview()

        b.mainContainer.visibility =
            View.VISIBLE

        b.activationSimple.visibility =
            View.GONE

        b.activation.visibility =
            View.GONE

        b.content.visibility =
            View.GONE

        b.homePanel.visibility =
            View.GONE

        b.failureState.visibility =
            View.GONE

        b.loadingState.visibility =
            View.VISIBLE

        b.loadingLabel.text =
            label
    }

    private fun showFailure(
        config: DeviceConfig?,
        detail: String,
        banner: Bitmap? = null
    ) {

        activationHandler
            .removeCallbacks(
                activationCheck
            )

        releasePreview()

        b.mainContainer.visibility =
            View.GONE

        b.activationSimple.visibility =
            View.GONE

        b.activation.visibility =
            View.GONE

        b.content.visibility =
            View.GONE

        b.loadingState.visibility =
            View.GONE

        b.failureState.visibility =
            View.VISIBLE

        b.failureClient.text =
            config?.clientName
                ?: "LPSM"

        b.failurePlaylist.text =
            config?.playlists
                ?.joinToString(
                    " • "
                ) {

                    it.name
                }
                ?: "Lista indisponível"

        b.failureMessage.text =
            detail

        banner
            ?.let {

                b.failureBanner
                    .setImageBitmap(
                        it
                    )
            }
    }

    private fun showContent(
        config: DeviceConfig,
        failedLists: Int
    ) {

        activationHandler
            .removeCallbacks(
                activationCheck
            )

        b.mainContainer.visibility =
            View.VISIBLE

        b.activationSimple.visibility =
            View.GONE

        b.activation.visibility =
            View.GONE

        b.failureState.visibility =
            View.GONE

        b.loadingState.visibility =
            View.GONE

        b.content.visibility =
            View.GONE

        b.homePanel.visibility =
            View.VISIBLE

        b.status.text =
            config.clientName

        b.homeWelcome.text =
            "BEM-VINDO, ${config.clientName.uppercase()}"

        b.message.text =
            if (
                failedLists >
                0
            ) {

                "$failedLists lista(s) indisponível(is). As demais foram carregadas."

            } else {

                config.appearance
                    .supportMessage
                    .ifBlank {

                        "Somente conteúdo autorizado"
                    }
            }

        b.homeLive
            .requestFocus()
    }

    /*
     * =====================================================
     * FILTROS SUPERIORES
     * =====================================================
     */

    private fun bindFilters() {

        b.all
            .setOnClickListener {

                showHome()
            }

        b.live
            .setOnClickListener {

                showBrowser(
                    ContentType.LIVE
                )
            }

        b.vod
            .setOnClickListener {

                showBrowser(
                    ContentType.VOD
                )
            }

        b.series
            .setOnClickListener {

                showBrowser(
                    ContentType.SERIES
                )
            }

        b.favorites
            .setOnClickListener {

                if (
                    selectedSeriesName !=
                    null
                ) {

                    selectedSeriesName =
                        null

                    selectedSeason =
                        null
                }

                favoritesOnly =
                    true

                selectedGroup =
                    null

                selectedEntry =
                    null

                stopPreview()

                render()
            }

        b.search
            .addTextChangedListener(

                object :
                    TextWatcher {

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {

                        render()
                    }

                    override fun afterTextChanged(
                        s: Editable?
                    ) {
                    }
                }
            )
    }

    /*
     * =====================================================
     * ORDEM DAS CATEGORIAS
     * =====================================================
     */

    private fun orderedCategoryEntries(
        indexedGroups:
            Map<
                String,
                List<MediaEntry>
                >
    ): List<
        Map.Entry<
            String,
            List<MediaEntry>
            >
        > {

        if (
            filter !=
            ContentType.LIVE
        ) {

            return indexedGroups
                .entries
                .sortedBy {

                    it.key
                        .lowercase()
                }
        }

        return indexedGroups
            .entries
            .sortedWith(

                Comparator {
                        first,
                        second ->

                    val priority =
                        liveCategoryPriority(
                            first.key
                        )
                            .compareTo(

                                liveCategoryPriority(
                                    second.key
                                )
                            )

                    if (
                        priority !=
                        0
                    ) {

                        priority

                    } else {

                        first.key
                            .compareTo(
                                second.key,
                                ignoreCase =
                                    true
                            )
                    }
                }
            )
    }

    private fun liveCategoryPriority(
        category: String
    ): Int {

        val value =
            category
                .lowercase()

        return when {

            isAdultCategory(
                value
            ) ->
                1000

            listOf(
                "rio grande do sul",
                "rio grande",
                "rbs",
                "gaucho",
                "gaúcho",
                "rs |",
                "| rs",
                " rs "
            ).any {

                it in
                    value
            } ->
                0

            listOf(
                "canais abertos",
                "tv aberta",
                "abertos"
            ).any {

                it in
                    value
            } ->
                10

            "globo" in
                value ->
                20

            "sbt" in
                value ->
                30

            "record" in
                value ->
                40

            "band" in
                value ->
                50

            listOf(
                "rede tv",
                "redetv"
            ).any {

                it in
                    value
            } ->
                60

            listOf(
                "noticia",
                "notícia",
                "news"
            ).any {

                it in
                    value
            } ->
                100

            listOf(
                "esporte",
                "sport"
            ).any {

                it in
                    value
            } ->
                110

            else ->
                500
        }
    }

    private fun isAdultCategory(
        category: String
    ): Boolean {

        val value =
            category
                .lowercase()

        return listOf(
            "adult",
            "adulto",
            "adultos",
            "+18",
            "18+",
            "xxx",
            "erótico",
            "erotico"
        ).any {

            it in
                value
        }
    }

    /*
     * =====================================================
     * CONTROLE PARENTAL - CONTEÚDO PORNOGRÁFICO
     * =====================================================
     *
     * IMPORTANTE:
     * +18 / 18+ sozinhos não são considerados pornô.
     * Assim filmes comuns classificados para maiores
     * de 18 anos continuam abrindo normalmente.
     */

    private fun isPornProtectedEntry(
        item: MediaEntry
    ): Boolean {

        val combined =
            buildString {

                append(item.name)
                append(' ')
                append(item.group)
                append(' ')
                append(item.seriesName)
            }

        return isPornProtectedText(
            combined
        )
    }

    private fun isPornProtectedText(
        text: String
    ): Boolean {

        val value =
            text
                .lowercase()
                .replace('ô', 'o')
                .replace('ó', 'o')
                .replace('í', 'i')
                .replace('é', 'e')
                .replace('ê', 'e')
                .replace('á', 'a')
                .replace('ã', 'a')
                .replace('ç', 'c')

        /*
         * Evita falso positivo em Adult Swim.
         */
        if (
            "adult swim" in
            value
        ) {

            return false
        }

        val explicitPornPattern =
            Regex(
                pattern =
                    """(^|[\s|_\-:/\[\]()])(?:adultos?|xxx|porn|porno|pornografia|erotico|eroticos|sexo|sex|playboy|hustler|brazzers|redlight)(?=$|[\s|_\-:/\[\]()])""",
                option =
                    RegexOption.IGNORE_CASE
            )

        return explicitPornPattern
            .containsMatchIn(
                value
            )
    }

    private fun adultGroupKey(
        group: String
    ): String {

        return group
            .trim()
            .lowercase()
    }

    private fun adultUnlockKey(
        item: MediaEntry
    ): String {

        val group =
            item.group
                .trim()

        return if (
            group.isNotBlank()
        ) {

            adultGroupKey(
                group
            )

        } else {

            "item:${item.url}"
        }
    }

    private fun isAdultGroupUnlocked(
        group: String
    ): Boolean {

        return adultGroupKey(
            group
        ) in unlockedAdultGroups
    }

    private fun isAdultUnlocked(
        item: MediaEntry
    ): Boolean {

        return adultUnlockKey(
            item
        ) in unlockedAdultGroups
    }

    private fun showAdultPinDialog(
        unlockKey: String,
        onUnlocked: () -> Unit
    ) {

        val input =
            EditText(
                this
            ).apply {

                hint =
                    "Senha"

                inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD

                isSingleLine =
                    true

                gravity =
                    Gravity.CENTER

                textSize =
                    22f

                setPadding(
                    dp(18),
                    dp(10),
                    dp(18),
                    dp(10)
                )
            }

        val container =
            FrameLayout(
                this
            ).apply {

                setPadding(
                    dp(24),
                    dp(8),
                    dp(24),
                    0
                )

                addView(
                    input,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        val dialog =
            AlertDialog
                .Builder(
                    this
                )
                .setTitle(
                    "Conteúdo protegido"
                )
                .setMessage(
                    "Digite a senha para acessar canais ou filmes adultos."
                )
                .setView(
                    container
                )
                .setPositiveButton(
                    "ENTRAR",
                    null
                )
                .setNegativeButton(
                    "CANCELAR"
                ) {
                        dialogInterface,
                        _ ->

                    dialogInterface.dismiss()
                }
                .create()

        dialog.setOnShowListener {

            input.requestFocus()

            dialog
                .getButton(
                    AlertDialog.BUTTON_POSITIVE
                )
                .setOnClickListener {

                    if (
                        input.text
                            .toString() ==
                        adultPin
                    ) {

                        unlockedAdultGroups.add(
                            unlockKey
                        )

                        dialog.dismiss()

                        onUnlocked()

                    } else {

                        input.error =
                            "Senha incorreta"

                        input.selectAll()
                    }
                }
        }

        dialog.show()
    }

    /*
     * =====================================================
     * RENDER PRINCIPAL
     * =====================================================
     */

    private fun render() {

        updateTopSelection()

        if (
            filter ==
                ContentType.SERIES &&
            selectedSeriesName !=
                null
        ) {

            renderSeriesDetail()

            return
        }

        val query =
            b.search.text
                .toString()
                .trim()

        val favoriteUrls =
            store.favoriteUrls()

        val section =
            filter
                ?.let {

                    entriesByType[
                        it
                    ]
                        .orEmpty()
                }
                ?: entries

        val indexedGroups =
            filter
                ?.let {

                    groupsByType[
                        it
                    ]
                        .orEmpty()
                }
                ?: groupsAll

        val categories =
            buildList {

                add(
                    CategoryRow(
                        "Tudo",
                        section.size
                    )
                )

                add(
                    CategoryRow(
                        "Favoritos",

                        section.count {

                            it.url in
                                favoriteUrls
                        }
                    )
                )

                orderedCategoryEntries(
                    indexedGroups
                ).forEach {
                        entry ->

                    add(
                        CategoryRow(
                            entry.key,
                            entry.value.size
                        )
                    )
                }
            }

        (
            b.categoryList.adapter
                as CategoryAdapter
            )
            .submit(
                categories,

                if (
                    favoritesOnly
                ) {

                    "Favoritos"

                } else {

                    selectedGroup
                        ?: "Tudo"
                }
            )

        val source =
            selectedGroup
                ?.let {

                    indexedGroups[
                        it
                    ]
                        .orEmpty()
                }
                ?: section

        val filtered =
            source.filter {

                val favoriteOkay =
                    !favoritesOnly ||

                        it.url in
                        favoriteUrls

                val queryOkay =
                    query.isBlank() ||

                        it.name.contains(
                            query,
                            true
                        ) ||

                        it.group.contains(
                            query,
                            true
                        ) ||

                        it.seriesName
                            .contains(
                                query,
                                true
                            )

                favoriteOkay &&
                    queryOkay
            }

        updatePreviewPosition()

        b.previewPanel.visibility =
            View.GONE

        when (filter) {

            ContentType.LIVE -> {

                setGridColumns(
                    1
                )

                (
                    b.list.adapter
                        as MediaAdapter
                    )
                    .submit(
                        filtered,
                        epg,
                        false
                    )

                if (
                    selectedEntry ==
                    null
                ) {

                    resetPreviewText()
                }
            }

            ContentType.VOD -> {

                setGridColumns(
                    posterColumns()
                )

                (
                    b.list.adapter
                        as MediaAdapter
                    )
                    .submit(
                        filtered,
                        epg,
                        true
                    )

                b.previewTitle.text =
                    "Selecione um filme"

                b.previewGroup.text =
                    "Navegue pelas capas • OK para ver detalhes"
            }

            ContentType.SERIES -> {

                val seriesCards =
                    createSeriesCards(
                        filtered
                    )

                setGridColumns(
                    posterColumns()
                )

                (
                    b.list.adapter
                        as MediaAdapter
                    )
                    .submit(
                        seriesCards,
                        epg,
                        true
                    )

                b.previewTitle.text =
                    "Selecione uma série"

                b.previewGroup.text =
                    "OK para ver detalhes"
            }

            else -> {

                setGridColumns(
                    1
                )

                (
                    b.list.adapter
                        as MediaAdapter
                    )
                    .submit(
                        filtered,
                        epg,
                        false
                    )
            }
        }

        b.message.contentDescription =
            "${filtered.size} itens"
    }

    /*
     * =====================================================
     * SÉRIES
     * =====================================================
     */

    private fun createSeriesCards(
        source:
            List<MediaEntry>
    ): List<MediaEntry> {

        return source
            .groupBy {

                seriesKey(
                    it
                )
            }
            .entries
            .sortedBy {

                it.key
                    .lowercase()
            }
            .map {
                    (seriesName, episodes) ->

                val representative =
                    episodes
                        .firstOrNull {

                            it.logo
                                .isNotBlank()
                        }
                        ?: episodes.first()

                representative.copy(

                    name =
                        seriesName,

                    logo =
                        episodes
                            .firstOrNull {

                                it.logo
                                    .isNotBlank()
                            }
                            ?.logo
                            ?: representative.logo,

                    seriesName =
                        seriesName,

                    season =
                        null,

                    episode =
                        null
                )
            }
    }

    private fun openSeries(
        name: String
    ) {

        selectedSeriesName =
            name

        selectedSeason =
            seriesEpisodes(
                name
            )
                .mapNotNull {

                    it.season
                }
                .distinct()
                .sorted()
                .firstOrNull()

        selectedEntry =
            null

        stopPreview()

        renderSeriesDetail()

        focusFirstCategory()
    }

    private fun renderSeriesDetail() {

        val name =
            selectedSeriesName
                ?: return

        val query =
            b.search.text
                .toString()
                .trim()

        val allEpisodes =
            seriesEpisodes(
                name
            )

        val seasons =
            allEpisodes
                .mapNotNull {

                    it.season
                }
                .distinct()
                .sorted()

        if (
            selectedSeason ==
                null &&
            seasons.isNotEmpty()
        ) {

            selectedSeason =
                seasons.first()
        }

        val categories =
            if (
                seasons.isEmpty()
            ) {

                listOf(
                    CategoryRow(
                        "Episódios",
                        allEpisodes.size
                    )
                )

            } else {

                seasons.map {
                        season ->

                    CategoryRow(
                        "Temporada $season",

                        allEpisodes.count {

                            it.season ==
                                season
                        }
                    )
                }
            }

        (
            b.categoryList.adapter
                as CategoryAdapter
            )
            .submit(
                categories,

                selectedSeason
                    ?.let {

                        "Temporada $it"
                    }
                    ?: "Episódios"
            )

        var episodes =
            if (
                selectedSeason ==
                null
            ) {

                allEpisodes

            } else {

                allEpisodes.filter {

                    it.season ==
                        selectedSeason
                }
            }

        if (
            query.isNotBlank()
        ) {

            episodes =
                episodes.filter {

                    it.name.contains(
                        query,
                        true
                    ) ||

                    it.group.contains(
                        query,
                        true
                    )
                }
        }

        episodes =
            episodes.sortedWith(

                compareBy<MediaEntry>(
                    {

                        it.season
                            ?: 0
                    },

                    {

                        it.episode
                            ?: 0
                    },

                    {

                        it.name
                            .lowercase()
                    }
                )
            )

        setGridColumns(
            1
        )

        (
            b.list.adapter
                as MediaAdapter
            )
            .submit(
                episodes,
                epg,
                false
            )

        updatePreviewPosition()

        b.previewPanel.visibility =
            View.VISIBLE

        b.previewTitle.text =
            displaySeriesTitle(
                name,
                allEpisodes
            )

        b.previewGroup.text =
            selectedSeason
                ?.let {

                    "Temporada $it • ${episodes.size} episódio(s)"
                }
                ?: "${allEpisodes.size} episódio(s)"

        b.previewWatch.text =
            "ASSISTIR EPISÓDIO"
    }

    private fun seriesEpisodes(
        name: String
    ): List<MediaEntry> {

        return entriesByType[
            ContentType.SERIES
        ]
            .orEmpty()
            .filter {

                seriesKey(
                    it
                )
                    .equals(
                        name,
                        true
                    )
            }
            .sortedWith(

                compareBy(
                    {

                        it.season
                            ?: 0
                    },

                    {

                        it.episode
                            ?: 0
                    },

                    {

                        it.name
                            .lowercase()
                    }
                )
            )
    }

    private fun seriesKey(
        entry: MediaEntry
    ): String {

        val parsed =
            entry.seriesName
                .trim()

        if (
            parsed.isNotBlank()
        ) {

            return parsed
        }

        val cleaned =
            entry.name
                .replace(
                    Regex(
                        """(?i)\b[ST]\d{1,2}\s*E\d{1,3}\b.*$"""
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """(?i)\b\d{1,2}\s*[xX]\s*\d{1,3}\b.*$"""
                    ),
                    ""
                )
                .trim(
                    ' ',
                    '-',
                    '–',
                    '—',
                    '|',
                    ':'
                )

        return cleaned
            .ifBlank {

                entry.name
            }
    }

    /*
     * =====================================================
     * TELA DE DETALHES - FILMES E SÉRIES
     * =====================================================
     */

    private fun showMediaDetails(
        item: MediaEntry,
        series: Boolean
    ) {

        stopPreview()

        val compact =
            isCompactScreen()

        val veryCompact =
            isVeryCompactScreen()

        val heightDp =
            screenHeightDp()

        val horizontalPadding =
            when {

                veryCompact ->
                    10

                compact ->
                    18

                else ->
                    46
            }

        val verticalPadding =
            when {

                veryCompact ->
                    6

                compact ->
                    12

                else ->
                    32
            }

        val backWidth =
            when {

                veryCompact ->
                    108

                compact ->
                    132

                else ->
                    170
            }

        val backHeight =
            when {

                veryCompact ->
                    38

                compact ->
                    46

                else ->
                    58
            }

        val posterWidth =
            when {

                heightDp <=
                    340 ->
                    112

                heightDp <=
                    380 ->
                    132

                heightDp <=
                    430 ->
                    152

                heightDp <=
                    520 ->
                    185

                heightDp <=
                    700 ->
                    238

                else ->
                    300
            }

        val posterHeight =
            (
                posterWidth *
                    1.46f
                )
                .toInt()

        val dialog =
            Dialog(
                this,
                android.R.style
                    .Theme_Black_NoTitleBar_Fullscreen
            )

        val root =
            FrameLayout(
                this
            ).apply {

                setBackgroundColor(
                    Color.rgb(
                        5,
                        8,
                        12
                    )
                )
            }

        val background =
            ImageView(
                this
            ).apply {

                scaleType =
                    ImageView.ScaleType.CENTER_CROP

                alpha =
                    if (
                        compact
                    ) {
                        0.20f
                    } else {
                        0.28f
                    }

                if (
                    item.logo
                        .isNotBlank()
                ) {
                    load(
                        item.logo
                    )
                }
            }

        root.addView(
            background,

            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val shade =
            View(
                this
            ).apply {

                setBackgroundColor(
                    Color.argb(
                        if (
                            compact
                        ) {
                            205
                        } else {
                            185
                        },
                        0,
                        0,
                        0
                    )
                )
            }

        root.addView(
            shade,

            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val main =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(
                        horizontalPadding
                    ),
                    dp(
                        verticalPadding
                    ),
                    dp(
                        horizontalPadding
                    ),
                    dp(
                        verticalPadding
                    )
                )
            }

        root.addView(
            main,

            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val back =
            Button(
                this
            ).apply {

                id =
                    View.generateViewId()

                text =
                    "↩  VOLTAR"

                textSize =
                    when {

                        veryCompact ->
                            12f

                        compact ->
                            15f

                        else ->
                            18f
                    }

                isAllCaps =
                    false

                isFocusable =
                    true

                setTextColor(
                    Color.WHITE
                )

                setBackgroundResource(
                    R.drawable
                        .tv_button_background
                )

                setOnClickListener {
                    dialog.dismiss()
                }
            }

        main.addView(
            back,

            LinearLayout.LayoutParams(
                dp(
                    backWidth
                ),
                dp(
                    backHeight
                )
            )
        )

        val body =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    0,
                    dp(
                        if (
                            veryCompact
                        ) {
                            5
                        } else if (
                            compact
                        ) {
                            10
                        } else {
                            24
                        }
                    ),
                    0,
                    0
                )
            }

        main.addView(
            body,

            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val poster =
            ImageView(
                this
            ).apply {

                scaleType =
                    ImageView.ScaleType.CENTER_CROP

                setBackgroundColor(
                    Color.rgb(
                        25,
                        30,
                        38
                    )
                )

                if (
                    item.logo
                        .isNotBlank()
                ) {
                    load(
                        item.logo
                    )
                }
            }

        body.addView(
            poster,

            LinearLayout.LayoutParams(
                dp(
                    posterWidth
                ),
                dp(
                    posterHeight
                )
            )
        )

        val information =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setPadding(
                    dp(
                        if (
                            veryCompact
                        ) {
                            10
                        } else if (
                            compact
                        ) {
                            18
                        } else {
                            42
                        }
                    ),
                    0,
                    0,
                    0
                )
            }

        body.addView(
            information,

            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        val title =
            TextView(
                this
            ).apply {

                text =
                    if (
                        series
                    ) {

                        val episodes =
                            seriesEpisodes(
                                seriesKey(
                                    item
                                )
                            )

                        displaySeriesTitle(
                            seriesKey(
                                item
                            ),
                            episodes
                        )

                    } else {

                        displayTitle(
                            item
                        )
                    }

                setTextColor(
                    Color.WHITE
                )

                textSize =
                    when {

                        veryCompact ->
                            19f

                        compact ->
                            25f

                        else ->
                            34f
                    }

                maxLines =
                    if (
                        veryCompact
                    ) {
                        2
                    } else {
                        3
                    }

                ellipsize =
                    android.text.TextUtils
                        .TruncateAt.END
            }

        information.addView(
            title
        )

        val info =
            TextView(
                this
            ).apply {

                val textValue =
                    if (
                        series
                    ) {

                        val episodes =
                            seriesEpisodes(
                                seriesKey(
                                    item
                                )
                            )

                        val seasons =
                            episodes
                                .mapNotNull {
                                    it.season
                                }
                                .distinct()
                                .sorted()

                        buildString {

                            append(
                                "Série"
                            )

                            if (
                                seasons.isNotEmpty()
                            ) {

                                append(
                                    " • "
                                )

                                append(
                                    seasons.size
                                )

                                append(
                                    if (
                                        seasons.size ==
                                        1
                                    ) {
                                        " temporada"
                                    } else {
                                        " temporadas"
                                    }
                                )
                            }

                            append(
                                " • "
                            )

                            append(
                                episodes.size
                            )

                            append(
                                if (
                                    episodes.size ==
                                    1
                                ) {
                                    " episódio"
                                } else {
                                    " episódios"
                                }
                            )

                            if (
                                item.group
                                    .isNotBlank()
                            ) {

                                append(
                                    "\n"
                                )

                                append(
                                    item.group
                                )
                            }
                        }

                    } else {

                        buildString {

                            append(
                                "Filme"
                            )

                            if (
                                item.group
                                    .isNotBlank()
                            ) {

                                append(
                                    " • "
                                )

                                append(
                                    item.group
                                )
                            }

                            if (
                                isLegendado(
                                    item
                                )
                            ) {

                                append(
                                    "\nLEGENDADO (L)"
                                )
                            }
                        }
                    }

                text =
                    textValue

                setTextColor(
                    Color.LTGRAY
                )

                textSize =
                    when {

                        veryCompact ->
                            11f

                        compact ->
                            15f

                        else ->
                            20f
                    }

                maxLines =
                    if (
                        veryCompact
                    ) {
                        3
                    } else {
                        5
                    }

                ellipsize =
                    android.text.TextUtils
                        .TruncateAt.END

                setPadding(
                    0,
                    dp(
                        if (
                            compact
                        ) {
                            7
                        } else {
                            18
                        }
                    ),
                    0,
                    dp(
                        if (
                            compact
                        ) {
                            10
                        } else {
                            28
                        }
                    )
                )
            }

        information.addView(
            info
        )

        val action =
            Button(
                this
            ).apply {

                id =
                    View.generateViewId()

                text =
                    if (
                        series
                    ) {

                        if (
                            veryCompact
                        ) {
                            "▶ TEMPORADAS"
                        } else {
                            "▶  ASSISTIR A TEMPORADA"
                        }

                    } else {

                        "▶  ASSISTIR"
                    }

                textSize =
                    when {

                        veryCompact ->
                            12f

                        compact ->
                            16f

                        else ->
                            20f
                    }

                isAllCaps =
                    false

                isFocusable =
                    true

                setTextColor(
                    Color.WHITE
                )

                setBackgroundResource(
                    R.drawable
                        .tv_button_background
                )

                setOnClickListener {

                    dialog.dismiss()

                    if (
                        series
                    ) {

                        openSeries(
                            seriesKey(
                                item
                            )
                        )

                    } else {

                        openPlayer(
                            item
                        )
                    }
                }
            }

        val actionWidth =
            if (
                series
            ) {

                when {

                    veryCompact ->
                        176

                    compact ->
                        265

                    else ->
                        360
                }

            } else {

                when {

                    veryCompact ->
                        136

                    compact ->
                        180

                    else ->
                        230
                }
            }

        val actionHeight =
            when {

                veryCompact ->
                    40

                compact ->
                    50

                else ->
                    66
            }

        information.addView(
            action,

            LinearLayout.LayoutParams(
                dp(
                    actionWidth
                ),
                dp(
                    actionHeight
                )
            )
        )

        back.nextFocusDownId =
            action.id

        action.nextFocusUpId =
            back.id

        dialog.setContentView(
            root
        )

        dialog.setOnShowListener {

            dialog.window
                ?.setLayout(
                    android.view.WindowManager
                        .LayoutParams.MATCH_PARENT,
                    android.view.WindowManager
                        .LayoutParams.MATCH_PARENT
                )

            action.requestFocus()
        }

        dialog.setOnDismissListener {

            if (
                b.content.visibility ==
                    View.VISIBLE &&
                selectedSeriesName ==
                    null
            ) {

                showPreviewInfo(
                    item
                )

                schedulePreview(
                    item,
                    if (
                        series
                    ) {
                        900L
                    } else {
                        850L
                    }
                )
            }
        }

        dialog.show()
    }

    /*
     * =====================================================
     * MARCAÇÃO (L)
     * =====================================================
     */

    private fun isLegendado(
        item: MediaEntry
    ): Boolean {

        val value =
            buildString {

                append(
                    item.name
                )

                append(
                    ' '
                )

                append(
                    item.seriesName
                )

                append(
                    ' '
                )

                append(
                    item.group
                )
            }

        return (
            Regex(
                """(?i)\[\s*L\s*]"""
            )
                .containsMatchIn(
                    value
                ) ||

            Regex(
                """(?i)\(\s*L\s*\)"""
            )
                .containsMatchIn(
                    value
                ) ||

            Regex(
                """(?i)\blegendad[oa]s?\b"""
            )
                .containsMatchIn(
                    value
                )
        )
    }

    private fun cleanLegendMarker(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)\[\s*L\s*]"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)\(\s*L\s*\)"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
    }

    private fun displayTitle(
        item: MediaEntry
    ): String {

        val clean =
            cleanLegendMarker(
                item.name
            )

        return if (
            isLegendado(
                item
            )
        ) {

            "$clean (L)"

        } else {

            clean
        }
    }

    private fun displaySeriesTitle(
        name: String,
        episodes:
            List<MediaEntry>
    ): String {

        val clean =
            cleanLegendMarker(
                name
            )

        val legendado =
            episodes.any {

                isLegendado(
                    it
                )
            }

        return if (
            legendado
        ) {

            "$clean (L)"

        } else {

            clean
        }
    }

    /*
     * =====================================================
     * LAYOUT DA LISTA
     * =====================================================
     */

    private fun setGridColumns(
        columns: Int
    ) {

        val manager =
            b.list.layoutManager
                as GridLayoutManager

        if (
            manager.spanCount !=
            columns
        ) {

            manager.spanCount =
                columns
        }

        resizeVisibleMediaCards()
    }

    private fun posterColumns():
        Int {

        /*
         * No celular usamos uma coluna a menos,
         * deixando as capas maiores e mais fáceis
         * de ler. Em telas grandes aumentamos
         * progressivamente.
         */
        return when {

            screenWidthDp() <
                650 ->
                3

            screenWidthDp() <
                900 ->
                4

            screenWidthDp() <
                1200 ->
                5

            else ->
                6
        }
    }

    /*
     * =====================================================
     * ÍNDICES
     * =====================================================
     */

    private fun rebuildIndex() {

        entriesByType =
            entries.groupBy {

                it.type
            }

        groupsAll =
            entries.groupBy {

                it.group
                    .ifBlank {

                        "Outros"
                    }
            }

        groupsByType =
            entriesByType
                .mapValues {
                        (type, values) ->

                    values.groupBy { entry ->

                        when (type) {

                            ContentType.LIVE ->
                                liveDisplayGroup(entry)

                            else ->
                                entry.group
                                    .ifBlank {

                                        "Outros"
                                    }
                        }
                    }
                }
    }

    /*
     * =====================================================
     * ORGANIZAÇÃO DOS CANAIS GLOBO / AFILIADAS
     * =====================================================
     *
     * Mantemos as categorias originais da lista para todos
     * os demais canais. Somente os canais Globo/afiliadas
     * são reorganizados em duas categorias mais fáceis:
     *
     * - Globo Sul / RBS
     * - Globo Capitais
     *
     * Isso não altera a URL do canal e não duplica canais.
     */

    private fun liveDisplayGroup(
        entry: MediaEntry
    ): String {

        val originalGroup =
            entry.group
                .trim()
                .ifBlank {

                    "Outros"
                }

        val combined =
            normalizeCategoryText(
                "${entry.group} ${entry.name}"
            )

        if (
            isGloboSouthChannel(
                combined
            )
        ) {

            return "Globo Sul / RBS"
        }

        if (
            isGloboCapitalChannel(
                combined
            )
        ) {

            return "Globo Capitais"
        }

        return originalGroup
    }

    private fun isGloboSouthChannel(
        text: String
    ): Boolean {

        val affiliateMarker =
            listOf(
                "rbs",
                "rbs tv",
                "nsc",
                "nsc tv",
                "rpc",
                "rpc tv",
                "globo sul"
            ).any { marker ->

                marker in text
            }

        if (affiliateMarker) {

            return true
        }

        val isGlobo =
            "globo" in text

        if (!isGlobo) {

            return false
        }

        return listOf(
            "rio grande do sul",
            "porto alegre",
            "caxias do sul",
            "pelotas",
            "santa maria",
            "passo fundo",
            "erechim",
            "uruguaiana",
            "bage",
            "santa cruz do sul",
            "parana",
            "curitiba",
            "londrina",
            "maringa",
            "cascavel",
            "santa catarina",
            "florianopolis",
            "joinville",
            "blumenau",
            "chapeco"
        ).any { location ->

            location in text
        }
    }

    private fun isGloboCapitalChannel(
        text: String
    ): Boolean {

        if (
            "globo" !in text
        ) {

            return false
        }

        if (
            isGloboSouthChannel(
                text
            )
        ) {

            return false
        }

        return listOf(
            "rio de janeiro",
            "sao paulo",
            "belo horizonte",
            "brasilia",
            "salvador",
            "recife",
            "fortaleza",
            "goiania",
            "vitoria",
            "belem",
            "manaus",
            "maceio",
            "natal",
            "joao pessoa",
            "teresina",
            "sao luis",
            "aracaju",
            "cuiaba",
            "campo grande",
            "porto velho",
            "rio branco",
            "macapa",
            "boa vista",
            "palmas"
        ).any { capital ->

            capital in text
        }
    }

    private fun normalizeCategoryText(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value.lowercase(),
                java.text.Normalizer.Form.NFD
            )
            .replace(
                Regex("\\p{Mn}+"),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    /*
     * =====================================================
     * PLAYER TELA CHEIA
     * =====================================================
     */

    private fun openPlayer(
        entry: MediaEntry
    ) {

        releasePreview()

        startActivity(
            Intent(
                this,
                PlayerActivity::class.java
            )
                .putExtra(
                    "name",
                    entry.name
                )
                .putExtra(
                    "url",
                    entry.url
                )
        )
    }

    /*
     * =====================================================
     * HOME / NAVEGADOR
     * =====================================================
     */

    private fun showHome() {

        releasePreview()

        filter =
            null

        favoritesOnly =
            false

        selectedGroup =
            null

        selectedEntry =
            null

        selectedSeriesName =
            null

        selectedSeason =
            null

        b.content.visibility =
            View.GONE

        b.homePanel.visibility =
            View.VISIBLE

        b.previewWatch.text =
            "ASSISTIR"

        b.previewTopHost.visibility =
            View.GONE

        b.previewSideHost.visibility =
            View.GONE

        b.homeLive
            .requestFocus()
    }

    private fun showBrowser(
        type: ContentType
    ) {

        releasePreview()

        filter =
            type

        favoritesOnly =
            false

        selectedGroup =
            null

        selectedEntry =
            null

        selectedSeriesName =
            null

        selectedSeason =
            null

        b.homePanel.visibility =
            View.GONE

        b.content.visibility =
            View.VISIBLE

        b.previewWatch.text =
            "ASSISTIR"

        resetPreviewText()

        updatePreviewPosition()

        render()

        focusFirstCategory()
    }

    private fun resetPreviewText() {

        b.previewTitle.text =
            when (filter) {

                ContentType.LIVE ->
                    "Selecione um canal"

                ContentType.VOD ->
                    "Selecione um filme"

                ContentType.SERIES ->
                    "Selecione uma série"

                else ->
                    "Selecione um conteúdo"
            }

        b.previewGroup.text =
            ""
    }

    /*
     * =====================================================
     * FOCO INICIAL
     * =====================================================
     */

    private fun focusFirstCategory() {

        b.categoryList.post {

            val first =
                b.categoryList
                    .getChildAt(
                        0
                    )

            if (
                first !=
                null
            ) {

                first.requestFocus()

            } else {

                b.categoryList
                    .requestFocus()
            }
        }
    }

    private fun focusFirstMedia() {

        b.list.post {

            val first =
                b.list
                    .getChildAt(
                        0
                    )

            if (
                first !=
                null
            ) {

                first.requestFocus()

            } else {

                b.list
                    .requestFocus()
            }
        }
    }

    /*
     * =====================================================
     * BOTÃO VOLTAR
     * =====================================================
     */

    @Deprecated(
        "Usado para compatibilidade com TV Box"
    )
    override fun onBackPressed() {

        if (
            selectedSeriesName !=
            null
        ) {

            selectedSeriesName =
                null

            selectedSeason =
                null

            selectedEntry =
                null

            stopPreview()

            b.previewWatch.text =
                "ASSISTIR"

            render()

            focusFirstMedia()

            return
        }

        if (
            b.content.visibility ==
            View.VISIBLE
        ) {

            showHome()

            return
        }

        super.onBackPressed()
    }

    /*
     * =====================================================
     * SERVIDOR
     * =====================================================
     */

    private fun showServerSetup() {

        val field =
            EditText(
                this
            )
                .apply {

                    setText(
                        store.serverUrl
                    )

                    hint =
                        "https://painel.seudominio.com"

                    setSingleLine(
                        true
                    )
                }

        AlertDialog
            .Builder(
                this
            )
            .setTitle(
                "Servidor do painel"
            )
            .setMessage(
                "Informe o endereço HTTPS público do LPSM."
            )
            .setView(
                field
            )
            .setNegativeButton(
                "Cancelar",
                null
            )
            .setPositiveButton(
                "Salvar"
            ) {
                    _,
                    _ ->

                val value =
                    field.text
                        .toString()
                        .trim()

                if (
                    value.startsWith(
                        "https://"
                    ) ||

                    value.startsWith(
                        "http://"
                    )
                ) {

                    store.serverUrl =
                        value

                    api =
                        LpsmApi(
                            store
                        )

                    b.server
                        .setText(
                            store.serverUrl
                        )

                    store.token =
                        null

                    showActivation()

                    toast(
                        "Servidor atualizado"
                    )

                } else {

                    toast(
                        "Informe um endereço HTTP ou HTTPS válido"
                    )
                }
            }
            .show()
    }

    private fun toast(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    /*
     * =====================================================
     * CICLO DE VIDA
     * =====================================================
     */

    override fun onConfigurationChanged(
        newConfig: Configuration
    ) {

        super.onConfigurationChanged(
            newConfig
        )

        applyResponsiveSizing()

        updatePreviewPosition()

        resizeVisibleMediaCards()

        if (
            b.content.visibility ==
            View.VISIBLE
        ) {
            render()
        }
    }

    override fun onStop() {

        releasePreview()

        super.onStop()
    }

    override fun onDestroy() {

        activationHandler
            .removeCallbacksAndMessages(
                null
            )

        previewHandler
            .removeCallbacksAndMessages(
                null
            )

        releasePreview()

        pool.shutdownNow()

        super.onDestroy()
    }
}
