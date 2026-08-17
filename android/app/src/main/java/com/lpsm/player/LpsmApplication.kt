package com.lpsm.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lpsm.player.data.LpsmApi
import com.lpsm.player.data.SecureStore
import java.util.concurrent.Executors

/**
 * Mantém o painel informado enquanto qualquer tela do LPSM está visível,
 * inclusive quando o cliente entra no player de vídeo.
 */
class LpsmApplication :
    Application(),
    Application.ActivityLifecycleCallbacks {

    private val handler =
        Handler(Looper.getMainLooper())

    private val executor =
        Executors.newSingleThreadExecutor()

    private lateinit var store:
        SecureStore

    private lateinit var api:
        LpsmApi

    private var visibleActivities =
        0

    private var heartbeatRunning =
        false

    private val heartbeat =
        object : Runnable {

            override fun run() {

                if (
                    !heartbeatRunning ||
                    visibleActivities <= 0
                ) {
                    return
                }

                if (
                    !store.token.isNullOrBlank()
                ) {

                    executor.execute {
                        try {
                            api.heartbeat(
                                nowPlayingName,
                                nowPlayingUrl,
                                nowPlayingGroup,
                                nowPlayingType
                            )
                        } catch (_: Exception) {
                            // A presença é informativa e nunca pode fechar o app.
                        }
                    }
                }

                handler.postDelayed(
                    this,
                    HEARTBEAT_INTERVAL_MS
                )
            }
        }


    @Volatile
    var nowPlayingName: String = ""
        private set

    @Volatile
    var nowPlayingUrl: String = ""
        private set

    @Volatile
    var nowPlayingGroup: String = ""
        private set

    @Volatile
    var nowPlayingType: String = ""
        private set

    fun setNowPlaying(
        name: String,
        url: String,
        group: String = "",
        type: String = ""
    ) {
        nowPlayingName = name.take(180)
        nowPlayingUrl = url.take(4096)
        nowPlayingGroup = group.take(180)
        nowPlayingType = type.take(32)
    }

    fun clearNowPlaying() {
        nowPlayingName = ""
        nowPlayingUrl = ""
        nowPlayingGroup = ""
        nowPlayingType = ""
    }

    override fun onCreate() {
        super.onCreate()

        store =
            SecureStore(this)

        api =
            LpsmApi(store)

        registerActivityLifecycleCallbacks(
            this
        )
    }

    override fun onActivityStarted(
        activity: Activity
    ) {

        visibleActivities += 1

        if (
            visibleActivities == 1
        ) {
            heartbeatRunning = true

            handler.removeCallbacks(
                heartbeat
            )

            handler.post(
                heartbeat
            )
        }
    }

    override fun onActivityStopped(
        activity: Activity
    ) {

        visibleActivities =
            (visibleActivities - 1)
                .coerceAtLeast(0)

        if (
            visibleActivities == 0
        ) {
            heartbeatRunning = false

            handler.removeCallbacks(
                heartbeat
            )
        }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
    ) = Unit

    override fun onActivityResumed(
        activity: Activity
    ) = Unit

    override fun onActivityPaused(
        activity: Activity
    ) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle
    ) = Unit

    override fun onActivityDestroyed(
        activity: Activity
    ) = Unit

    companion object {
        private const val HEARTBEAT_INTERVAL_MS =
            10_000L
    }
}
