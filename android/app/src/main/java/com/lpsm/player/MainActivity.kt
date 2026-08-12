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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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

    private val pool = Executors.newSingleThreadExecutor()

    private var entries = listOf<MediaEntry>()
    private var epg = emptyMap<String, String>()

    private var filter: ContentType? = null
    private var favoritesOnly = false
    private var selectedGroup: String? = null
    private var selectedEntry: MediaEntry? = null

    private var entriesByType =
        emptyMap<ContentType, List<MediaEntry>>()

    private var groupsAll =
        emptyMap<String, List<MediaEntry>>()

    private var groupsByType =
        emptyMap<ContentType, Map<String, List<MediaEntry>>>()

    private var lastConfig: DeviceConfig? = null

    private val activationHandler =
        Handler(Looper.getMainLooper())

    private val activationCheck =
        Runnable { silentActivate() }

    private val macAddress by lazy {
        store.installMac
    }

    /*
     * PLAYER PEQUENO DA TV AO VIVO
     */
    private var previewPlayer: ExoPlayer? = null
    private var previewPlayerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SecureStore(this)
        api = LpsmApi(store)

        b.deviceId.text = macAddress
        b.simpleDeviceId.text = macAddress

        b.simpleLogo.setOnLongClickListener {
            showServerSetup()
            true
        }

        b.code.visibility = View.GONE
        b.activateButton.text = "ATUALIZAR"

        b.server.setText(store.serverUrl)

        /*
         * CRIA O PLAYER PEQUENO DENTRO
         * DA JANELA DE PRÉ-VISUALIZAÇÃO.
         */
        preparePreviewPlayerView()

        /*
         * LISTA DE CONTEÚDO
         */
        b.list.layoutManager =
            GridLayoutManager(
                this,
                if (
                    resources.configuration
                        .smallestScreenWidthDp >= 600
                ) 4 else 2
            )

        b.list.adapter =
            MediaAdapter(store) { item ->

                if (filter == ContentType.LIVE) {

                    /*
                     * PRIMEIRO CLIQUE:
                     * abre no player pequeno.
                     *
                     * SEGUNDO CLIQUE NO MESMO CANAL:
                     * abre tela cheia.
                     */
                    if (
                        selectedEntry?.url == item.url
                    ) {
                        openPlayer(item)
                    } else {

                        selectedEntry = item

                        b.previewTitle.text =
                            item.name

                        b.previewGroup.text =
                            item.group

                        playPreview(item)
                    }

                } else {

                    /*
                     * FILMES E SÉRIES:
                     * abre direto no player.
                     */
                    openPlayer(item)
                }
            }

        /*
         * BOTÃO ASSISTIR ABAIXO DO
         * PLAYER PEQUENO.
         */
        b.previewWatch.setOnClickListener {
            selectedEntry?.let {
                openPlayer(it)
            }
        }

        /*
         * MENU DA HOME
         */
        b.homeLive.setOnClickListener {
            showBrowser(ContentType.LIVE)
        }

        b.homeVod.setOnClickListener {
            showBrowser(ContentType.VOD)
        }

        b.homeSeries.setOnClickListener {
            showBrowser(ContentType.SERIES)
        }

        /*
         * CATEGORIAS
         */
        b.categoryList.layoutManager =
            LinearLayoutManager(this)

        b.categoryList.adapter =
            CategoryAdapter { category ->

                favoritesOnly =
                    category == "Favoritos"

                selectedGroup =
                    category.takeUnless {
                        it == "Tudo" ||
                        it == "Favoritos"
                    }

                /*
                 * Ao trocar categoria da TV,
                 * para o canal anterior.
                 */
                if (filter == ContentType.LIVE) {
                    selectedEntry = null
                    stopPreview()
                    b.previewTitle.text =
                        "Selecione um canal"
                    b.previewGroup.text = ""
                }

                render()

                /*
                 * Depois de escolher a categoria,
                 * leva o foco para os canais/capas.
                 */
                b.list.post {
                    b.list.requestFocus()
                }
            }

        b.activateButton.setOnClickListener {
            activate()
        }

        b.retryButton.setOnClickListener {
            loadConfig()
        }

        b.cancelButton.setOnClickListener {
            finishAffinity()
        }

        bindFilters()
        showActivation()

        if (store.token != null) {
            loadConfig()
        }
    }

    /*
     * =================================================
     * PLAYER PEQUENO
     * =================================================
     */

    private fun preparePreviewPlayerView() {

        /*
         * O primeiro componente dentro do
         * previewPanel é o FrameLayout preto
         * que já existe no XML.
         */
        val previewFrame =
            b.previewPanel.getChildAt(0)
                    as? FrameLayout
                ?: return

        previewPlayerView =
            PlayerView(this).apply {

                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )

                useController = false

                isFocusable = false
                isClickable = false
            }

        /*
         * O PlayerView fica por cima
         * do texto LPSM existente.
         */
        previewFrame.addView(
            previewPlayerView
        )
    }

    private fun playPreview(
        entry: MediaEntry
    ) {

        if (previewPlayer == null) {

            previewPlayer =
                ExoPlayer.Builder(this)
                    .build()

            previewPlayerView?.player =
                previewPlayer
        }

        previewPlayer?.apply {

            stop()
            clearMediaItems()

            setMediaItem(
                MediaItem.fromUri(
                    entry.url
                )
            )

            prepare()

            playWhenReady = true
        }
    }

    private fun stopPreview() {

        previewPlayer?.apply {
            stop()
            clearMediaItems()
        }
    }

    private fun releasePreview() {

        previewPlayerView?.player = null

        previewPlayer?.release()

        previewPlayer = null
    }

    /*
     * =================================================
     * ATIVAÇÃO
     * =================================================
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

            } catch (e: Exception) {

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
     * =================================================
     * CARREGAR CONFIGURAÇÃO
     * =================================================
     */

    private fun loadConfig() {

        showLoading(
            "Carregando configuração e aparência..."
        )

        pool.execute {

            val config =
                try {

                    api.config()

                } catch (e: Exception) {

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

                            store.token = null
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

            lastConfig = config

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

                    if (remaining == 0) {
                        break
                    }

                    val parsed =
                        api.downloadPlaylist(
                            playlist.url,
                            remaining
                        )

                    if (parsed.isEmpty()) {

                        throw IllegalStateException(
                            "lista vazia ou formato inválido"
                        )
                    }

                    all += parsed

                    if (
                        playlist.xmltvUrl
                            .isNotBlank()
                    ) {

                        try {

                            guides +=
                                XmlTvParser.current(
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

                if (all.isEmpty()) {

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

                    epg = guides

                    showContent(
                        config,
                        errors.size
                    )
                }
            }
        }
    }

    private fun applyAppearance(
        wallpaper: Bitmap?,
        banner: Bitmap?
    ) {

        b.wallpaper.setImageBitmap(
            wallpaper
        )

        b.failureBanner.setImageBitmap(
            banner
        )
    }

    /*
     * =================================================
     * TELAS DE ESTADO
     * =================================================
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
            b.activationSimple.visibility
                != View.VISIBLE
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
                        b.activationSimple
                            .visibility ==
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
                ?.joinToString(" • ") {
                    it.name
                }
                ?: "Lista indisponível"

        b.failureMessage.text =
            detail

        banner?.let {
            b.failureBanner
                .setImageBitmap(it)
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
            if (failedLists > 0) {

                "$failedLists lista(s) indisponível(is). As demais foram carregadas."

            } else {

                config.appearance
                    .supportMessage
                    .ifBlank {
                        "Somente conteúdo autorizado"
                    }
            }

        b.homeLive.requestFocus()
    }

    /*
     * =================================================
     * IMAGENS DO PAINEL
     * =================================================
     */

    private fun loadBitmap(
        url: String,
        sampleSize: Int
    ): Bitmap? {

        if (url.isBlank()) {
            return null
        }

        return try {

            val connection =
                URL(url)
                    .openConnection()
                    as HttpURLConnection

            connection.connectTimeout =
                10_000

            connection.readTimeout =
                15_000

            connection.inputStream.use {

                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options()
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
     * =================================================
     * FILTROS
     * =================================================
     */

    private fun bindFilters() {

        mapOf(
            b.all to null,
            b.live to ContentType.LIVE,
            b.vod to ContentType.VOD,
            b.series to ContentType.SERIES
        ).forEach {
                (button, type) ->

            button.setOnClickListener {

                if (type == null) {
                    showHome()
                } else {
                    showBrowser(type)
                }
            }
        }

        b.favorites
            .setOnClickListener {

                favoritesOnly = true
                selectedGroup = null

                if (
                    filter ==
                    ContentType.LIVE
                ) {
                    selectedEntry = null
                    stopPreview()
                }

                render()
            }

        b.search
            .addTextChangedListener(
                object : TextWatcher {

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
     * =================================================
     * EXIBIR CATEGORIAS E CONTEÚDO
     * =================================================
     */

    private fun render() {

        val query =
            b.search.text
                .toString()
                .trim()

        val favoriteUrls =
            store.favoriteUrls()

        val section =
            filter?.let {
                entriesByType[it]
                    .orEmpty()
            } ?: entries

        val indexedGroups =
            filter?.let {
                groupsByType[it]
                    .orEmpty()
            } ?: groupsAll

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

                indexedGroups
                    .entries
                    .sortedBy {
                        it.key
                            .lowercase()
                    }
                    .forEach {

                        add(
                            CategoryRow(
                                it.key,
                                it.value.size
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
                if (favoritesOnly) {
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

        val shown =
            if (
                !favoritesOnly &&
                query.isBlank()
            ) {

                source

            } else {

                source.filter {

                    (
                        !favoritesOnly ||
                        it.url in
                        favoriteUrls
                    ) &&
                        (
                            query.isBlank() ||
                            it.name.contains(
                                query,
                                true
                            ) ||
                            it.group.contains(
                                query,
                                true
                            )
                        )
                }
            }

        val isLive =
            filter ==
                ContentType.LIVE

        /*
         * TV AO VIVO:
         * mostra player lateral.
         *
         * FILMES/SÉRIES:
         * usa toda a área para capas.
         */
        b.previewPanel.visibility =
            if (isLive) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val manager =
            b.list.layoutManager
                as GridLayoutManager

        manager.spanCount =
            if (isLive) {

                1

            } else {

                if (
                    resources.configuration
                        .smallestScreenWidthDp >= 600
                ) {
                    4
                } else {
                    2
                }
            }

        (
            b.list.adapter
                as MediaAdapter
            )
            .submit(
                shown,
                epg
            )

        b.message.contentDescription =
            "${shown.size} itens"
    }

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
            entriesByType.mapValues {
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
     * =================================================
     * PLAYER TELA CHEIA
     * =================================================
     */

    private fun openPlayer(
        entry: MediaEntry
    ) {

        /*
         * Para o player pequeno antes
         * de entrar em tela cheia.
         */
        if (
            entry.type ==
            ContentType.LIVE
        ) {
            releasePreview()
            selectedEntry = null
        }

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
     * =================================================
     * HOME
     * =================================================
     */

    private fun showHome() {

        releasePreview()

        filter = null

        favoritesOnly = false

        selectedGroup = null

        selectedEntry = null

        b.content.visibility =
            View.GONE

        b.homePanel.visibility =
            View.VISIBLE

        b.homeLive.requestFocus()
    }

    /*
     * =================================================
     * TV / FILMES / SÉRIES
     * =================================================
     */

    private fun showBrowser(
        type: ContentType
    ) {

        releasePreview()

        filter = type

        favoritesOnly = false

        selectedGroup = null

        selectedEntry = null

        b.previewTitle.text =
            "Selecione um canal"

        b.previewGroup.text =
            ""

        b.homePanel.visibility =
            View.GONE

        b.content.visibility =
            View.VISIBLE

        render()

        b.categoryList
            .requestFocus()
    }

    /*
     * =================================================
     * CONFIGURAÇÃO DO SERVIDOR
     * =================================================
     */

    private fun showServerSetup() {

        val field =
            EditText(this).apply {

                setText(
                    store.serverUrl
                )

                hint =
                    "https://painel.seudominio.com"

                setSingleLine(true)
            }

        AlertDialog
            .Builder(this)
            .setTitle(
                "Servidor do painel"
            )
            .setMessage(
                "Informe o endereço HTTPS público do LPSM."
            )
            .setView(field)
            .setNegativeButton(
                "Cancelar",
                null
            )
            .setPositiveButton(
                "Salvar"
            ) { _, _ ->

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
                        LpsmApi(store)

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

    override fun onStop() {

        releasePreview()

        super.onStop()
    }

    override fun onDestroy() {

        activationHandler
            .removeCallbacksAndMessages(
                null
            )

        releasePreview()

        pool.shutdownNow()

        super.onDestroy()
    }
}
