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
                """(?i)\bS(\d{1,2})\s*[-._ ]*EP?\s*\.?\s*(\d{1,3})\b"""
            ),

            Regex(
                """(?i)\bT(\d{1,2})\s*[-._ ]*EP?\s*\.?\s*(\d{1,3})\b"""
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
        var partialStage = 0
        val partialMarks = intArrayOf(800, 4_000, 12_000)

        fun emitPartialIfNeeded() {
            val callback = onPartial ?: return
            if (partialStage >= partialMarks.size) return
            if (kept < partialMarks[partialStage]) return

            val snapshot = buildList {
                addAll(buckets.getValue(ContentType.LIVE))
                addAll(buckets.getValue(ContentType.VOD))
                addAll(buckets.getValue(ContentType.SERIES))
            }
            callback(snapshot)
            partialStage += 1
        }

        fun keep(entry: MediaEntry) {
            if (limit <= 0) return

            val target = buckets.getValue(entry.type)

            if (kept < limit) {
                target += entry
                kept += 1
                emitPartialIfNeeded()
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
            emitPartialIfNeeded()
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

                            keep(
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

                    !hasLiveStream &&
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
            "/series/" in path ||
            "type=series" in lowerUrl ||
            "type=serie" in lowerUrl ||
            "content=series" in lowerUrl ||
            "category=series" in lowerUrl
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
