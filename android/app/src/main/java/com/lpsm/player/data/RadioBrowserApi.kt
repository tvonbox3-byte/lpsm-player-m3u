package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Catalogo independente de radios brasileiras.
 *
 * A consulta e feita somente quando a pessoa abre a secao Radios. Assim a
 * inicializacao do LPSM e o carregamento das listas M3U continuam leves.
 */
object RadioBrowserApi {

    private const val QUERY =
        "/json/stations/bycountrycodeexact/BR" +
            "?hidebroken=true&order=clickcount&reverse=true&limit=450"

    private val servers =
        listOf(
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info"
        )

    fun brazilianStations(): List<MediaEntry> {
        var lastError: Throwable? = null

        for (server in servers) {
            try {
                val stations = download("$server$QUERY")
                if (stations.isNotEmpty()) return stations
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IllegalStateException(
            "Nao foi possivel carregar as radios agora.",
            lastError
        )
    }

    private fun download(address: String): List<MediaEntry> {
        val connection =
            (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
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
                !(streamUrl.startsWith("https://") ||
                    streamUrl.startsWith("http://")) ||
                !seen.add(streamUrl.lowercase())
            ) {
                continue
            }

            val name =
                station.optString("name")
                    .trim()
                    .ifBlank { "Radio sem nome" }

            val state = station.optString("state").trim()
            val group = state.ifBlank { "Brasil" }

            result +=
                MediaEntry(
                    name = name,
                    url = streamUrl,
                    logo = station.optString("favicon").trim(),
                    group = group,
                    tvgId = station.optString("stationuuid").trim(),
                    type = ContentType.LIVE
                )
        }

        return result
    }
}
