package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import java.io.Reader
import java.net.URL

object M3uParser {

    /*
     * Alguns servidores usam aspas simples ou deixam o valor sem aspas.
     * Aceitar os tres formatos evita perder principalmente tvg-logo e
     * group-title em filmes e series.
     */
    private val attributePattern =
        Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

    /*
     * Exemplos reconhecidos:
     *
     * S01E01
     * S1E2
     * T01E01
     * 1x01
     * Temporada 1 Episodio 2
     * Season 1 Episode 2
     */
    private val seasonEpisodePatterns =
        listOf(
            Regex(
                """(?i)\bS(\d{1,2})\s*[-._ ]*EP?\s*\.?\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\bT(\d{1,2})\s*[-._ ]*EP?\s*\.?\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(\d{1,2})\s*[xX]\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(?:temporada|season)\s*(\d{1,2}).*?\b(?:epis[oó]dio|episode|ep|cap(?:[ií]tulo)?)\s*[.\-:# ]*\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b[ST](\d{1,2})\s*[-._ ]+\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(\d{1,2})\s*[ªº]\s*(?:temporada|season).*?\b(?:epis[oó]dio|episode|ep)?\s*\.?\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b[ST](\d{1,2})\s*[.:#\-]\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(?:temporada|season)\s*(\d{1,2})\s*[-._:# ]+\s*(\d{1,3})\b"""
            )
        )

    private val reversedSeasonEpisodePattern =
        Regex(
            """(?i)\b(?:E|EP|epis[oó]dio|episode)\s*\.?\s*(\d{1,3})\s*[-._ ]*\s*(?:S|T|temporada|season)\s*\.?\s*(\d{1,2})\b"""
        )

    /*
     * Para listas que informam apenas:
     * EP 01
     * Episodio 01
     */
    private val episodeOnlyPattern =
        Regex(
            """(?i)\b(?:ep|epis[oó]dio|episode|cap(?:[ií]tulo)?)\s*\.?\s*(\d{1,3})\b"""
        )

    private data class EpisodeInfo(
        val seriesName: String,
        val season: Int?,
        val episode: Int?
    )

    fun parse(
        reader: Reader,
        limit: Int = 60_000,
        onPartial: ((List<MediaEntry>) -> Unit)? = null
    ): List<MediaEntry> {

        /*
         * Listas grandes costumam vir na ordem TV, filmes e, por ultimo,
         * series. Parar simplesmente no limite fazia as categorias finais
         * desaparecerem. Mantemos o mesmo teto de memoria, mas reservamos
         * espaco para os tres tipos e continuamos examinando o arquivo.
         */
        val buckets =
            linkedMapOf(
                ContentType.LIVE to ArrayList<MediaEntry>(),
                ContentType.VOD to ArrayList<MediaEntry>(),
                ContentType.SERIES to ArrayList<MediaEntry>()
            )

        val reserved =
            mapOf(
                ContentType.LIVE to (limit * 12 / 100),
                ContentType.VOD to (limit * 45 / 100),
                ContentType.SERIES to (limit * 43 / 100)
            )

        var kept = 0

        /*
         * BUILD 41 - carregamento progressivo.
         * Algumas TV Boxes levam muito tempo para percorrer listas enormes.
         * Liberamos uma primeira amostra rapidamente e continuamos lendo o
         * restante sem deixar TV/Filmes/Séries vazios durante todo o processo.
         */
        var firstPartialEmitted = false
        val readyTypePartials = mutableSetOf<ContentType>()
        /*
         * Uma unica entrega inicial basta para liberar a navegacao. Recriar
         * todos os indices em 800, 4 mil e 12 mil itens disputava CPU com o
         * parser e deixava TV Boxes mais lentas justamente ao abrir.
         */
        fun emitPartialIfNeeded(changedType: ContentType) {
            val callback = onPartial ?: return

            val firstReady =
                !firstPartialEmitted && kept >= 1_500

            val sectionReady =
                changedType != ContentType.LIVE &&
                    changedType !in readyTypePartials &&
                    buckets.getValue(changedType).size >= 200

            if (!firstReady && !sectionReady) return

            val snapshot = buildList {
                addAll(buckets.getValue(ContentType.LIVE))
                addAll(buckets.getValue(ContentType.VOD))
                addAll(buckets.getValue(ContentType.SERIES))
            }
            callback(snapshot)

            if (firstReady) firstPartialEmitted = true
            if (sectionReady) readyTypePartials += changedType
        }

        fun keep(entry: MediaEntry) {
            if (limit <= 0) return

            val target = buckets.getValue(entry.type)

            if (kept < limit) {
                target += entry
                kept += 1
                emitPartialIfNeeded(entry.type)
                return
            }

            val targetReserve = reserved.getValue(entry.type)
            if (target.size >= targetReserve) return

            val victim =
                buckets.entries
                    .filter {
                        it.key != entry.type &&
                            it.value.size > reserved.getValue(it.key)
                    }
                    .maxByOrNull {
                        it.value.size - reserved.getValue(it.key)
                    }
                    ?: return

            victim.value.removeAt(victim.value.lastIndex)
            target += entry
            emitPartialIfNeeded(entry.type)
        }

        var metadata = ""

        reader
            .buffered(64 * 1024)
            .useLines { lines ->

                for (raw in lines) {

                    val line =
                        raw.trim()

                    when {

                        line.startsWith(
                            "#EXTINF",
                            true
                        ) -> {
                            metadata = line
                        }

                        line.isNotEmpty() &&
                            !line.startsWith("#") &&
                            metadata.isNotEmpty() -> {

                            val attributes =
                                attributePattern
                                    .findAll(metadata)
                                    .associate { match ->
                                        val value =
                                            match.groupValues
                                                .drop(2)
                                                .firstOrNull { it.isNotEmpty() }
                                                .orEmpty()

                                        match.groupValues[1].lowercase() to value
                                    }

                            val name =
                                extractDisplayName(
                                    metadata
                                )
                                    .trim()
                                    .ifBlank {
                                        "Sem nome"
                                    }

                            val group =
                                attributes[
                                    "group-title"
                                ]
                                    ?.normalizeGroupName()
                                    ?.ifBlank {
                                        "Outros"
                                    }
                                    ?: "Outros"

                            val logo =
                                listOf(
                                    "tvg-logo",
                                    "movie-logo",
                                    "series-logo",
                                    "cover",
                                    "cover-big",
                                    "cover_big",
                                    "poster",
                                    "poster-path",
                                    "poster_path",
                                    "movie-image",
                                    "movie_image",
                                    "stream-icon",
                                    "stream_icon",
                                    "backdrop-path",
                                    "backdrop_path",
                                    "icon",
                                    "logo"
                                )
                                    .firstNotNullOfOrNull { key ->
                                        attributes[key]
                                            ?.takeIf { it.isNotBlank() }
                                    }
                                    .orEmpty()
                                    .normalizeArtworkUrl(line)

                            val tvgId =
                                attributes[
                                    "tvg-id"
                                ]
                                    ?.trim()
                                    ?: ""

                            val description =
                                listOf(
                                    "description",
                                    "tvg-description",
                                    "plot",
                                    "overview",
                                    "synopsis",
                                    "storyline",
                                    "short-description",
                                    "short_description",
                                    "desc"
                                )
                                    .firstNotNullOfOrNull { key ->
                                        attributes[key]
                                            ?.trim()
                                            ?.takeIf { it.isNotBlank() }
                                    }
                                    .orEmpty()
                                    .replace("&quot;", "\"")
                                    .replace("&amp;", "&")

                            val declaredSeriesName =
                                listOf(
                                    "series-name",
                                    "series-title",
                                    "show-title",
                                    "show_name",
                                    "show-name"
                                )
                                    .firstNotNullOfOrNull { key ->
                                        attributes[key]
                                            ?.trim()
                                            ?.takeIf { it.isNotBlank() }
                                    }
                                    .orEmpty()

                            val parsedEpisodeInfo =
                                parseEpisodeInfo(name)

                            val episodeInfo =
                                parsedEpisodeInfo.copy(
                                    seriesName =
                                        declaredSeriesName.ifBlank {
                                            parsedEpisodeInfo.seriesName
                                        },
                                    season =
                                        firstNumberAttribute(
                                            attributes,
                                            "season",
                                            "season-number",
                                            "season_number",
                                            "season-num",
                                            "season_num"
                                        ) ?: parsedEpisodeInfo.season,
                                    episode =
                                        firstNumberAttribute(
                                            attributes,
                                            "episode",
                                            "episode-number",
                                            "episode_number",
                                            "episode-num",
                                            "episode_num"
                                        ) ?: parsedEpisodeInfo.episode
                                )

                            val type =
                                detectType(
                                    name = name,
                                    group = group,
                                    url = line,
                                    episodeInfo = episodeInfo
                                )

                            val trailerUrl =
                                listOf(
                                    "trailer",
                                    "trailer-url",
                                    "trailer_url",
                                    "tvg-trailer",
                                    "youtube-trailer",
                                    "youtube_trailer"
                                )
                                    .firstNotNullOfOrNull { key ->
                                        attributes[key]
                                            ?.trim()
                                            ?.takeIf { it.isNotBlank() }
                                    }
                                    .orEmpty()
                                    .normalizeTrailerUrl(line)

                            val finalSeriesName =
                                if (
                                    type ==
                                    ContentType.SERIES
                                ) {

                                    declaredSeriesName
                                        .ifBlank {
                                            episodeInfo
                                                .seriesName
                                                .ifBlank {
                                                    cleanSeriesName(name)
                                                }
                                        }

                                } else {
                                    ""
                                }

                            keep(
                                MediaEntry(
                                    name = name,
                                    url = line,
                                    logo = logo,
                                    group = group,
                                    tvgId = tvgId,
                                    description = description,
                                    trailerUrl = trailerUrl,
                                    type = type,

                                    seriesName =
                                        finalSeriesName,

                                    season =
                                        if (
                                            type ==
                                            ContentType.SERIES
                                        ) {
                                            episodeInfo
                                                .season
                                        } else {
                                            null
                                        },

                                    episode =
                                        if (
                                            type ==
                                            ContentType.SERIES
                                        ) {
                                            episodeInfo
                                                .episode
                                        } else {
                                            null
                                        }
                                )
                            )

                            metadata = ""
                        }
                    }
                }
            }

        val keptEntries =
            buildList {
                addAll(buckets.getValue(ContentType.LIVE))
                addAll(buckets.getValue(ContentType.VOD))
                addAll(buckets.getValue(ContentType.SERIES))
            }

        return normalizeSeriesGroups(keptEntries)
    }

    /*
     * Alguns fornecedores marcam apenas parte dos episodios de uma mesma
     * categoria. Quando pelo menos um terco do grupo tem sinal claro de
     * serie e nao ha URL de TV ao vivo, aplicamos o tipo ao grupo inteiro.
     */
    private fun normalizeSeriesGroups(
        source: List<MediaEntry>
    ): List<MediaEntry> {
        val promotedGroups =
            source
                .groupBy { it.group.trim().lowercase() }
                .filterValues { items ->
                    val seriesCount =
                        items.count { it.type == ContentType.SERIES }

                    val hasLiveStream =
                        items.any {
                            val path =
                                it.url.lowercase()
                                    .substringBefore('?')
                                    .substringBefore('#')

                            "/live/" in path || path.endsWith(".ts")
                        }

                    val clearlyLiveGroup =
                        items.any {
                            isClearlyLiveGroup(it.group.lowercase())
                        }

                    !hasLiveStream &&
                        !clearlyLiveGroup &&
                        seriesCount > 0 &&
                        (
                            items.size <= 4 ||
                                seriesCount * 3 >= items.size
                            )
                }
                .keys

        if (promotedGroups.isEmpty()) return source

        return source.map { entry ->
            if (
                entry.group.trim().lowercase() !in promotedGroups ||
                entry.type == ContentType.SERIES
            ) {
                entry
            } else {
                val episodeInfo = parseEpisodeInfo(entry.name)

                entry.copy(
                    type = ContentType.SERIES,
                    seriesName =
                        episodeInfo.seriesName.ifBlank {
                            cleanSeriesName(entry.name)
                        },
                    season = episodeInfo.season,
                    episode = episodeInfo.episode
                )
            }
        }
    }

    private fun detectType(
        name: String,
        group: String,
        url: String,
        episodeInfo: EpisodeInfo
    ): ContentType {

        val lowerUrl =
            url.lowercase()

        val path =
            lowerUrl
                .substringBefore('?')
                .substringBefore('#')

        val lowerGroup =
            group.lowercase()

        /*
         * PRIMEIRO usamos características
         * fortes da URL.
         *
         * Isso evita um canal chamado
         * "Canal Filmes" ir para Filmes.
         */
        if (
            "/live/" in path ||
            path.endsWith(".ts")
        ) {
            return ContentType.LIVE
        }

        if (
            "/series/" in path ||
            "/serie/" in path ||
            "/tvseries/" in path ||
            "type=series" in lowerUrl ||
            "type=serie" in lowerUrl ||
            "content=series" in lowerUrl ||
            "category=series" in lowerUrl
        ) {
            return ContentType.SERIES
        }

        /*
         * Nome com S01E01, T01E01,
         * 1x01 etc. é episódio. Alguns servidores colocam esses episódios
         * em /movie/, portanto esta evidência precisa vir antes do caminho.
         */
        if (
            episodeInfo.episode != null ||
            episodeInfo.season != null ||
            episodeInfo.seriesName.isNotBlank()
        ) {
            return ContentType.SERIES
        }

        if (isClearlyLiveGroup(lowerGroup)) {
            return ContentType.LIVE
        }

        /*
         * Categorias explicitamente
         * de séries.
         */
        if (
            containsAny(
                lowerGroup,
                listOf(
                    "series",
                    "séries",
                    "serie ",
                    "série ",
                    "tv show",
                    "tv shows",
                    "seriado",
                    "seriados",
                    "temporada",
                    "temporadas",
                    "season",
                    "seasons",
                    "episodio",
                    "episódio",
                    "episodios",
                    "episódios",
                    "novela",
                    "novelas",
                    "dorama",
                    "doramas",
                    "anime",
                    "animes",
                    "reality",
                    "minisserie",
                    "minissérie"
                )
            )
        ) {
            return ContentType.SERIES
        }

        if (
            "/movie/" in path
        ) {
            return ContentType.VOD
        }

        /*
         * Arquivos típicos de VOD.
         */
        if (
            path.endsWith(".mp4") ||
            path.endsWith(".mkv") ||
            path.endsWith(".avi") ||
            path.endsWith(".mov") ||
            path.endsWith(".wmv") ||
            path.endsWith(".webm")
        ) {
            return ContentType.VOD
        }

        /*
         * Categorias claramente de
         * televisão ao vivo.
         */
        /*
         * Categorias claramente de filmes.
         */
        if (
            containsAny(
                lowerGroup,
                listOf(
                    "filmes",
                    "filme ",
                    "movies",
                    "movie ",
                    "cinema",
                    "vod "
                )
            )
        ) {
            return ContentType.VOD
        }

        /*
         * Se não houver nenhuma evidência
         * de VOD ou Série, consideramos
         * TV ao vivo.
         */
        return ContentType.LIVE
    }

    private fun isClearlyLiveGroup(lowerGroup: String): Boolean =
        containsAny(
            lowerGroup,
            listOf(
                "canais",
                "ao vivo",
                "tv ao vivo",
                "live tv",
                "tv aberta",
                "canais abertos",
                "canais fechados"
            )
        )

    private fun parseEpisodeInfo(
        name: String
    ): EpisodeInfo {

        reversedSeasonEpisodePattern
            .find(name)
            ?.let { match ->
                val episode =
                    match.groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()

                val season =
                    match.groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()

                val title =
                    name.substring(0, match.range.first)
                        .cleanTitle()

                return EpisodeInfo(
                    seriesName =
                        title.ifBlank {
                            cleanSeriesName(name)
                        },
                    season = season,
                    episode = episode
                )
            }

        for (
            regex in
            seasonEpisodePatterns
        ) {

            val match =
                regex.find(name)
                    ?: continue

            val season =
                match.groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()

            val episode =
                match.groupValues
                    .getOrNull(2)
                    ?.toIntOrNull()

            val title =
                name
                    .substring(
                        0,
                        match.range.first
                    )
                    .cleanTitle()

            return EpisodeInfo(
                seriesName =
                    title.ifBlank {
                        cleanSeriesName(
                            name
                        )
                    },

                season = season,
                episode = episode
            )
        }

        val episodeOnly =
            episodeOnlyPattern
                .find(name)

        if (
            episodeOnly != null
        ) {

            val episode =
                episodeOnly
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()

            val title =
                name
                    .substring(
                        0,
                        episodeOnly
                            .range.first
                    )
                    .cleanTitle()

            return EpisodeInfo(
                seriesName = title,
                season = null,
                episode = episode
            )
        }

        return EpisodeInfo(
            seriesName = "",
            season = null,
            episode = null
        )
    }

    private fun cleanSeriesName(
        value: String
    ): String {

        var text =
            value.trim()

        for (
            regex in
            seasonEpisodePatterns
        ) {
            text =
                text.replace(
                    regex,
                    " "
                )
        }

        text =
            text.replace(
                reversedSeasonEpisodePattern,
                " "
            )

        text =
            text.replace(
                episodeOnlyPattern,
                " "
            )

        return text
            .cleanTitle()
            .ifBlank {
                value.trim()
            }
    }

    private fun String.cleanTitle():
        String {

        return this
            .replace(
                Regex(
                    """[\[\]()]+"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\s*[-–—|:]+\s*$"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\s{2,}"""
                ),
                " "
            )
            .trim(
                ' ',
                '-',
                '–',
                '—',
                '|',
                ':',
                '.'
            )
    }

    private fun containsAny(
        text: String,
        words: List<String>
    ): Boolean {

        return words.any {
            it in text
        }
    }

    private fun firstNumberAttribute(
        attributes: Map<String, String>,
        vararg keys: String
    ): Int? =
        keys
            .asSequence()
            .mapNotNull { key ->
                attributes[key]
                    ?.let {
                        Regex("""\d{1,4}""")
                            .find(it)
                            ?.value
                            ?.toIntOrNull()
                    }
            }
            .firstOrNull()

    private fun String.normalizeGroupName(): String =
        trim()
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("""\s*[|/\\]\s*"""), " | ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '|', '-', ':')

    /*
     * A virgula que separa os atributos do titulo e a primeira que aparece
     * fora de aspas. Usar a ultima quebrava nomes como "Serie, O Retorno" e
     * acabava agrupando episodios/capas na serie errada.
     */
    private fun extractDisplayName(
        metadata: String
    ): String {

        var quote: Char? = null

        metadata.forEachIndexed { index, char ->
            when {
                quote == null && (char == '\"' || char == '\'') ->
                    quote = char

                quote == char ->
                    quote = null

                quote == null && char == ',' ->
                    return metadata.substring(index + 1)
            }
        }

        return metadata.substringAfter(':', "")
    }

    private fun String.normalizeArtworkUrl(streamUrl: String): String {
        val cleaned =
            trim()
                .replace("&amp;", "&")
                .replace("\\/", "/")

        if (cleaned.isBlank()) return ""

        if (
            cleaned.startsWith("http://", true) ||
            cleaned.startsWith("https://", true) ||
            cleaned.startsWith("data:", true) ||
            cleaned.startsWith("content:", true)
        ) {
            return cleaned
        }

        return runCatching {
            URL(URL(streamUrl), cleaned).toString()
        }.getOrDefault(cleaned)
    }

    private fun String.normalizeTrailerUrl(streamUrl: String): String {
        val cleaned =
            trim()
                .replace("&amp;", "&")
                .replace("\\/", "/")

        if (cleaned.isBlank()) return ""

        if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(cleaned)) {
            return "https://www.youtube.com/watch?v=$cleaned"
        }

        if (
            cleaned.startsWith("http://", true) ||
            cleaned.startsWith("https://", true)
        ) {
            return cleaned
        }

        return runCatching {
            URL(URL(streamUrl), cleaned).toString()
        }.getOrDefault("")
    }
}
