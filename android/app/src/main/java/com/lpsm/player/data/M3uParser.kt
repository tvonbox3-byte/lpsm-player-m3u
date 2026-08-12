package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import java.io.Reader

object M3uParser {

    private val attributePattern =
        Regex("""([\w-]+)="([^"]*)"""")

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
                """(?i)\bS(\d{1,2})\s*E(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\bT(\d{1,2})\s*E(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(\d{1,2})\s*[xX]\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\b(?:temporada|season)\s*(\d{1,2}).*?\b(?:epis[oó]dio|episode|ep)\s*\.?\s*(\d{1,3})\b"""
            )
        )

    /*
     * Para listas que informam apenas:
     * EP 01
     * Episodio 01
     */
    private val episodeOnlyPattern =
        Regex(
            """(?i)\b(?:ep|epis[oó]dio|episode)\s*\.?\s*(\d{1,3})\b"""
        )

    private data class EpisodeInfo(
        val seriesName: String,
        val season: Int?,
        val episode: Int?
    )

    fun parse(
        reader: Reader,
        limit: Int = 60_000
    ): List<MediaEntry> {

        val output =
            ArrayList<MediaEntry>(4096)

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
                                    .associate {
                                        it.groupValues[1] to
                                            it.groupValues[2]
                                    }

                            val name =
                                metadata
                                    .substringAfterLast(',')
                                    .trim()
                                    .ifBlank {
                                        "Sem nome"
                                    }

                            val group =
                                attributes[
                                    "group-title"
                                ]
                                    ?.trim()
                                    ?.ifBlank {
                                        "Outros"
                                    }
                                    ?: "Outros"

                            val logo =
                                attributes[
                                    "tvg-logo"
                                ]
                                    ?.trim()
                                    ?: ""

                            val tvgId =
                                attributes[
                                    "tvg-id"
                                ]
                                    ?.trim()
                                    ?: ""

                            val episodeInfo =
                                parseEpisodeInfo(
                                    name
                                )

                            val type =
                                detectType(
                                    name = name,
                                    group = group,
                                    url = line,
                                    episodeInfo =
                                        episodeInfo
                                )

                            val finalSeriesName =
                                if (
                                    type ==
                                    ContentType.SERIES
                                ) {

                                    episodeInfo
                                        .seriesName
                                        .ifBlank {
                                            cleanSeriesName(
                                                name
                                            )
                                        }

                                } else {
                                    ""
                                }

                            output +=
                                MediaEntry(
                                    name = name,
                                    url = line,
                                    logo = logo,
                                    group = group,
                                    tvgId = tvgId,
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

                            metadata = ""

                            if (
                                output.size >=
                                limit
                            ) {
                                return@useLines
                            }
                        }
                    }
                }
            }

        return output
    }

    private fun detectType(
        name: String,
        group: String,
        url: String,
        episodeInfo: EpisodeInfo
    ): ContentType {

        val path =
            url
                .lowercase()
                .substringBefore('?')
                .substringBefore('#')

        val lowerName =
            name.lowercase()

        val lowerGroup =
            group.lowercase()

        val text =
            "$lowerGroup $lowerName"

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
            "/series/" in path
        ) {
            return ContentType.SERIES
        }

        if (
            "/movie/" in path
        ) {
            return ContentType.VOD
        }

        /*
         * Nome com S01E01, T01E01,
         * 1x01 etc. é episódio.
         */
        if (
            episodeInfo.episode != null
        ) {
            return ContentType.SERIES
        }

        /*
         * Categorias explicitamente
         * de séries.
         */
        if (
            containsAny(
                text,
                listOf(
                    "series",
                    "séries",
                    "serie ",
                    "série ",
                    "temporada",
                    "season"
                )
            )
        ) {
            return ContentType.SERIES
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
        if (
            containsAny(
                lowerGroup,
                listOf(
                    "canais",
                    "ao vivo",
                    "tv ao vivo",
                    "live tv",
                    "canais abertos",
                    "canais fechados"
                )
            )
        ) {
            return ContentType.LIVE
        }

        /*
         * Categorias claramente de filmes.
         */
        if (
            containsAny(
                text,
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

    private fun parseEpisodeInfo(
        name: String
    ): EpisodeInfo {

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
}
