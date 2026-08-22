package com.lpsm.player.data

import android.content.Context
import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Catalogo independente de radios brasileiras.
 *
 * 2.2.22:
 * - uma unica consulta nacional por tentativa (antes eram varias consultas sequenciais)
 * - timeouts curtos com failover de servidor
 * - cache local por 24h, deixando as proximas aberturas praticamente imediatas
 * - catalogo maior, incluindo automaticamente emissoras AM cadastradas no Radio Browser
 * - emissoras de Vacaria/regiao continuam fixadas no topo como fallback instantaneo
 */
object RadioBrowserApi {

    private const val PREFS = "lpsm_radio_cache"
    private const val KEY_JSON = "stations_json"
    private const val KEY_TIME = "stations_time"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    // 350 emissoras preservam variedade sem sobrecarregar boxes com pouca memoria.
    private const val BRAZIL_QUERY =
        "/json/stations/bycountrycodeexact/BR" +
            "?hidebroken=true&order=clickcount&reverse=true&limit=350"

    private val servers =
        listOf(
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info"
        )

    fun brazilianStations(context: Context): List<MediaEntry> {
        val immediate = initialStations(context)

        if (!needsRefresh(context)) return immediate

        return try {
            refreshBrazilianStations(context)
        } catch (error: Throwable) {
            if (immediate.isNotEmpty()) immediate else throw error
        }
    }

    fun featuredStations(): List<MediaEntry> =
        vacariaAndRegionStations()

    /** Entrega inclusive cache vencido; a renovacao acontece em segundo plano. */
    fun initialStations(context: Context): List<MediaEntry> =
        merge(
            vacariaAndRegionStations(),
            readCache(context, allowExpired = true).orEmpty()
        )

    fun needsRefresh(context: Context): Boolean {
        val savedAt =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_TIME, 0L)
        return savedAt <= 0L ||
            System.currentTimeMillis() - savedAt > CACHE_TTL_MS
    }

    fun refreshBrazilianStations(context: Context): List<MediaEntry> {
        val fixed = vacariaAndRegionStations()

        var lastError: Throwable? = null

        for (server in servers) {
            try {
                val downloaded = download("$server$BRAZIL_QUERY")
                if (downloaded.isNotEmpty()) {
                    writeCache(context, downloaded)
                    return merge(fixed, downloaded)
                }
            } catch (error: Throwable) {
                lastError = error
            }
        }

        // Mesmo sem internet/API, as radios locais conhecidas aparecem de imediato.
        if (fixed.isNotEmpty()) return fixed

        throw IllegalStateException("Nao foi possivel carregar as radios agora.", lastError)
    }

    private fun merge(
        first: List<MediaEntry>,
        second: List<MediaEntry>
    ): List<MediaEntry> {
        val result = ArrayList<MediaEntry>(first.size + second.size)
        val seenUrls = HashSet<String>()
        val seenNames = HashSet<String>()

        fun append(items: List<MediaEntry>) {
            for (station in items) {
                val urlKey = station.url.trim().lowercase()
                val nameKey = station.name.normalizedRadioName()
                if (urlKey.isNotBlank() && seenUrls.add(urlKey) && seenNames.add(nameKey)) {
                    result += station
                }
            }
        }

        append(first)
        append(second)
        return result
    }

    private fun vacariaAndRegionStations(): List<MediaEntry> =
        listOf(
            localStation(
                name = "Radio Esmeralda 96.5 FM - Vacaria",
                url = "https://stm7.conectastreaming.com:8476/stream",
                id = "lpsm-vacaria-esmeralda-965"
            ),
            localStation(
                name = "Radio 93.1 FM - Vacaria",
                url = "https://stm4.conectastreaming.com:6708/stream",
                id = "lpsm-vacaria-931"
            ),
            localStation(
                name = "Maisnova 101.5 FM - Vacaria",
                url = "https://painel.sintonizar.tv.br/stream/mnvacaria",
                id = "lpsm-vacaria-maisnova-1015"
            ),
            localStation(
                name = "Tua Radio Fatima 90.5 FM - Vacaria",
                url = "https://painel.sintonizar.tv.br/stream/fatima",
                id = "lpsm-vacaria-fatima-905"
            ),
            localStation(
                name = "Radio Viva 94.5 FM - Serra Gaucha",
                url = "https://8547.brasilstream.com.br/stream?origem=siteviva",
                id = "lpsm-serra-viva-945"
            ),
            localStation(
                name = "Momento FM 97.9 - Xanxere",
                url = "https://stm10.virtualcast.com.br:8318/momentoxanxere",
                id = "lpsm-xanxere-momento-979",
                group = "Xanxere - SC"
            )
        )

    private fun localStation(
        name: String,
        url: String,
        id: String,
        group: String = "Vacaria e Regiao"
    ) =
        MediaEntry(
            name = name,
            url = url,
            logo = "",
            group = group,
            tvgId = "radio:$id",
            type = ContentType.LIVE
        )

    private fun download(address: String): List<MediaEntry> {
        val connection =
            (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_500
                readTimeout = 5_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                /*
                 * Nao forcar gzip aqui. Em algumas TV Boxes o
                 * HttpURLConnection entregava os bytes compactados sem
                 * descompactar e o JSON falhava, deixando apenas as seis
                 * radios fixas.
                 */
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "LPSM-Player/Android")
            }

        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Servidor de radios respondeu $status")
            }

            val body =
                connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): List<MediaEntry> {
        val array = JSONArray(body)
        val result = ArrayList<MediaEntry>(array.length())
        val seen = HashSet<String>()

        for (index in 0 until array.length()) {
            val station = array.optJSONObject(index) ?: continue
            if (station.optInt("lastcheckok", 0) != 1) continue

            val streamUrl =
                station.optString("url_resolved")
                    .ifBlank { station.optString("url") }
                    .trim()

            if (
                streamUrl.isBlank() ||
                !(streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) ||
                !seen.add(streamUrl.lowercase())
            ) continue

            val name = station.optString("name").trim().ifBlank { "Radio sem nome" }
            val normalizedName = name.normalizedRadioName()

            // Evita duplicatas quebradas das emissoras que ja estao fixadas acima.
            if ("radio viva" in normalizedName || "momento fm" in normalizedName) continue

            val city = station.optString("city").trim()
            val state = station.optString("state").trim()
            val tags = station.optString("tags").lowercase()

            val looksAm =
                Regex("(?i)(^|\\s)AM(\\s|$)").containsMatchIn(name) ||
                    Regex("(?i)\\b\\d{3,4}\\s*k?hz\\b").containsMatchIn(name) ||
                    tags.split(',').any { it.trim() in setOf("am", "am radio", "radio am") }

            val group =
                when {
                    city.equals("Vacaria", ignoreCase = true) -> "Vacaria e Regiao"
                    city.equals("Xanxere", ignoreCase = true) ||
                        city.equals("Xanxerê", ignoreCase = true) -> "Xanxere - SC"
                    looksAm -> "Radios AM - Brasil"
                    else -> state.ifBlank { city.ifBlank { "Brasil" } }
                }

            result +=
                MediaEntry(
                    name = name,
                    url = streamUrl,
                    logo = station.optString("favicon").trim(),
                    group = group,
                    tvgId = "radio:${station.optString("stationuuid").trim()}",
                    type = ContentType.LIVE
                )
        }

        return result
    }

    private fun writeCache(context: Context, stations: List<MediaEntry>) {
        try {
            val array = JSONArray()
            stations.forEach { item ->
                array.put(
                    JSONObject()
                        .put("name", item.name)
                        .put("url", item.url)
                        .put("logo", item.logo)
                        .put("group", item.group)
                        .put("tvgId", item.tvgId)
                )
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, array.toString())
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
            // Cache nunca pode impedir a secao Radios de funcionar.
        }
    }

    private fun readCache(
        context: Context,
        allowExpired: Boolean = false
    ): List<MediaEntry>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedAt = prefs.getLong(KEY_TIME, 0L)
            val body = prefs.getString(KEY_JSON, null) ?: return null
            if (
                savedAt <= 0L ||
                (!allowExpired && System.currentTimeMillis() - savedAt > CACHE_TTL_MS)
            ) return null

            val array = JSONArray(body)
            val result = ArrayList<MediaEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                result +=
                    MediaEntry(
                        name = item.optString("name").trim().ifBlank { "Radio sem nome" },
                        url = url,
                        logo = item.optString("logo").trim(),
                        group = item.optString("group").trim().ifBlank { "Brasil" },
                        tvgId = item.optString("tvgId").trim()
                            .removePrefix("radio:")
                            .let { "radio:$it" },
                        type = ContentType.LIVE
                    )
            }
            result.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun String.normalizedRadioName(): String =
        lowercase()
            .replace("rádio", "radio")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
