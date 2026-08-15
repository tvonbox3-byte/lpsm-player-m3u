package com.lpsm.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

class SecureStore(context: Context) {
    private val appContext = context.applicationContext

    // O identificador da instalacao nao e um segredo. Mantê-lo separado evita que uma
    // falha do Android Keystore em emuladores impeça o aplicativo de abrir.
    private val identity: SharedPreferences =
        appContext.getSharedPreferences("lpsm_install_identity", Context.MODE_PRIVATE)

    private val prefs: SharedPreferences = createSecurePreferences()

    private fun createSecurePreferences(): SharedPreferences {
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
        prefs.edit().putStringSet("favorites", favorites).apply()
        return added
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
