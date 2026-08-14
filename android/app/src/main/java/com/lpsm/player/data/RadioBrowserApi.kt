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

    private val queries =
        listOf(
            "/json/stations/bycityexact/Vacaria" +
                "?hidebroken=true&order=clickcount&reverse=true&limit=100",
            "/json/stations/bycity/Vacaria" +
                "?hidebroken=true&order=clickcount&reverse=true&limit=100",
            "/json/stations/bycityexact/Xanxer%C3%AA" +
                "?hidebroken=true&order=clickcount&reverse=true&limit=100",
            "/json/stations/bycity/Xanxere" +
                "?hidebroken=true&order=clickcount&reverse=true&limit=100",
            "/json/stations/bycountrycodeexact/BR" +
                "?hidebroken=true&order=clickcount&reverse=true&limit=450"
        )

    private val servers =
        listOf(
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info"
        )

    fun brazilianStations(): List<MediaEntry> {
        var lastError: Throwable? = null
        val result = ArrayList<MediaEntry>()
        val seenUrls = HashSet<String>()
        val seenNames = HashSet<String>()

        fun append(stations: List<MediaEntry>) {
            for (station in stations) {
                val urlKey = station.url.trim().lowercase()
                val nameKey = station.name.normalizedRadioName()

                if (seenUrls.add(urlKey) && seenNames.add(nameKey)) {
                    result += station
                }
            }
        }

        // Estas emissoras usam os enderecos publicados pelos proprios sites.
        // Alem de garantir Vacaria no topo, isto substitui cadastros antigos do
        // catalogo publico (principalmente o da Radio Viva).
        append(vacariaAndRegionStations())

        for (server in servers) {
            var serverWorked = false

            for (query in queries) {
                try {
                    append(download("$server$query"))
                    serverWorked = true
                } catch (error: Throwable) {
                    lastError = error
                }
            }

            if (serverWorked && result.isNotEmpty()) return result
        }

        if (result.isNotEmpty()) return result

        throw IllegalStateException("Nao foi possivel carregar as radios agora.", lastError)
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
            tvgId = id,
            type = ContentType.LIVE
        )

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

            // Ha varios cadastros antigos com o nome Radio Viva. Mantemos
            // somente o stream oficial fixado acima para evitar a opcao muda.
            val normalizedName = name.normalizedRadioName()
            if (
                "radio viva" in normalizedName ||
                "momento fm" in normalizedName
            ) {
                continue
            }

            val city = station.optString("city").trim()
            val state = station.optString("state").trim()
            val group =
                when {
                    city.equals("Vacaria", ignoreCase = true) ->
                        "Vacaria e Regiao"
                    city.equals("Xanxere", ignoreCase = true) ||
                        city.equals("Xanxerê", ignoreCase = true) ->
                        "Xanxere - SC"
                    else -> state.ifBlank { city.ifBlank { "Brasil" } }
                }

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

    private fun String.normalizedRadioName(): String =
        lowercase()
            .replace("rádio", "radio")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
