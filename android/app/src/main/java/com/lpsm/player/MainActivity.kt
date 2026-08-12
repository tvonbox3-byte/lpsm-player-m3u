package com.lpsm.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
         *
         * O EditText normalmente segura as setas para mover
         * o cursor. Interceptamos BAIXO e VOLTAR para que o
         * controle remoto nunca fique preso aqui.
         */
        b.search.isFocusable = true
        b.search.isFocusableInTouchMode = true
        b.search.setBackgroundResource(
            R.drawable.tv_search_background
        )

        b.search.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    preferredTopFilter().requestFocus()
                    true
                }

                android.view.KeyEvent.KEYCODE_BACK -> {
                    b.search.clearFocus()
                    preferredTopFilter().requestFocus()
                    true
                }

                else -> false
            }
        }

        /*
         * ÁREAS PRINCIPAIS
         */
        b.categoryList.isFocusable = true
        b.list.isFocusable = true
        b.previewWatch.isFocusable = true
        b.previewWatch.isFocusableInTouchMode = false
    }

    private fun preferredTopFilter(): View {
        return when {
            favoritesOnly -> b.favorites
            filter == ContentType.LIVE -> b.live
            filter == ContentType.VOD -> b.vod
            filter == ContentType.SERIES -> b.series
            else -> b.all
        }
    }

    private fun updateTopSelection() {
        b.all.isSelected =
            filter == null && !favoritesOnly

        b.live.isSelected =
            filter == ContentType.LIVE && !favoritesOnly

        b.vod.isSelected =
            filter == ContentType.VOD && !favoritesOnly

        b.series.isSelected =
            filter == ContentType.SERIES && !favoritesOnly

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

        when (filter) {

            ContentType.LIVE -> {

                /*
                 * Primeiro OK:
                 * fixa o canal no preview.
                 *
                 * Segundo OK:
                 * tela cheia.
                 */
                if (
                    selectedEntry?.url ==
                    item.url
                ) {

                    openPlayer(item)

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

                /*
                 * No filme, navegar pelas
                 * capas já mostra a prévia.
                 *
                 * OK abre o filme.
                 */
                openPlayer(item)
            }

            ContentType.SERIES -> {

                if (
                    selectedSeriesName ==
                    null
                ) {

                    /*
                     * Estamos nas capas
                     * das séries.
                     */
                    openSeries(
                        seriesKey(item)
                    )

                } else {

                    /*
                     * Estamos nos episódios.
                     */
                    openPlayer(item)
                }
            }

            else -> {
                openPlayer(item)
            }
        }
    }

    /*
     * =====================================================
     * FOCO DO CONTROLE REMOTO
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

                schedulePreview(
                    item,
                    850L
                )
            }

            ContentType.SERIES -> {

                if (
                    selectedSeriesName ==
                    null
                ) {

                    /*
                     * A capa representa
                     * uma série inteira.
                     *
                     * Encontramos o primeiro
                     * episódio para usar
                     * como prévia.
                     */
                    val name =
                        seriesKey(item)

                    val episodes =
                        seriesEpisodes(
                            name
                        )

                    val firstEpisode =
                        episodes.firstOrNull()

                    b.previewTitle.text =
                        name

                    b.previewGroup.text =
                        if (
                            episodes.size == 1
                        ) {
                            "Série • 1 episódio"
                        } else {
                            "Série • ${episodes.size} episódios"
                        }

                    if (
                        firstEpisode !=
                        null
                    ) {
                        schedulePreview(
                            firstEpisode,
                            900L
                        )
                    }

                } else {

                    /*
                     * Episódio destacado.
                     */
                    showPreviewInfo(
                        item
                    )

                    schedulePreview(
                        item,
                        750L
                    )
                }
            }

            else -> Unit
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

        /*
         * Dentro de uma série,
         * o menu da esquerda vira
         * lista de temporadas.
         */
        if (
            filter ==
                ContentType.SERIES &&
            selectedSeriesName !=
                null
        ) {

            selectedSeason =
                if (
                    category ==
                    "Todos"
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

        /*
         * Navegação normal:
         * Tudo / Favoritos / categorias.
         */
        favoritesOnly =
            category ==
            "Favoritos"

        selectedGroup =
            category.takeUnless {
                it == "Tudo" ||
                it == "Favoritos"
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
     *
     * TV ao vivo: preview lateral, como já estava funcionando.
     * Filmes e Séries: preview compacto no topo, ao lado da busca,
     * para liberar a área inferior para capas e episódios.
     */
    private fun updatePreviewPosition() {
        when (filter) {
            ContentType.LIVE -> {
                movePreviewTo(
                    host = b.previewSideHost,
                    compact = false
                )

                b.previewTopHost.visibility = View.GONE
                b.previewSideHost.visibility = View.VISIBLE
            }

            ContentType.VOD,
            ContentType.SERIES -> {
                b.previewTopHost.visibility = View.VISIBLE

                movePreviewTo(
                    host = b.previewTopHost,
                    compact = true
                )

                b.previewSideHost.visibility = View.GONE
            }

            else -> {
                movePreviewTo(
                    host = b.previewSideHost,
                    compact = false
                )

                b.previewTopHost.visibility = View.GONE
                b.previewSideHost.visibility = View.GONE
            }
        }
    }

    private fun movePreviewTo(
        host: FrameLayout,
        compact: Boolean
    ) {
        val currentParent =
            b.previewPanel.parent as? FrameLayout

        if (currentParent !== host) {
            currentParent?.removeView(
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
            b.previewPanel.getChildAt(0)

        videoFrame?.layoutParams =
            videoFrame?.layoutParams?.apply {
                height = dp(
                    if (compact) 128 else 197
                )
            }

        if (compact) {
            b.previewPanel.setPadding(
                0, 0, 0, 0
            )

            b.previewWatch.visibility =
                View.GONE

            b.previewTitle.textSize =
                16f

            b.previewGroup.textSize =
                12f

        } else {
            b.previewPanel.setPadding(
                dp(10), 0, 0, 0
            )

            b.previewWatch.visibility =
                View.VISIBLE

            b.previewTitle.textSize =
                20f

            b.previewGroup.textSize =
                14f
        }
    }

    private fun dp(value: Int): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    /*
     * =====================================================
     * PLAYER PEQUENO
     * =====================================================
     */

    private fun preparePreviewPlayerView() {

        val previewFrame =
            b.previewPanel
                .getChildAt(0)
                as? FrameLayout
                ?: return

        previewPlayerView =
            PlayerView(this).apply {

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams
                            .MATCH_PARENT,

                        FrameLayout.LayoutParams
                            .MATCH_PARENT
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
                    .removeCallbacks(it)
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

        /*
         * Se já está passando esse
         * mesmo vídeo, não reinicia.
         */
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
                    .Builder(this)
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
            entry.name

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
                    .removeCallbacks(it)
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

    /*
     * Item que está sendo usado
     * pelo botão ASSISTIR.
     */
    private fun currentPreviewEntry():
        MediaEntry? {

        val url =
            previewPlayingUrl
                ?: return selectedEntry

        return entries
            .firstOrNull {
                it.url == url
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
                            e.message ?: ""

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
                            60_000 -
                                all.size
                            )
                            .coerceAtLeast(0)

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
                        throw
                            IllegalStateException(
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
     * APARÊNCIA
     * =====================================================
     */

    private fun applyAppearance(
        wallpaper: Bitmap?,
        banner: Bitmap?
    ) {

        b.wallpaper
            .setImageBitmap(
                wallpaper
            )

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
                URL(url)
                    .openConnection()
                    as HttpURLConnection

            connection
                .connectTimeout =
                10_000

            connection
                .readTimeout =
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

        b.all.setOnClickListener {
            showHome()
        }

        b.live.setOnClickListener {
            showBrowser(
                ContentType.LIVE
            )
        }

        b.vod.setOnClickListener {
            showBrowser(
                ContentType.VOD
            )
        }

        b.series.setOnClickListener {
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
     * ORDEM DAS CATEGORIAS DE TV
     * =====================================================
     *
     * Coloca primeiro os canais mais comuns/locais e deixa
     * categorias adultas por último. O bloqueio por PIN será
     * ligado ao painel para que a senha seja escolhida pelo
     * administrador, em vez de ficar fixa dentro do APK.
     */
    private fun orderedCategoryEntries(
        indexedGroups: Map<String, List<MediaEntry>>
    ): List<Map.Entry<String, List<MediaEntry>>> {

        if (filter != ContentType.LIVE) {
            return indexedGroups
                .entries
                .sortedBy {
                    it.key.lowercase()
                }
        }

        return indexedGroups
            .entries
            .sortedWith(
                Comparator { first, second ->
                    val priority =
                        liveCategoryPriority(
                            first.key
                        ).compareTo(
                            liveCategoryPriority(
                                second.key
                            )
                        )

                    if (priority != 0) {
                        priority
                    } else {
                        first.key.compareTo(
                            second.key,
                            ignoreCase = true
                        )
                    }
                }
            )
    }

    private fun liveCategoryPriority(
        category: String
    ): Int {
        val value =
            category.lowercase()

        return when {
            isAdultCategory(value) -> 1000

            listOf(
                "rio grande do sul",
                "rio grande",
                "rbs",
                "gaucho",
                "gaúcho",
                "rs |",
                "| rs",
                " rs "
            ).any { it in value } -> 0

            listOf(
                "canais abertos",
                "tv aberta",
                "abertos"
            ).any { it in value } -> 10

            "globo" in value -> 20
            "sbt" in value -> 30
            "record" in value -> 40
            "band" in value -> 50

            listOf(
                "rede tv",
                "redetv"
            ).any { it in value } -> 60

            listOf(
                "noticia",
                "notícia",
                "news"
            ).any { it in value } -> 100

            listOf(
                "esporte",
                "sport"
            ).any { it in value } -> 110

            else -> 500
        }
    }

    private fun isAdultCategory(
        category: String
    ): Boolean {
        val value =
            category.lowercase()

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
            it in value
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

        val section =
            filter
                ?.let {
                    entriesByType[it]
                        .orEmpty()
                }
                ?: entries

        val indexedGroups =
            filter
                ?.let {
                    groupsByType[it]
                        .orEmpty()
                }
                ?: groupsAll

        /*
         * CATEGORIAS DO LADO ESQUERDO
         */
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
                ).forEach { entry ->

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
                    indexedGroups[it]
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

        /*
         * O quadrado pequeno fica visível, mas muda de posição:
         * TV ao vivo = lateral. Filmes/Séries = topo.
         */
        updatePreviewPosition()

        b.previewPanel.visibility =
            View.VISIBLE

        when (filter) {

            /*
             * TV AO VIVO
             */
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

            /*
             * FILMES
             */
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
                    "Navegue pelas capas para visualizar"
            }

            /*
             * SÉRIES:
             * transforma vários episódios
             * em UMA capa por série.
             */
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
                    "OK para abrir temporadas e episódios"
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
        source: List<MediaEntry>
    ): List<MediaEntry> {

        return source
            .groupBy {
                seriesKey(it)
            }
            .entries
            .sortedBy {
                it.key.lowercase()
            }
            .map {
                    (seriesName, episodes) ->

                val representative =
                    episodes.firstOrNull {
                        it.logo.isNotBlank()
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
            null

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

        /*
         * TEMPORADAS À ESQUERDA
         */
        val seasons =
            allEpisodes
                .mapNotNull {
                    it.season
                }
                .distinct()
                .sorted()

        val categories =
            buildList {

                add(
                    CategoryRow(
                        "Todos",
                        allEpisodes.size
                    )
                )

                seasons.forEach {
                        season ->

                    add(
                        CategoryRow(
                            "Temporada $season",

                            allEpisodes.count {
                                it.season ==
                                    season
                            }
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

                selectedSeason
                    ?.let {
                        "Temporada $it"
                    }
                    ?: "Todos"
            )

        /*
         * EPISÓDIOS
         */
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

        /*
         * Episódios ficam em LISTA,
         * não em dezenas de capas.
         */
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
            name

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
                seriesKey(it)
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

        /*
         * Segurança extra para listas
         * que não preencheram seriesName.
         */
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
    }

    private fun posterColumns():
        Int {

        return if (
            resources.configuration
                .smallestScreenWidthDp >=
            600
        ) {
            3
        } else {
            2
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
                        (_, values) ->

                    values.groupBy {

                        it.group
                            .ifBlank {
                                "Outros"
                            }
                    }
                }
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
                    .getChildAt(0)

            if (
                first != null
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
                    .getChildAt(0)

            if (
                first != null
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
     * BOTÃO VOLTAR DO CONTROLE
     * =====================================================
     */

    @Deprecated(
        "Usado para compatibilidade com TV Box"
    )
    override fun onBackPressed() {

        /*
         * Se estamos vendo episódios,
         * volta para as capas das séries.
         */
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

        /*
         * Se estamos em TV/Filmes/Séries,
         * volta para a Home.
         */
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
            EditText(this)
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
            .Builder(this)
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

                    b.server.setText(
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
