package com.lpsm.player.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifica atualizacoes sem bloquear a abertura do player.
 *
 * A verificacao pertence ao processo do aplicativo, e nao a tela de abertura.
 * Assim, uma TV Box lenta continua recebendo o aviso mesmo quando a resposta
 * do GitHub demora mais que a animacao inicial.
 */
object AppUpdateChecker {

    private const val UPDATE_JSON_URL =
        "https://github.com/tvonbox3-byte/lpsm-player-m3u/releases/latest/download/update.json"

    private val executor = Executors.newSingleThreadExecutor()
    private val checking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var deliveredVersionCode = 0L

    fun markDelivered(versionCode: Long) {
        if (versionCode > deliveredVersionCode) {
            deliveredVersionCode = versionCode
        }
    }

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val force: Boolean,
        val message: String
    )

    fun check(context: Context, onAvailable: (UpdateInfo) -> Unit) {
        if (!checking.compareAndSet(false, true)) return

        val appContext = context.applicationContext

        executor.execute {
            try {
                val json = JSONObject(downloadText(UPDATE_JSON_URL))
                val info =
                    UpdateInfo(
                        versionCode = json.optLong("versionCode", 0L),
                        versionName = json.optString("versionName", ""),
                        apkUrl = json.optString("apkUrl", ""),
                        sha256 = json.optString("sha256", ""),
                        force = json.optBoolean("force", false),
                        message =
                            json.optString(
                                "message",
                                "Uma nova versao do LPSM esta disponivel."
                            )
                    )

                val installed = currentVersionCode(appContext)
                if (
                    info.versionCode > installed &&
                    info.apkUrl.isNotBlank() &&
                    deliveredVersionCode != info.versionCode
                ) {
                    deliveredVersionCode = info.versionCode
                    mainHandler.post { onAvailable(info) }
                }
            } catch (_: Throwable) {
                // Atualizacao nunca pode impedir o uso do player.
            } finally {
                checking.set(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun downloadText(address: String): String {
        val connection = openConnection(address)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(address: String): HttpURLConnection {
        var current = address

        repeat(8) {
            val connection =
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "LPSM-Android-Updater/2.2.24")
                    setRequestProperty("Accept", "application/json,*/*")
                    setRequestProperty("Cache-Control", "no-cache")
                }

            val status = connection.responseCode
            if (status in 300..399) {
                val location =
                    connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirecionamento sem endereco")
                current = URL(URL(current), location).toString()
                connection.disconnect()
            } else {
                if (status !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("Servidor respondeu HTTP $status")
                }
                return connection
            }
        }

        throw IllegalStateException("Muitos redirecionamentos")
    }
}
