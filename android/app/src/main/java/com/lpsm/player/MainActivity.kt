package com.lpsm.player

import android.text.InputType

import android.app.Dialog
import android.app.ActivityManager
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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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

    private val adultPin = "0202"
    private val playlistRefreshIntervalMillis =
        30L * 60L * 1000L
    private val playlistFallbackMaxAgeMillis =
        7L * 24L * 60L * 60L * 1000L
    private var allowSearchFocus = false

    private lateinit var b: ActivityMainBinding
    private lateinit var store: SecureStore
    private lateinit var api: LpsmApi
    private lateinit var playlistCache: PlaylistCache

    /*
     * TV Boxes de entrada frequentemente têm 1 GB de RAM ou menos.
     * Quatro tarefas pesadas ao mesmo tempo (M3U + imagens + índices)
     * causavam disputa de CPU/memória e a tela parecia ficar travada em
     * "Carregando". Em aparelhos low-RAM usamos apenas 2 workers.
     */
    private val pool by lazy {
        Executors.newFixedThreadPool(
            if (isLowRamDevice()) 2 else 4
        )
    }

    private var entries =
        listOf<MediaEntry>()

    /*
     * Radios usam um catalogo publico independente da playlist do cliente.
     * O catalogo so e baixado ao abrir a secao para manter a HOME rapida.
     */
    private var radioEntries =
        listOf<MediaEntry>()

    private var radioMode =
        false

    private var radioLoading =
        false

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

    /* Índices prontos evitam varrer milhares de episódios a cada toque. */
    private var seriesEpisodesByName =
        emptyMap<String, List<MediaEntry>>()

    private var seriesCards =
        emptyList<MediaEntry>()

    private var seriesCardsByGroup =
        emptyMap<String, List<MediaEntry>>()

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
     * BUILD 44 - FULLSCREEN SEM REINICIAR O STREAM
     *
     * Mantemos o mesmo ExoPlayer da previa ao entrar em tela cheia.
     * Isso evita tela preta, novo buffer e o pequeno "salto para tras"
     * observado em algumas TV Boxes quando uma segunda Activity era aberta.
     */
    private var previewFullscreen = false
    private var fullscreenOverlay: FrameLayout? = null

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

    /*
     * =====================================================
     * APARÊNCIA DA HOME
     * =====================================================
     *
     * O painel salva as preferências da HOME dentro de
     * supportMessage no formato:
     *
     * [[LPSM_HOME|style=standard|banner=1|accent=7C4DFF]]
     *
     * Mantemos esse formato para não quebrar o backend atual.
     */
    private data class HomeVisualPrefs(
        val message: String = "Escolha o que deseja assistir",
        val style: String = "standard",
        val showBanner: Boolean = true,
        val accentHex: String = "7C4DFF"
    )

    private data class EntryIndexes(
        val byType: Map<ContentType, List<MediaEntry>>,
        val allGroups: Map<String, List<MediaEntry>>,
        val groupsByType:
            Map<ContentType, Map<String, List<MediaEntry>>>,
        val seriesEpisodesByName: Map<String, List<MediaEntry>>,
        val seriesCards: List<MediaEntry>,
        val seriesCardsByGroup: Map<String, List<MediaEntry>>
    )

    private var homeVisualPrefs =
        HomeVisualPrefs()

    private var homeBannerView:
        ImageView? = null

    private var homeSupportContact:
        TextView? = null

    private var homeSubtitle:
        TextView? = null

    private var homeButtonsRow:
        LinearLayout? = null

    private fun isLowRamDevice(): Boolean {
        val manager =
            getSystemService(ACTIVITY_SERVICE) as? ActivityManager

        return manager?.isLowRamDevice == true ||
            (manager?.memoryClass ?: 256) <= 128
    }

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
         * A verificacao continua depois que a HOME abre. Na versao anterior,
         * TV Boxes que levavam mais de 850 ms para responder ao GitHub perdiam
         * silenciosamente o aviso de atualizacao.
         */
        AppUpdateChecker.check(applicationContext) { info ->
            if (!isFinishing && !isDestroyed) {
                startActivity(
                    UpdateActivity.promptIntent(this, info)
                )
            }
        }

        /*
         * Ajusta o layout para o tamanho real
         * do celular, tablet, TV Box ou TV.
         */
        applyResponsiveSizing()

        /*
         * Cria o banner da HOME no topo e captura os
         * elementos que já existem no XML.
         */
        prepareHomeAppearance()

        store =
            SecureStore(this)

        api =
            LpsmApi(store)

        playlistCache =
            PlaylistCache(this)

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
        configureTvSearchKeyboard()

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
                },

                /*
                 * ATUALIZA A ESTRELA E O CONTADOR
                 * LOGO APOS FAVORITAR.
                 */
                { _, added ->

                    /*
                     * BUILD 42 - TV BOX / FAVORITOS
                     *
                     * O item ja atualiza a propria estrela no MediaAdapter.
                     * Recriar toda a tela aqui (render()) logo depois do OK/clique
                     * podia desmontar o RecyclerView enquanto o controle ainda
                     * entregava o evento, causando fechamento em algumas boxes.
                     * Mantemos somente o feedback visual e deixamos a lista
                     * intacta.
                     */
                    toast(
                        if (added) {
                            "Adicionado aos favoritos"
                        } else {
                            "Removido dos favoritos"
                        }
                    )
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
                        if (radioMode) {
                            selectedEntry = it
                            playPreviewNow(it)
                            b.previewWatch.text = "TOCANDO"
                        } else if (filter == ContentType.LIVE) {
                            enterPreviewFullscreen(it)
                        } else {
                            openPlayer(it)
                        }
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

        b.homeRadio
            .setOnClickListener {

                showBrowser(
                    ContentType.LIVE,
                    radios = true
                )
            }

        b.homeAccount
            .setOnClickListener {

                showAccount()
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
            b.homeRadio,
            b.homeAccount,
            b.all,
            b.live,
            b.vod,
            b.series,
            b.radio,
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
        b.homeLive.nextFocusLeftId = b.homeAccount.id
        b.homeVod.nextFocusLeftId = b.homeLive.id
        b.homeVod.nextFocusRightId = b.homeSeries.id
        b.homeSeries.nextFocusLeftId = b.homeVod.id
        b.homeSeries.nextFocusRightId = b.homeRadio.id
        b.homeRadio.nextFocusLeftId = b.homeSeries.id
        b.homeRadio.nextFocusRightId = b.homeAccount.id
        b.homeAccount.nextFocusLeftId = b.homeRadio.id
        b.homeAccount.nextFocusRightId = b.homeLive.id

        // A HOME possui somente uma linha; baixo permanece no mesmo botão.
        listOf(
            b.homeLive,
            b.homeVod,
            b.homeSeries,
            b.homeRadio,
            b.homeAccount
        ).forEach { it.nextFocusDownId = it.id }

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
        b.series.nextFocusRightId = b.radio.id

        b.radio.nextFocusLeftId = b.series.id
        b.radio.nextFocusRightId = b.favorites.id

        b.favorites.nextFocusLeftId = b.radio.id
        b.favorites.nextFocusRightId = b.all.id

        /*
         * Qualquer filtro sobe para a busca.
         */
        listOf(
            b.all,
            b.live,
            b.vod,
            b.series,
            b.radio,
            b.favorites
        ).forEach { button ->
            button.nextFocusUpId = b.search.id
            button.nextFocusDownId = b.list.id

            /*
             * Segurar UP serve para percorrer a lista rapidamente.
             * Ao chegar nos filtros, repeticoes do mesmo toque nao podem
             * jogar o foco no campo Pesquisar e abrir o teclado.
             * Um toque novo e curto em UP continua levando a busca.
             */
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (event.repeatCount > 0) {
                            true
                        } else {
                            allowSearchFocus = true
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.repeatCount == 0) {
                            enterContentFromTop(button)
                        }
                        true
                    }

                    else -> false
                }
            }
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
                KeyEvent.ACTION_DOWN
            ) {
                return@setOnKeyListener false
            }

            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    hideSearchKeyboard()
                    b.search.clearFocus()
                    preferredTopFilter()
                        .requestFocus()
                    true
                }

                KeyEvent.KEYCODE_BACK -> {
                    hideSearchKeyboard()
                    b.search.clearFocus()
                    preferredTopFilter()
                        .requestFocus()
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    showSearchKeyboard()
                    false
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

    /*
     * Navegacao deterministica para controles remotos. Diversas TV Boxes
     * calculam o proximo foco pela geometria e, ao apertar esquerda/cima,
     * pulavam da grade diretamente para a barra superior.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            ::b.isInitialized &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount > 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            b.search.hasFocus()
        ) {
            b.search.clearFocus()
            preferredTopFilter().requestFocus()
            return true
        }

        if (
            event.action == KeyEvent.ACTION_DOWN &&
            ::b.isInitialized &&
            b.content.visibility == View.VISIBLE
        ) {
            val focusedView = currentFocus

            if (focusedView != null && focusedView.isInside(b.list)) {
                val holder = b.list.findContainingViewHolder(focusedView)
                val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                val columns =
                    (b.list.layoutManager as? GridLayoutManager)
                        ?.spanCount
                        ?.coerceAtLeast(1)
                        ?: 1

                if (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                    position != RecyclerView.NO_POSITION &&
                    position % columns == 0
                ) {
                    focusSelectedCategory()
                    return true
                }

                if (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    position in 0 until columns
                ) {
                    if (event.repeatCount == 0) {
                        preferredTopFilter().requestFocus()
                    }
                    return true
                }

                if (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    position != RecyclerView.NO_POSITION &&
                    position + columns >= (b.list.adapter?.itemCount ?: 0)
                ) {
                    return true
                }
            }

            if (focusedView != null && focusedView.isInside(b.categoryList)) {
                val holder = b.categoryList.findContainingViewHolder(focusedView)
                val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION

                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return true
                }

                if (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                    position == 0
                ) {
                    if (event.repeatCount == 0) {
                        preferredTopFilter().requestFocus()
                    }
                    return true
                }

                if (
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    position != RecyclerView.NO_POSITION &&
                    position == (b.categoryList.adapter?.itemCount ?: 0) - 1
                ) {
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun View.isInside(container: View): Boolean {
        var candidate: View? = this
        while (candidate != null) {
            if (candidate === container) return true
            candidate = candidate.parent as? View
        }
        return false
    }

    private fun preferredTopFilter():
        View {

        return when {

            favoritesOnly ->
                b.favorites

            radioMode ->
                b.radio

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

    /**
     * Baixo na barra superior sempre entra no conteúdo correspondente.
     * Não deixamos o Android escolher geometricamente, pois algumas boxes
     * pulavam para a busca ou para outro botão do topo.
     */
    private fun enterContentFromTop(button: View) {
        when (button.id) {
            R.id.live -> enterBrowserContent(ContentType.LIVE)
            R.id.vod -> enterBrowserContent(ContentType.VOD)
            R.id.series -> enterBrowserContent(ContentType.SERIES)
            R.id.radio -> enterBrowserContent(ContentType.LIVE, radios = true)
            R.id.favorites -> {
                if (!favoritesOnly) {
                    selectedSeriesName = null
                    selectedSeason = null
                    favoritesOnly = true
                    selectedGroup = null
                    selectedEntry = null
                    stopPreview()
                    render()
                }
                focusFirstMedia()
            }
            else -> focusFirstMedia()
        }
    }

    private fun enterBrowserContent(
        type: ContentType,
        radios: Boolean = false
    ) {
        if (
            filter == type &&
            radioMode == radios &&
            !favoritesOnly
        ) {
            focusFirstMedia()
        } else {
            showBrowser(
                type = type,
                radios = radios,
                focusContent = true
            )
        }
    }

    private fun updateTopSelection() {

        b.all.isSelected =
            filter == null &&
                !favoritesOnly

        b.live.isSelected =
            filter ==
                ContentType.LIVE &&
                !radioMode &&
                !favoritesOnly

        b.vod.isSelected =
            filter ==
                ContentType.VOD &&
                !favoritesOnly

        b.series.isSelected =
            filter ==
                ContentType.SERIES &&
                !favoritesOnly

        b.radio.isSelected =
            radioMode &&
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

        if (radioMode) {
            selectedEntry =
                item

            showPreviewInfo(
                item
            )

            playPreviewNow(
                item
            )

            b.previewWatch.text =
                "TOCANDO"

            return
        }

        when (filter) {

            ContentType.LIVE -> {

                if (
                    selectedEntry?.url ==
                    item.url
                ) {

                    enterPreviewFullscreen(
                        item
                    )

                } else {

                    selectedEntry =
                        item

                    showPreviewInfo(
                        item
                    )

                    playPreviewNow(
                        item
                    )
                }
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

        when (filter) {

            ContentType.LIVE -> {

                showPreviewInfo(
                    item
                )

                schedulePreview(
                    item,
                    450L
                )
            }

            ContentType.VOD -> {

                showPreviewInfo(
                    item
                )
            }

            ContentType.SERIES -> {

                if (
                    selectedSeriesName ==
                    null
                ) {

                    val name =
                        seriesKey(
                            item
                        )

                    val episodes =
                        seriesEpisodes(
                            name
                        )

                    b.previewTitle.text =
                        displaySeriesTitle(
                            name,
                            episodes
                        )

                    b.previewGroup.text =
                        if (
                            episodes.size ==
                            1
                        ) {

                            "Série • 1 episódio"

                        } else {

                            "Série • ${episodes.size} episódios"
                        }

                } else {

                    showPreviewInfo(
                        item
                    )
                }
            }

            else ->
                Unit
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

        if (isAdultCategory(category)) {
            requestAdultPin(category)
            return
        }

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

        when (filter) {

            ContentType.LIVE -> {

                movePreviewTo(
                    host =
                        b.previewSideHost,

                    compact =
                        false
                )

                b.previewTopHost.visibility =
                    View.GONE

                b.previewSideHost.visibility =
                    View.VISIBLE
            }

            ContentType.VOD,
            ContentType.SERIES -> {

                b.previewTopHost.visibility =
                    View.VISIBLE

                movePreviewTo(
                    host =
                        b.previewTopHost,

                    compact =
                        true
                )

                b.previewSideHost.visibility =
                    View.GONE
            }

            else -> {

                movePreviewTo(
                    host =
                        b.previewSideHost,

                    compact =
                        false
                )

                b.previewTopHost.visibility =
                    View.GONE

                b.previewSideHost.visibility =
                    View.GONE
            }
        }
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

        return when {

            screenWidthDp() <
                650 ->
                125

            screenWidthDp() <
                800 ->
                155

            screenWidthDp() <
                1000 ->
                205

            screenWidthDp() <
                1300 ->
                260

            else ->
                310
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
                150

            screenWidthDp() <
                800 ->
                190

            screenWidthDp() <
                1000 ->
                250

            screenWidthDp() <
                1300 ->
                320

            else ->
                400
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

        /*
         * Na TV ao vivo a prévia deve ser realmente visível à distância.
         * Ela fica quase quadrada, limitada pela altura disponível para que
         * o nome do canal e o botão continuem aparecendo abaixo.
         */
        return (
            sidePreviewWidthDp() -
                8
            )
            .coerceAtMost(
                (
                    screenHeightDp() *
                        0.58f
                    )
                    .toInt()
            )
            .coerceAtLeast(
                88
            )
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

            b.homeRadio.textSize =
                homeButtonText

            b.homeAccount.textSize =
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
                null
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

                screenHeightDp() <=
                    520 ->
                    92

                screenHeightDp() <=
                    700 ->
                    104

                else ->
                    112
            }

        val imageWidth =
            when {

                screenHeightDp() <=
                    340 ->
                    50

                screenHeightDp() <=
                    380 ->
                    54

                screenHeightDp() <=
                    430 ->
                    60

                screenHeightDp() <=
                    520 ->
                    66

                screenHeightDp() <=
                    700 ->
                    76

                else ->
                    86
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

        view.findViewById<ImageView?>(
            R.id.mediaImage
        )
            ?.scaleType =
            ImageView.ScaleType.CENTER_INSIDE

        view.findViewById<TextView?>(
            R.id.title
        )
            ?.textSize =
            when {

                screenHeightDp() <= 340 -> 13f
                screenHeightDp() <= 380 -> 14f
                screenHeightDp() <= 430 -> 15f
                screenHeightDp() <= 700 -> 17f
                else -> 19f
            }

        view.findViewById<TextView?>(
            R.id.subtitle
        )
            ?.textSize =
            when {

                screenHeightDp() <= 340 -> 9f
                screenHeightDp() <= 380 -> 10f
                screenHeightDp() <= 520 -> 11f
                screenHeightDp() <= 700 -> 12f
                else -> 13f
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

        /*
         * O proprio quadrado da previa pode abrir a tela cheia.
         * Em TV Box o OK sobre a previa faz a mesma coisa.
         */
        previewFrame.isClickable = true
        previewFrame.isFocusable = true
        previewFrame.isFocusableInTouchMode = false
        previewFrame.setOnClickListener {
            currentPreviewEntry()?.let { entry ->
                if (radioMode) {
                    playPreviewNow(entry)
                } else if (filter == ContentType.LIVE) {
                    enterPreviewFullscreen(entry)
                } else {
                    openPlayer(entry)
                }
            }
        }
    }

    private fun configureTvSearchKeyboard() {
        /*
         * Em muitas boxes, um EditText que recebe foco por acidente abre o
         * teclado imediatamente. Desligamos essa abertura automatica e
         * mostramos o teclado apenas quando o usuario realmente entra na busca.
         */
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.search.showSoftInputOnFocus = false
        }

        b.search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!allowSearchFocus) {
                    b.search.post {
                        b.search.clearFocus()
                        preferredTopFilter().requestFocus()
                    }
                } else {
                    allowSearchFocus = false
                    showSearchKeyboard()
                }
            } else {
                hideSearchKeyboard()
            }
        }

        b.search.setOnClickListener {
            allowSearchFocus = true
            showSearchKeyboard()
        }
    }

    private fun showSearchKeyboard() {
        allowSearchFocus = true
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.search.showSoftInputOnFocus = true
        }
        b.search.requestFocus()
        b.search.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.search.windowToken, 0)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.search.showSoftInputOnFocus = false
        }
    }

    private fun enterPreviewFullscreen(entry: MediaEntry) {
        selectedEntry = entry

        /* Se a previa ainda nao iniciou, iniciamos uma unica vez. */
        if (previewPlayingUrl != entry.url || previewPlayer == null) {
            playPreviewNow(entry)
        }

        if (previewFullscreen) return

        val playerView = previewPlayerView ?: return
        val oldParent = playerView.parent as? ViewGroup ?: return
        oldParent.removeView(playerView)

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { exitPreviewFullscreen() }
        }

        overlay.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        (b.root as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        fullscreenOverlay = overlay
        previewFullscreen = true

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        overlay.requestFocus()
    }

    private fun exitPreviewFullscreen() {
        if (!previewFullscreen) return

        val playerView = previewPlayerView
        val overlay = fullscreenOverlay

        if (playerView != null) {
            (playerView.parent as? ViewGroup)?.removeView(playerView)

            val previewFrame =
                b.previewPanel.getChildAt(0) as? FrameLayout

            previewFrame?.addView(
                playerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        overlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        fullscreenOverlay = null
        previewFullscreen = false
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        b.list.post {
            val entry = selectedEntry
            val adapter = b.list.adapter as? MediaAdapter
            if (entry != null && adapter != null) {
                /* preserva a posicao atual; nao volta a lista para tras */
                b.list.requestFocus()
            }
        }
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

        (application as? LpsmApplication)
            ?.setNowPlaying(
                displayTitle(entry),
                entry.url,
                entry.group,
                entry.type.name
            )

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
            "Abrindo LPSM..."
        )

        pool.execute {

            /*
             * TV Boxes mais simples demoram muito mais que celulares para
             * esperar rede, descompactar a lista e montar todos os índices.
             * A HOME só pode abrir pelo modo rápido quando a configuração
             * e uma lista local com conteúdo estiverem disponíveis. Abrir
             * somente com a configuração deixava algumas TV Boxes presas em 0.
             * A validação online continua em segundo plano.
             */
            var openedFromCache =
                false

            /*
             * FAST START 2.2.23
             *
             * A HOME nao precisa esperar a lista M3U ser descompactada.
             * Em TV Boxes fracas, ler e agrupar ate 60 mil itens antes de
             * desenhar a HOME era o maior gargalo. Agora mostramos a HOME
             * imediatamente com a ultima configuracao autorizada e fazemos
             * cache, indices, M3U e EPG somente em segundo plano.
             */
            api.cachedConfig()
                ?.let { cachedConfig ->

                    openedFromCache = true
                    lastConfig = cachedConfig

                    runOnUiThread {
                        showContent(
                            cachedConfig,
                            0
                        )
                        if (entries.isEmpty()) {
                            b.message.text =
                                "Carregando sua lista em segundo plano..."
                        }
                    }
                }

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

                        } else if (
                            !openedFromCache
                        ) {

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

            /*
             * Se nao havia configuracao local (primeira abertura/limpeza de
             * dados), a resposta pequena do painel ja e suficiente para
             * liberar a HOME. A lista grande continua abaixo em background.
             */
            if (!openedFromCache) {
                runOnUiThread {
                    showContent(
                        config,
                        0
                    )
                    if (entries.isEmpty()) {
                        b.message.text =
                            "Carregando sua lista em segundo plano..."
                    }
                }
            }

            /*
             * Banner e papel de parede não podem mais atrasar a lista.
             * Eles são baixados em paralelo e aparecem assim que estiverem
             * prontos.
             */
            val wallpaperFuture =
                pool.submit<Bitmap?> {
                    loadBitmap(
                        config.appearance
                            .wallpaperUrl,
                        4
                    )
                }

            val bannerFuture =
                pool.submit<Bitmap?> {
                    loadBitmap(
                        config.appearance
                            .bannerUrl,
                        2
                    )
                }

            pool.execute {

                val wallpaper =
                    try {
                        wallpaperFuture.get()
                    } catch (_: Throwable) {
                        null
                    }

                val banner =
                    try {
                        bannerFuture.get()
                    } catch (_: Throwable) {
                        null
                    }

                runOnUiThread {
                    applyAppearance(
                        config.appearance,
                        wallpaper,
                        banner
                    )
                }
            }

            val uniquePlaylists =
                config.playlists
                    .distinctBy {

                        it.url
                            .trim()
                            .lowercase()
                    }

            val cacheSignature =
                playlistCache
                    .signature(
                        uniquePlaylists
                    )

            /*
             * Mostra a última cópia autorizada primeiro. A lista atualizada
             * continua sendo baixada abaixo sem travar a navegação.
             */
            /*
             * Se a mesma lista já foi aberta no começo, não descompacta e
             * indexa o arquivo inteiro uma segunda vez. Isso fazia diferença
             * grande em boxes com armazenamento e CPU lentos.
             */
            val cachedEntries =
                playlistCache.read(
                    cacheSignature,
                    playlistFallbackMaxAgeMillis
                )

            val cachedIndexes =
                cachedEntries
                    .takeIf { it.isNotEmpty() }
                    ?.let(::buildEntryIndexes)

            val cachedAgeMillis =
                playlistCache.ageMillis(
                    cacheSignature
                )

            if (
                cachedEntries
                    .isNotEmpty()
            ) {

                runOnUiThread {

                    val wasBrowsing =
                        b.content.visibility ==
                            View.VISIBLE

                    entries =
                        cachedEntries

                    applyEntryIndexes(
                        requireNotNull(
                            cachedIndexes
                        )
                    )

                    epg =
                        emptyMap()

                    if (
                        wasBrowsing
                    ) {

                        render()

                    } else {

                        showContent(
                            config,
                            0
                        )
                    }
                }
            }

            /*
             * Se a copia completa ainda e recente, nao ha motivo para baixar,
             * descompactar e reagrupar dezenas de milhares de itens novamente.
             * A configuracao do painel ja foi validada acima, portanto ativacao,
             * vencimento e troca de URL continuam imediatos.
             */
            if (
                cachedEntries.isNotEmpty() &&
                cachedAgeMillis != null &&
                cachedAgeMillis <= playlistRefreshIntervalMillis
            ) {
                runOnUiThread {
                    b.message.text =
                        "Lista pronta • ${cachedEntries.size} itens"
                }

                return@execute
            }

            runOnUiThread {

                if (
                    cachedEntries
                        .isEmpty()
                ) {
                    b.loadingLabel.text =
                        "Carregando ${uniquePlaylists.size} lista(s)..."
                }
            }

            val all =
                ArrayList<MediaEntry>(
                    8_192
                )

            val xmltvUrls =
                LinkedHashSet<String>()

            val errors =
                mutableListOf<String>()

            for (
                playlist in
                uniquePlaylists
            ) {

                try {

                    val remaining =
                        (
                            60_000 -
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

                    val partialCallback:
                        ((List<MediaEntry>) -> Unit)? =
                        if (
                            cachedEntries.isEmpty() &&
                            all.isEmpty()
                        ) {
                            { partial ->

                            /*
                             * BUILD 41: libera conteúdo parcial enquanto a
                             * lista grande ainda está chegando/processando.
                             * Assim uma box lenta já consegue navegar em vez
                             * de permanecer com as seções vazias.
                             */
                            val progressive =
                                ArrayList<MediaEntry>(
                                    all.size + partial.size
                                ).apply {
                                    addAll(all)
                                    addAll(partial)
                                }

                            val progressiveIndexes =
                                buildEntryIndexes(progressive)

                                runOnUiThread {
                                    entries = progressive
                                    applyEntryIndexes(progressiveIndexes)
                                    epg = emptyMap()

                                    b.message.text =
                                        "Lista carregando... ${progressive.size} itens disponíveis"

                                    if (b.content.visibility == View.VISIBLE) {
                                        render()
                                    }
                                }
                            }
                        } else {
                            null
                        }

                    val parsed =
                        api.downloadPlaylist(
                            playlist.url,
                            remaining,
                            partialCallback
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

                        xmltvUrls +=
                            playlist.xmltvUrl
                                .trim()
                    }

                } catch (
                    e: Throwable
                ) {

                    errors +=
                        "${playlist.name}: ${e.message ?: "indisponível"}"
                }
            }

            val freshEntries =
                all.toList()

            /*
             * Uma atualizacao incompleta nunca substitui a ultima lista boa.
             * Isso evita sumir categorias quando um dos servidores oscila.
             */
            val keepCompleteCache =
                errors.isNotEmpty() &&
                    cachedEntries.isNotEmpty()

            val displayEntries =
                if (keepCompleteCache) {
                    cachedEntries
                } else {
                    freshEntries
                }

            val displayIndexes =
                if (keepCompleteCache) {
                    cachedIndexes
                } else if (
                    displayEntries.isNotEmpty()
                ) {
                    buildEntryIndexes(
                        displayEntries
                    )
                } else {
                    null
                }

            runOnUiThread {

                if (
                    freshEntries
                        .isEmpty()
                ) {

                    if (
                        cachedEntries
                            .isNotEmpty()
                    ) {

                        b.message.text =
                            "Usando a última lista salva. A atualização do servidor está indisponível."

                        return@runOnUiThread
                    }

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
                        null
                    )

                } else {

                    val wasBrowsing =
                        b.content.visibility ==
                            View.VISIBLE

                    entries =
                        displayEntries

                    applyEntryIndexes(
                        requireNotNull(
                            displayIndexes
                        )
                    )

                    epg =
                        emptyMap()

                    if (
                        wasBrowsing
                    ) {

                        render()

                    } else {

                        showContent(
                            config,
                            errors.size
                        )
                    }

                    if (keepCompleteCache) {
                        b.message.text =
                            "Lista salva mantida • atualização do servidor incompleta"
                    } else if (errors.isEmpty()) {
                        b.message.text =
                            "Lista pronta • ${displayEntries.size} itens"
                    }
                }
            }

            if (
                freshEntries.isNotEmpty() &&
                errors.isEmpty()
            ) {

                playlistCache
                    .write(
                        cacheSignature,
                        freshEntries
                    )
            }

            /*
             * O guia EPG é complementar. Ele passa a carregar somente
             * depois de a lista já estar liberada para o cliente.
             */
            if (
                freshEntries
                    .isNotEmpty() &&
                xmltvUrls
                    .isNotEmpty()
            ) {

                pool.execute {

                    val guides =
                        mutableMapOf<String, String>()

                    xmltvUrls
                        .forEach { url ->

                            try {

                                guides +=
                                    XmlTvParser
                                        .current(
                                            api.download(
                                                url
                                            )
                                        )

                            } catch (_: Throwable) {
                            }
                        }

                    if (
                        guides
                            .isNotEmpty()
                    ) {

                        runOnUiThread {
                            epg =
                                guides.toMap()
                        }
                    }
                }
            }
        }
    }

    /*
     * =====================================================
     * APARÊNCIA
     * =====================================================
     */

    private fun prepareHomeAppearance() {

        /*
         * O XML original possui:
         * 0 = título
         * 1 = subtítulo
         * 2 = linha dos três botões
         *
         * Guardamos essas referências antes de inserir
         * o banner como o novo primeiro item.
         */
        if (
            homeSubtitle ==
            null
        ) {

            homeSubtitle =
                b.homePanel
                    .getChildAt(
                        1
                    )
                    as? TextView
        }

        if (
            homeButtonsRow ==
            null
        ) {

            homeButtonsRow =
                b.homeLive.parent
                    as? LinearLayout
        }

        if (
            homeBannerView ==
            null
        ) {

            val banner =
                ImageView(this)
                    .apply {

                        scaleType =
                            ImageView.ScaleType.CENTER_CROP

                        adjustViewBounds =
                            false

                        visibility =
                            View.GONE

                        contentDescription =
                            "Banner da tela inicial"

                        setBackgroundColor(
                            Color.rgb(
                                8,
                                14,
                                24
                            )
                        )
                    }

            b.homePanel
                .addView(
                    banner,
                    0
                )

            homeBannerView =
                banner
        }

        if (
            homeSupportContact ==
            null
        ) {

            val contact =
                TextView(this)
                    .apply {
                        text =
                            "SUPORTE: (54) 99714-6384"

                        gravity =
                            Gravity.CENTER

                        textSize =
                            16f

                        setTextColor(
                            Color.WHITE
                        )

                        setCompoundDrawablesWithIntrinsicBounds(
                            R.drawable.ic_support_robot,
                            0,
                            0,
                            0
                        )

                        compoundDrawablePadding =
                            dp(8)

                        setPadding(
                            dp(8),
                            dp(5),
                            dp(8),
                            dp(5)
                        )
                    }

            b.homePanel
                .addView(
                    contact,
                    1
                )

            homeSupportContact =
                contact
        }

        /*
         * Antes o painel inteiro ficava centralizado.
         * Como a linha de botões tinha layout_weight=1,
         * os botões acabavam ocupando quase toda a tela.
         *
         * Agora tudo começa pelo topo:
         * BANNER -> TÍTULO -> SUBTÍTULO -> BOTÕES.
         */
        b.homePanel.gravity =
            Gravity.TOP or
                Gravity.CENTER_HORIZONTAL
    }


    private fun parseHomeVisualPrefs(
        rawSupportMessage: String
    ): HomeVisualPrefs {

        val markerRegex =
            Regex(
                """\[\[LPSM_HOME\|([^\]]+)]]""",
                RegexOption.IGNORE_CASE
            )

        val match =
            markerRegex
                .find(
                    rawSupportMessage
                )

        var style =
            "standard"

        var showBanner =
            true

        var accent =
            "7C4DFF"

        match
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.split(
                "|"
            )
            ?.forEach {
                    part ->

                val separator =
                    part.indexOf(
                        "="
                    )

                if (
                    separator <=
                    0
                ) {

                    return@forEach
                }

                val key =
                    part
                        .substring(
                            0,
                            separator
                        )
                        .trim()
                        .lowercase()

                val value =
                    part
                        .substring(
                            separator +
                                1
                        )
                        .trim()

                when (
                    key
                ) {

                    "style" -> {

                        style =
                            value
                                .lowercase()
                    }

                    "banner" -> {

                        showBanner =
                            value !=
                                "0" &&
                                !value.equals(
                                    "false",
                                    true
                                )
                    }

                    "accent" -> {

                        accent =
                            value
                                .replace(
                                    "#",
                                    ""
                                )
                                .trim()
                                .uppercase()
                    }
                }
            }

        if (
            style !in
            listOf(
                "standard",
                "compact",
                "classic"
            )
        ) {

            style =
                "standard"
        }

        if (
            !Regex(
                "^[0-9A-F]{6}$"
            )
                .matches(
                    accent
                )
        ) {

            accent =
                "7C4DFF"
        }

        val cleanMessage =
            rawSupportMessage
                .replace(
                    markerRegex,
                    ""
                )
                .trim()
                .ifBlank {

                    "Escolha o que deseja assistir"
                }

        return HomeVisualPrefs(
            message =
                cleanMessage,

            style =
                style,

            showBanner =
                showBanner,

            accentHex =
                accent
        )
    }


    private fun applyAppearance(
        appearance: Appearance,
        wallpaper: Bitmap?,
        banner: Bitmap?
    ) {

        b.wallpaper
            .setImageBitmap(
                wallpaper
            )

        /*
         * Mantém o banner também na tela de falha,
         * como já acontecia antes.
         */
        b.failureBanner
            .setImageBitmap(
                banner
            )

        homeVisualPrefs =
            parseHomeVisualPrefs(
                appearance
                    .supportMessage
            )

        applyHomeAppearance(
            appearance =
                appearance,

            banner =
                banner
        )
    }


    private fun applyHomeAppearance(
        appearance: Appearance,
        banner: Bitmap?
    ) {

        prepareHomeAppearance()

        val prefs =
            homeVisualPrefs

        /*
         * Mensagem real da HOME, sem o marcador técnico
         * [[LPSM_HOME|...]].
         */
        homeSubtitle
            ?.text =
            prefs.message

        val accentColor =
            try {

                Color.parseColor(
                    "#${prefs.accentHex}"
                )

            } catch (
                _: Exception
            ) {

                Color.parseColor(
                    "#7C4DFF"
                )
            }

        val accentFocused =
            lightenColor(
                accentColor,
                0.20f
            )

        homeSupportContact
            ?.setTextColor(
                accentColor
            )

        val homeTint =
            android.content.res
                .ColorStateList(
                    arrayOf(
                        intArrayOf(
                            android.R.attr
                                .state_focused
                        ),
                        intArrayOf(
                            android.R.attr
                                .state_selected
                        ),
                        intArrayOf()
                    ),
                    intArrayOf(
                        accentFocused,
                        accentFocused,
                        accentColor
                    )
                )

        val heightDp =
            screenHeightDp()

        val veryCompact =
            isVeryCompactScreen()

        val compact =
            isCompactScreen()

        /*
         * Cada modelo possui dimensões realmente diferentes.
         * Não usamos mais layout_weight=1 na linha dos botões.
         */
        val bannerHeightDp:
            Int

        val buttonsHeightDp:
            Int

        val buttonTextSize:
            Float

        val welcomeTextSize:
            Float

        val panelTopPaddingDp:
            Int

        when (
            prefs.style
        ) {

            "compact" -> {

                bannerHeightDp =
                    when {
                        heightDp <=
                            380 ->
                            72

                        heightDp <=
                            520 ->
                            88

                        heightDp <=
                            700 ->
                            105

                        else ->
                            125
                    }

                buttonsHeightDp =
                    when {
                        veryCompact ->
                            60

                        compact ->
                            68

                        else ->
                            78
                    }

                buttonTextSize =
                    when {
                        veryCompact ->
                            11f

                        compact ->
                            13f

                        else ->
                            15f
                    }

                welcomeTextSize =
                    when {
                        veryCompact ->
                            17f

                        compact ->
                            20f

                        else ->
                            23f
                    }

                panelTopPaddingDp =
                    6
            }

            "classic" -> {

                bannerHeightDp =
                    when {
                        heightDp <=
                            380 ->
                            82

                        heightDp <=
                            520 ->
                            100

                        heightDp <=
                            700 ->
                            125

                        else ->
                            145
                    }

                /*
                 * No clássico, os três botões ficam empilhados.
                 */
                buttonsHeightDp =
                    when {
                        veryCompact ->
                            132

                        compact ->
                            154

                        else ->
                            174
                    }

                buttonTextSize =
                    when {
                        veryCompact ->
                            11f

                        compact ->
                            13f

                        else ->
                            15f
                    }

                welcomeTextSize =
                    when {
                        veryCompact ->
                            18f

                        compact ->
                            21f

                        else ->
                            24f
                    }

                panelTopPaddingDp =
                    8
            }

            else -> {

                /*
                 * MODELO PADRÃO:
                 * banner maior em cima e três cartões menores abaixo.
                 */
                bannerHeightDp =
                    when {
                        heightDp <=
                            380 ->
                            92

                        heightDp <=
                            520 ->
                            118

                        heightDp <=
                            700 ->
                            148

                        else ->
                            178
                    }

                buttonsHeightDp =
                    when {
                        veryCompact ->
                            72

                        compact ->
                            88

                        else ->
                            105
                    }

                buttonTextSize =
                    when {
                        veryCompact ->
                            12f

                        compact ->
                            15f

                        else ->
                            17f
                    }

                welcomeTextSize =
                    when {
                        veryCompact ->
                            18f

                        compact ->
                            21f

                        else ->
                            25f
                    }

                panelTopPaddingDp =
                    8
            }
        }

        b.homePanel
            .setPadding(
                dp(
                    8
                ),
                dp(
                    panelTopPaddingDp
                ),
                dp(
                    8
                ),
                dp(
                    8
                )
            )

        b.homeWelcome.textSize =
            welcomeTextSize

        homeSubtitle
            ?.textSize =
            when {

                veryCompact ->
                    10f

                compact ->
                    12f

                else ->
                    14f
            }

        /*
         * Banner de verdade no TOPO da HOME.
         *
         * Se "Mostrar banner" estiver ligado e existir URL,
         * o espaço fica reservado no topo mesmo se a imagem
         * demorar ou falhar ao baixar.
         */
        val shouldShowBanner =
            prefs.showBanner &&
                appearance
                    .bannerUrl
                    .isNotBlank()

        homeBannerView
            ?.apply {

                visibility =
                    if (
                        shouldShowBanner
                    ) {

                        View.VISIBLE

                    } else {

                        View.GONE
                    }

                setImageBitmap(
                    if (
                        shouldShowBanner
                    ) {

                        banner

                    } else {

                        null
                    }
                )

                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout
                                .LayoutParams
                                .MATCH_PARENT,

                            if (
                                shouldShowBanner
                            ) {

                                dp(
                                    bannerHeightDp
                                )

                            } else {

                                0
                            }
                        )
                        .apply {

                            setMargins(
                                dp(
                                    8
                                ),
                                0,
                                dp(
                                    8
                                ),
                                dp(
                                    if (
                                        shouldShowBanner
                                    ) {
                                        8
                                    } else {
                                        0
                                    }
                                )
                            )
                        }
            }

        val row =
            homeButtonsRow

        if (
            row !=
            null
        ) {

            val classic =
                prefs.style ==
                    "classic"

            row.orientation =
                if (
                    classic
                ) {

                    LinearLayout.VERTICAL

                } else {

                    LinearLayout.HORIZONTAL
                }

            row.gravity =
                Gravity.CENTER

            row.setPadding(
                dp(
                    if (
                        compact
                    ) {
                        2
                    } else {
                        5
                    }
                ),
                0,
                dp(
                    if (
                        compact
                    ) {
                        2
                    } else {
                        5
                    }
                ),
                0
            )

            row.layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout
                            .LayoutParams
                            .MATCH_PARENT,

                        dp(
                            buttonsHeightDp
                        ),
                        0f
                    )
                    .apply {

                        setMargins(
                            0,
                            dp(
                                if (
                                    prefs.style ==
                                    "compact"
                                ) {
                                    6
                                } else {
                                    10
                                }
                            ),
                            0,
                            0
                        )
                    }

            listOf(
                b.homeLive,
                b.homeVod,
                b.homeSeries,
                b.homeRadio,
                b.homeAccount
            )
                .forEach {
                        button ->

                    button.textSize =
                        buttonTextSize

                    button.minHeight =
                        0

                    button.minimumHeight =
                        0

                    button.minWidth =
                        0

                    button.minimumWidth =
                        0

                    button.backgroundTintList =
                        homeTint

                    button.setPadding(
                        dp(
                            8
                        ),
                        dp(
                            4
                        ),
                        dp(
                            8
                        ),
                        dp(
                            4
                        )
                    )

                    button.layoutParams =
                        if (
                            classic
                        ) {

                            LinearLayout
                                .LayoutParams(
                                    LinearLayout
                                        .LayoutParams
                                        .MATCH_PARENT,
                                    0,
                                    1f
                                )
                                .apply {

                                    setMargins(
                                        dp(
                                            4
                                        ),
                                        dp(
                                            2
                                        ),
                                        dp(
                                            4
                                        ),
                                        dp(
                                            2
                                        )
                                    )
                                }

                        } else {

                            LinearLayout
                                .LayoutParams(
                                    0,
                                    LinearLayout
                                        .LayoutParams
                                        .MATCH_PARENT,
                                    1f
                                )
                                .apply {

                                    setMargins(
                                        dp(
                                            4
                                        ),
                                        0,
                                        dp(
                                            4
                                        ),
                                        0
                                    )
                                }
                        }
                }
        }

        /*
         * Força novo cálculo das medidas imediatamente.
         */
        b.homePanel
            .requestLayout()
    }


    private fun lightenColor(
        color: Int,
        amount: Float
    ): Int {

        val safeAmount =
            amount
                .coerceIn(
                    0f,
                    1f
                )

        val red =
            (
                Color.red(
                    color
                ) +
                (
                    255 -
                    Color.red(
                        color
                    )
                    ) *
                safeAmount
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        val green =
            (
                Color.green(
                    color
                ) +
                (
                    255 -
                    Color.green(
                        color
                    )
                    ) *
                safeAmount
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        val blue =
            (
                Color.blue(
                    color
                ) +
                (
                    255 -
                    Color.blue(
                        color
                    )
                    ) *
                safeAmount
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        return Color.rgb(
            red,
            green,
            blue
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

            connection.instanceFollowRedirects =
                true

            connection.setRequestProperty(
                "User-Agent",
                "LPSM-Player/2.2.9 Android"
            )

            connection.setRequestProperty(
                "Accept",
                "image/*,*/*;q=0.8"
            )

            connection.connect()

            if (
                connection.responseCode !in
                200..299
            ) {

                connection.disconnect()

                return null
            }

            val bitmap =
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

            connection.disconnect()

            bitmap

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

        b.failureMac.text =
            "MAC: $macAddress"

        b.failureSupport.text =
            "SUPORTE: (54) 99714-6384"

        banner
            ?.let {

                b.failureBanner
                    .setImageBitmap(
                        it
                    )
            }
    }

    private fun showAccount() {

        AlertDialog
            .Builder(this)
            .setIcon(
                R.drawable.ic_support_robot
            )
            .setTitle(
                "CONTA LPSM"
            )
            .setMessage(
                "MAC DO APARELHO\n$macAddress\n\nSUPORTE\n(54) 99714-6384"
            )
            .setPositiveButton(
                "FECHAR",
                null
            )
            .show()
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

                homeVisualPrefs
                    .message
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

        b.radio
            .setOnClickListener {

                showBrowser(
                    ContentType.LIVE,
                    radios = true
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
                .sortedWith(
                    compareBy<Map.Entry<String, List<MediaEntry>>>(
                        { isAdultCategory(it.key) },
                        { it.key.lowercase() }
                    )
                )
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

    private var unlockedAdultGroup: String? = null

    private fun requestAdultPin(category: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            isSingleLine = true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Conteúdo adulto")
            .setMessage("Digite o PIN para acessar esta categoria.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                if (input.text.toString() == adultPin) {
                    unlockedAdultGroup = category
                    favoritesOnly = false
                    selectedGroup = category
                    selectedEntry = null
                    stopPreview()
                    resetPreviewText()
                    dialog.dismiss()
                    render()
                    focusFirstMedia()
                } else {
                    input.error = "PIN incorreto"
                    input.selectAll()
                }
            }
            input.requestFocus()
        }

        dialog.setOnDismissListener {
            hideSearchKeyboard()
        }
        dialog.show()
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
                "jogos de hoje",
                "jogos hoje",
                "jogo de hoje"
            ).any { it in value } ->
                -20

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

        /*
         * Na raiz de SERIES a grade mostra uma capa por serie, nao um card
         * para cada episodio. Usar os episodios aqui fazia "Tudo" e as
         * categorias anunciarem milhares de itens que nao existiam na grade.
         * A fonte da contagem agora e exatamente a mesma fonte da tela.
         */
        val rootSeries =
            !radioMode &&
                filter == ContentType.SERIES

        val section =
            when {

                radioMode ->
                    radioEntries

                rootSeries ->
                    seriesCards

                else ->
                    filter
                        ?.let {
                            entriesByType[it]
                                .orEmpty()
                        }
                        ?: entries
            }

        val indexedGroups =
            when {

                radioMode ->
                    radioEntries.groupBy {
                        it.group.ifBlank { "Brasil" }
                    }

                rootSeries ->
                    seriesCardsByGroup

                else ->
                    filter
                        ?.let {
                            groupsByType[it]
                                .orEmpty()
                        }
                        ?: groupsAll
            }

        /* O total deve refletir somente o que "Tudo" realmente exibe. */
        val countableSection =
            section.filter {
                !isAdultCategory(it.group) ||
                    it.group == unlockedAdultGroup
            }

        val categories =
            buildList {

                add(
                    CategoryRow(
                        "Tudo",
                        countableSection.size
                    )
                )

                add(
                    CategoryRow(
                        "Favoritos",

                        countableSection.count {

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

                val adultOkay =
                    !isAdultCategory(it.group) ||
                        (selectedGroup != null && selectedGroup == unlockedAdultGroup)

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
                    adultOkay &&
                    queryOkay
            }

        updatePreviewPosition()

        b.previewPanel.visibility =
            View.VISIBLE

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
        return seriesEpisodesByName[
            name.lowercase()
        ].orEmpty()
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

        val availableWidth =
            (
                screenWidthDp() -
                    categoryWidthDp() -
                    24
                )
                .coerceAtLeast(
                    220
                )

        val targetCardWidth =
            when {

                screenHeightDp() <=
                    380 ->
                    112

                screenHeightDp() <=
                    520 ->
                    145

                else ->
                    190
            }

        return (
            availableWidth /
                (targetCardWidth + 10)
            )
            .coerceIn(
                2,
                6
            )
    }

    /*
     * =====================================================
     * ÍNDICES
     * =====================================================
     */

    private fun rebuildIndex() {

        applyEntryIndexes(
            buildEntryIndexes(
                entries
            )
        )
    }

    private fun buildEntryIndexes(
        source: List<MediaEntry>
    ): EntryIndexes {

        val byType =
            source.groupBy {

                it.type
            }

        val allGroups =
            source.groupBy {

                it.group
                    .ifBlank {

                        "Outros"
                    }
            }

        val indexedGroupsByType =
            byType
                .mapValues {
                        (_, values) ->

                    values.groupBy {

                        it.group
                            .ifBlank {

                                "Outros"
                            }
                    }
                }

        val episodeOrder =
            compareBy<MediaEntry>(
                { it.season ?: 0 },
                { it.episode ?: 0 },
                { it.name.lowercase() }
            )

        val indexedSeriesEpisodes =
            byType[ContentType.SERIES]
                .orEmpty()
                .groupBy {
                    seriesKey(it).lowercase()
                }
                .mapValues { (_, episodes) ->
                    episodes.sortedWith(episodeOrder)
                }

        val indexedSeriesCards =
            createSeriesCards(
                byType[ContentType.SERIES]
                    .orEmpty()
            )

        val indexedSeriesCardsByGroup =
            indexedGroupsByType[ContentType.SERIES]
                .orEmpty()
                .mapValues { (_, episodes) ->
                    createSeriesCards(episodes)
                }

        return EntryIndexes(
            byType = byType,
            allGroups = allGroups,
            groupsByType = indexedGroupsByType,
            seriesEpisodesByName = indexedSeriesEpisodes,
            seriesCards = indexedSeriesCards,
            seriesCardsByGroup = indexedSeriesCardsByGroup
        )
    }

    private fun applyEntryIndexes(
        indexes: EntryIndexes
    ) {

        entriesByType =
            indexes.byType

        groupsAll =
            indexes.allGroups

        groupsByType =
            indexes.groupsByType

        seriesEpisodesByName =
            indexes.seriesEpisodesByName

        seriesCards =
            indexes.seriesCards

        seriesCardsByGroup =
            indexes.seriesCardsByGroup
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
                .putExtra(
                    "group",
                    entry.group
                )
                .putExtra(
                    "type",
                    entry.type.name
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

        radioMode =
            false

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
        type: ContentType,
        radios: Boolean = false,
        focusContent: Boolean = false
    ) {

        releasePreview()

        filter =
            type

        radioMode =
            radios

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
            if (radios) {
                "TOCAR"
            } else {
                "ASSISTIR"
            }

        resetPreviewText()

        updatePreviewPosition()

        render()

        if (
            radios
        ) {
            loadRadiosIfNeeded()
        }

        if (focusContent) {
            focusFirstMedia()
        } else {
            focusFirstCategory()
        }
    }

    private fun loadRadiosIfNeeded() {

        if (
            radioLoading
        ) {
            return
        }

        /* Vacaria e regiao aparecem no mesmo instante, mesmo sem servidor. */
        if (radioEntries.isEmpty()) {
            radioEntries = RadioBrowserApi.featuredStations()
            render()
        }

        radioLoading =
            true

        b.previewTitle.text =
            "Carregando rádios brasileiras..."

        b.previewGroup.text =
            "Aguarde um instante"

        pool.execute {

            try {

                val immediate =
                    RadioBrowserApi.initialStations(applicationContext)

                runOnUiThread {

                    radioEntries = immediate

                    if (
                        radioMode &&
                        b.content.visibility ==
                            View.VISIBLE
                    ) {
                        render()
                    }
                }

                val loaded =
                    if (RadioBrowserApi.needsRefresh(applicationContext)) {
                        RadioBrowserApi.refreshBrazilianStations(applicationContext)
                    } else {
                        immediate
                    }

                runOnUiThread {
                    radioEntries = loaded
                    radioLoading = false

                    if (
                        radioMode &&
                        b.content.visibility == View.VISIBLE
                    ) {
                        render()
                    }
                }

            } catch (
                error: Throwable
            ) {

                runOnUiThread {

                    radioLoading =
                        false

                    if (
                        radioMode &&
                        radioEntries.isEmpty()
                    ) {
                        b.previewTitle.text =
                            "Rádios indisponíveis"

                        b.previewGroup.text =
                            "Volte e abra Rádios para tentar novamente"

                        toast(
                            error.message
                                ?: "Falha ao carregar rádios"
                        )
                    } else if (radioMode) {
                        b.previewGroup.text =
                            "Catálogo salvo disponível"
                    }
                }
            }
        }
    }

    private fun resetPreviewText() {

        b.previewTitle.text =
            if (
                radioMode
            ) {

                "Selecione uma rádio"

            } else when (filter) {

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
        b.categoryList.scrollToPosition(0)
        b.categoryList.post {
            b.categoryList.post {
                b.categoryList
                    .findViewHolderForAdapterPosition(0)
                    ?.itemView
                    ?.requestFocus()
            }
        }
    }

    private fun focusSelectedCategory() {
        val position =
            (b.categoryList.adapter as? CategoryAdapter)
                ?.selectedPosition()
                ?: 0

        b.categoryList.scrollToPosition(position)
        b.categoryList.post {
            b.categoryList.post {
                b.categoryList
                    .findViewHolderForAdapterPosition(position)
                    ?.itemView
                    ?.requestFocus()
            }
        }
    }

    private fun focusFirstMedia() {
        if ((b.list.adapter?.itemCount ?: 0) == 0) {
            focusSelectedCategory()
            return
        }

        b.list.scrollToPosition(0)
        b.list.post {
            b.list.post {
                val focused = b.list
                    .findViewHolderForAdapterPosition(0)
                    ?.itemView
                    ?.requestFocus()

                if (focused != true) {
                    b.list.getChildAt(0)?.requestFocus()
                }
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

        if (previewFullscreen) {
            exitPreviewFullscreen()
            return
        }

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
