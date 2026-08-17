package com.lpsm.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

import com.lpsm.player.databinding.ActivityPlayerBinding
import com.lpsm.player.LpsmApplication

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding

    private var player: ExoPlayer? = null

    private val uiHandler =
        Handler(
            Looper.getMainLooper()
        )

    private enum class ScreenMode {
        FIT,
        COMFORT,
        FILL
    }

    private var currentScreenMode =
        ScreenMode.FIT

    private val preferences by lazy {
        getSharedPreferences(
            "lpsm_player_preferences",
            MODE_PRIVATE
        )
    }

    /*
     * Considera TV / Android TV / TV Box / telas grandes
     * como aparelho de sala.
     *
     * Celular usa o modo "AMPLIAR" como padrão,
     * com um zoom leve para reduzir as barras sem
     * cortar demais a imagem.
     */
    private val tvLikeDevice: Boolean by lazy {

        val uiModeManager =
            getSystemService(
                Context.UI_MODE_SERVICE
            ) as UiModeManager

        val televisionMode =
            uiModeManager.currentModeType ==
                Configuration.UI_MODE_TYPE_TELEVISION

        val leanback =
            packageManager.hasSystemFeature(
                PackageManager.FEATURE_LEANBACK
            )

        val largeScreen =
            resources.configuration
                .smallestScreenWidthDp >=
                600

        televisionMode ||
            leanback ||
            largeScreen
    }

    private val hideTitleRunnable =
        Runnable {

            b.title.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {

                    b.title.visibility =
                        View.GONE
                }
                .start()
        }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        window.addFlags(
            WindowManager.LayoutParams
                .FLAG_KEEP_SCREEN_ON
        )

        b =
            ActivityPlayerBinding.inflate(
                layoutInflater
            )

        setContentView(
            b.root
        )

        hideSystemBars()

        b.title.text =
            intent.getStringExtra(
                "name"
            ) ?: ""

        b.title.alpha =
            1f

        b.title.visibility =
            View.VISIBLE

        /*
         * ==================================================
         * PROPORÇÃO / TAMANHO DA IMAGEM
         * ==================================================
         *
         * TV / TV Box:
         * abre em AJUSTAR, preservando a imagem completa.
         *
         * Celular:
         * abre em AMPLIAR, com zoom leve de 8%.
         * Isso reduz as barras pretas sem fazer o corte
         * forte que acontecia no modo ZOOM.
         *
         * No celular aparece um botão no canto inferior:
         *
         * TELA: AJUSTAR
         * TELA: AMPLIAR
         * TELA: PREENCHER
         */
        currentScreenMode =
            readInitialScreenMode()

        applyScreenMode(
            currentScreenMode,
            savePreference = false
        )

        b.screenModeButton.visibility =
            if (
                tvLikeDevice
            ) {
                View.GONE
            } else {
                View.VISIBLE
            }

        b.screenModeButton
            .setOnClickListener {

                cycleScreenMode()
            }

        /*
         * ==================================================
         * ESPELHAMENTO / TRANSMISSÃO PARA TV (CELULAR)
         * ==================================================
         *
         * Usa a tela de Cast/Transmitir nativa do Android.
         * Dessa forma o LPSM não força um protocolo próprio e
         * continua compatível com os recursos disponibilizados
         * pelo fabricante do celular/TV.
         */
        b.castButton.visibility =
            if (tvLikeDevice) View.GONE else View.VISIBLE

        b.castButton.setOnClickListener {
            openCastSettings()
        }

        /*
         * PLAYER
         */
        b.playerView.isFocusable =
            true

        b.playerView.isFocusableInTouchMode =
            false

        b.playerView.useController =
            true

        b.playerView.setControllerAutoShow(
            false
        )

        b.playerView.setControllerHideOnTouch(
            true
        )

        b.playerView.requestFocus()

        uiHandler.postDelayed(
            hideTitleRunnable,
            3500
        )
    }


    override fun onStart() {

        super.onStart()

        startPlayer()
    }


    private fun openCastSettings() {

        /*
         * Primeiro tenta abrir diretamente a tela "Transmitir"
         * do Android. Em firmwares que não expõem essa tela,
         * usamos as configurações sem fio como alternativa.
         */
        val castIntent =
            Intent(Settings.ACTION_CAST_SETTINGS)

        val canOpenCast =
            castIntent.resolveActivity(packageManager) != null

        val targetIntent =
            if (canOpenCast) {
                castIntent
            } else {
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            }

        runCatching {
            startActivity(targetIntent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }


    /*
     * ==================================================
     * PLAYER
     * ==================================================
     */

    private fun startPlayer() {

        val url =
            intent.getStringExtra(
                "url"
            ) ?: return

        (application as? LpsmApplication)
            ?.setNowPlaying(
                intent.getStringExtra("name") ?: "",
                url,
                intent.getStringExtra("group") ?: "",
                intent.getStringExtra("type") ?: ""
            )

        if (
            player != null
        ) {
            return
        }

        val newPlayer =
            ExoPlayer.Builder(
                this
            )
                .build()

        player =
            newPlayer

        b.playerView.player =
            newPlayer

        newPlayer.setMediaItem(
            MediaItem.fromUri(
                url
            )
        )

        newPlayer.prepare()

        newPlayer.playWhenReady =
            true
    }


    /*
     * ==================================================
     * MODOS DE TELA
     * ==================================================
     */

    private fun readInitialScreenMode():
        ScreenMode {

        /*
         * TV / TV Box:
         * imagem completa por padrão.
         */
        if (
            tvLikeDevice
        ) {
            return ScreenMode.FIT
        }

        /*
         * Celular:
         * lembra a última escolha.
         *
         * Na primeira utilização começa
         * em AMPLIAR.
         */
        return when (
            preferences.getString(
                "phone_screen_mode",
                ""
            )
        ) {

            "FIT" ->
                ScreenMode.FIT

            "FILL" ->
                ScreenMode.FILL

            "COMFORT" ->
                ScreenMode.COMFORT

            else ->
                ScreenMode.COMFORT
        }
    }


    private fun cycleScreenMode() {

        val next =
            when (
                currentScreenMode
            ) {

                ScreenMode.FIT ->
                    ScreenMode.COMFORT

                ScreenMode.COMFORT ->
                    ScreenMode.FILL

                ScreenMode.FILL ->
                    ScreenMode.FIT
            }

        applyScreenMode(
            next,
            savePreference = true
        )

        showTitleAgain()
    }


    private fun applyScreenMode(
        mode: ScreenMode,
        savePreference: Boolean
    ) {

        currentScreenMode =
            mode

        /*
         * Primeiro sempre volta a escala
         * ao tamanho normal.
         */
        b.playerView.scaleX =
            1f

        b.playerView.scaleY =
            1f

        when (
            mode
        ) {

            /*
             * Imagem inteira.
             * Pode mostrar barras se a proporção
             * do canal for diferente da tela.
             */
            ScreenMode.FIT -> {

                b.playerView.resizeMode =
                    AspectRatioFrameLayout
                        .RESIZE_MODE_FIT

                b.screenModeButton.text =
                    "TELA: AJUSTAR"
            }


            /*
             * Zoom leve.
             *
             * É o padrão no celular.
             * Amplia somente 8%, para diminuir
             * as barras sem cortar muito a imagem.
             */
            ScreenMode.COMFORT -> {

                b.playerView.resizeMode =
                    AspectRatioFrameLayout
                        .RESIZE_MODE_FIT

                b.playerView.scaleX =
                    1.08f

                b.playerView.scaleY =
                    1.08f

                b.screenModeButton.text =
                    "TELA: AMPLIAR"
            }


            /*
             * Preenche completamente a tela.
             *
             * Pode cortar uma parte das bordas,
             * por isso não é usado como padrão.
             */
            ScreenMode.FILL -> {

                b.playerView.resizeMode =
                    AspectRatioFrameLayout
                        .RESIZE_MODE_ZOOM

                b.screenModeButton.text =
                    "TELA: PREENCHER"
            }
        }

        if (
            savePreference &&
            !tvLikeDevice
        ) {

            preferences.edit()
                .putString(
                    "phone_screen_mode",
                    mode.name
                )
                .apply()
        }
    }


    /*
     * ==================================================
     * CONTROLE REMOTO / TECLADO
     * ==================================================
     */

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        when (
            event.keyCode
        ) {

            /*
             * MENU / INFO:
             *
             * Permite trocar a proporção também
             * em TV Box caso o usuário queira.
             */
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_DOWN
                ) {

                    cycleScreenMode()

                    return true
                }
            }


            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_DOWN
                ) {

                    b.playerView
                        .showController()
                }
            }


            /*
             * OK / ENTER:
             * pausa ou continua.
             */
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_DOWN
                ) {

                    togglePlayPause()

                    showTitleAgain()

                    return true
                }
            }


            KeyEvent.KEYCODE_MEDIA_PLAY -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_DOWN
                ) {

                    player?.play()

                    return true
                }
            }


            KeyEvent.KEYCODE_MEDIA_PAUSE -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_DOWN
                ) {

                    player?.pause()

                    return true
                }
            }


            KeyEvent.KEYCODE_BACK -> {

                if (
                    event.action ==
                    KeyEvent.ACTION_UP
                ) {

                    finish()
                }

                return true
            }
        }

        return super.dispatchKeyEvent(
            event
        )
    }


    private fun togglePlayPause() {

        val current =
            player ?: return

        if (
            current.isPlaying
        ) {

            current.pause()

        } else {

            current.play()
        }
    }


    /*
     * ==================================================
     * TÍTULO
     * ==================================================
     */

    private fun showTitleAgain() {

        uiHandler.removeCallbacks(
            hideTitleRunnable
        )

        b.title.animate()
            .cancel()

        b.title.visibility =
            View.VISIBLE

        b.title.alpha =
            1f

        uiHandler.postDelayed(
            hideTitleRunnable,
            3000
        )
    }


    /*
     * ==================================================
     * TELA CHEIA
     * ==================================================
     */

    private fun hideSystemBars() {

        val controller =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        controller.hide(
            WindowInsetsCompat.Type
                .systemBars()
        )
    }


    override fun onResume() {

        super.onResume()

        hideSystemBars()

        /*
         * Garante que a proporção escolhida
         * continue aplicada depois de voltar
         * de outra tela.
         */
        applyScreenMode(
            currentScreenMode,
            savePreference = false
        )

        b.playerView.requestFocus()
    }


    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(
            hasFocus
        )

        if (
            hasFocus
        ) {

            hideSystemBars()
        }
    }


    override fun onStop() {

        uiHandler.removeCallbacks(
            hideTitleRunnable
        )

        b.playerView.player =
            null

        player?.release()

        player =
            null

        super.onStop()
    }


    override fun onDestroy() {

        uiHandler.removeCallbacksAndMessages(
            null
        )

        super.onDestroy()
    }
}
