package com.lpsm.player.ui

import android.os.Bundle
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

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding

    private var player: ExoPlayer? = null

    private val uiHandler =
        Handler(Looper.getMainLooper())

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

        /*
         * Permite usar toda a área
         * disponível da tela.
         */
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

        /*
         * TELA CHEIA IMERSIVA
         */
        hideSystemBars()

        /*
         * Título do canal/filme/episódio.
         */
        b.title.text =
            intent.getStringExtra(
                "name"
            ) ?: ""

        b.title.alpha =
            1f

        b.title.visibility =
            View.VISIBLE

        /*
         * PROPORÇÃO AUTOMÁTICA:
         *
         * FIT preserva a imagem completa do canal,
         * filme ou episódio em celular, TV, TV Box
         * e emulador. Assim logotipos e informações
         * que ficam nas bordas não são cortados.
         *
         * Quando o formato do vídeo é diferente do
         * formato da tela, podem aparecer pequenas
         * barras pretas. Isso é intencional para não
         * perder nenhuma parte da imagem.
         */
        b.playerView.resizeMode =
            AspectRatioFrameLayout
                .RESIZE_MODE_FIT

        /*
         * CONTROLE REMOTO
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

        /*
         * O nome aparece alguns segundos
         * e depois some para não atrapalhar.
         */
        uiHandler.postDelayed(
            hideTitleRunnable,
            3500
        )
    }

    override fun onStart() {
        super.onStart()

        startPlayer()
    }

    private fun startPlayer() {

        val url =
            intent.getStringExtra(
                "url"
            ) ?: return

        if (
            player != null
        ) {
            return
        }

        val newPlayer =
            ExoPlayer.Builder(this)
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
     * CONTROLE REMOTO / TECLADO
     * ==================================================
     */

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {

        /*
         * Deixamos o PlayerView receber
         * setas e teclas de mídia.
         */
        when (event.keyCode) {

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

            /*
             * Botão VOLTAR do controle.
             */
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
     * Mostra novamente o nome
     * quando o usuário interage.
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

        b.playerView.requestFocus()
    }

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {
        super.onWindowFocusChanged(
            hasFocus
        )

        if (hasFocus) {
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
