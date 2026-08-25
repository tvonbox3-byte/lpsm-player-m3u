package com.lpsm.player.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class SecureStore(context: Context) {
    private val appContext = context.applicationContext

    // O identificador da instalacao nao e um segredo. Mantê-lo separado evita que uma
    // falha do Android Keystore em emuladores impeça o aplicativo de abrir.
    private val identity: SharedPreferences =
        appContext.getSharedPreferences("lpsm_install_identity", Context.MODE_PRIVATE)

    private val prefs: SharedPreferences = createSecurePreferences()

    private fun createSecurePreferences(): SharedPreferences {
        /*
         * O Android Keystore usado pelo EncryptedSharedPreferences exige API
         * 23. Em Android 5.0/5.1 usamos o armazenamento privado do próprio
         * aplicativo para manter o APK universal sem impedir a abertura.
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.M
        ) {
            return appContext.getSharedPreferences(
                "lpsm_secure_fallback",
                Context.MODE_PRIVATE
            )
        }

        fun encrypted(): SharedPreferences {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                "lpsm_secure_mac",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        return try {
            encrypted()
        } catch (_: Exception) {
            // Uma atualizacao/restauracao do emulador pode deixar o arquivo cifrado
            // incompatível com a chave. Recriamos antes de usar o fallback local.
            appContext.deleteSharedPreferences("lpsm_secure_mac")
            try {
                encrypted()
            } catch (_: Exception) {
                appContext.getSharedPreferences("lpsm_secure_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().putString("token", value).apply()

    var serverUrl: String
        get() = prefs.getString("server", null) ?: com.lpsm.player.BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString("server", value.trimEnd('/')).apply()

    fun saveDeviceConfig(json: String) {
        prefs.edit()
            .putString("device_config_json", json)
            .putLong("device_config_saved_at", System.currentTimeMillis())
            .apply()
    }

    fun cachedDeviceConfig(
        maxAgeMillis: Long = 7L * 24L * 60L * 60L * 1000L
    ): String? {
        val savedAt = prefs.getLong("device_config_saved_at", 0L)
        if (
            savedAt <= 0L ||
            System.currentTimeMillis() - savedAt > maxAgeMillis
        ) {
            return null
        }
        return prefs.getString("device_config_json", null)
            ?.takeIf { it.isNotBlank() }
    }

    val installMac: String
        get() {
            identity.getString("install_mac", null)?.let { return it }
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            bytes[0] = ((bytes[0].toInt() or 2) and 254).toByte()
            if (bytes.none { byte -> "%02X".format(byte).any { it in 'A'..'F' } }) {
                bytes[5] = 0xAF.toByte()
            }
            val value = bytes.joinToString(":") { "%02X".format(it) }
            identity.edit().putString("install_mac", value).commit()
            return value
        }

    fun isFavorite(url: String) = prefs.getStringSet("favorites", emptySet())!!.contains(url)

    fun favoriteUrls(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()

    fun toggleFavorite(url: String): Boolean {
        val favorites = prefs.getStringSet("favorites", emptySet())!!.toMutableSet()
        val added = if (favorites.contains(url)) {
            favorites.remove(url)
            false
        } else {
            favorites.add(url)
            true
        }
        // commit() garante que o favorito esteja persistido antes de a TV Box
        // processar o proximo evento do controle remoto. E uma escrita pequena
        // e evita estados intermediarios em firmwares Android TV mais antigos.
        prefs.edit().putStringSet("favorites", favorites).commit()
        return added
    }

    data class PlaybackProgress(
        val url: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long
    )

    fun playbackPosition(url: String): Long =
        playbackHistory()
            .firstOrNull { it.url == url }
            ?.positionMs
            ?: 0L

    fun continueWatchingUrls(): List<String> =
        playbackHistory()
            .sortedByDescending { it.updatedAt }
            .map { it.url }

    /**
     * Salva somente filmes e episodios realmente iniciados. Ao chegar perto
     * do fim, o item sai de "Continuar assistindo" automaticamente.
     */
    fun savePlaybackProgress(
        url: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (url.isBlank()) return

        val history =
            playbackHistory()
                .filterNot { it.url == url }
                .toMutableList()

        val completed =
            durationMs > 0L &&
                positionMs >= durationMs * 95L / 100L

        if (positionMs >= 30_000L && !completed) {
            history.add(
                PlaybackProgress(
                    url = url,
                    positionMs = positionMs,
                    durationMs = durationMs.coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        val json = JSONArray()
        history
            .sortedByDescending { it.updatedAt }
            .take(MAX_PLAYBACK_HISTORY)
            .forEach { item ->
                json.put(
                    JSONObject()
                        .put("url", item.url)
                        .put("positionMs", item.positionMs)
                        .put("durationMs", item.durationMs)
                        .put("updatedAt", item.updatedAt)
                )
            }

        prefs.edit()
            .putString(PLAYBACK_HISTORY_KEY, json.toString())
            .commit()
    }

    private fun playbackHistory(): List<PlaybackProgress> {
        val raw = prefs.getString(PLAYBACK_HISTORY_KEY, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    val positionMs = item.optLong("positionMs", 0L)
                    if (url.isBlank() || positionMs < 30_000L) continue
                    add(
                        PlaybackProgress(
                            url = url,
                            positionMs = positionMs,
                            durationMs = item.optLong("durationMs", 0L),
                            updatedAt = item.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PLAYBACK_HISTORY_KEY = "playback_history_json"
        private const val MAX_PLAYBACK_HISTORY = 50
    }
}
