package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import java.io.Reader
import java.net.URL
import java.text.Normalizer
import java.util.Locale

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

    private const val SERIES_DIVERSITY_EPISODES = 6
    private const val VOD_CATEGORY_MIN_ITEMS = 2

    private val seriesDiacriticsPattern =
        Regex("""\p{M}+""")

    private val seriesQualityPattern =
        Regex(
            """\b(?:4k|uhd|fhd|full\s*hd|hd|sd|h\.?26[45]|x26[45])\b"""
        )

    private val seriesNonAlphaNumericPattern =
        Regex("""[^a-z0-9]+""")

    private val seriesRepeatedSpacePattern =
        Regex("""\s{2,}""")

    private data class EpisodeInfo(
        val seriesName: String,
        val season: Int?,
        val episode: Int?
    )

    fun parse(
        reader: Reader,
        limit: Int = 180_000,
        onPartial: ((List<MediaEntry>) -> Unit)? = null,
        liveOnly: Boolean = false
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
                ContentType.LIVE to (limit * 5 / 100),
                ContentType.VOD to (limit * 48 / 100),
                ContentType.SERIES to (limit * 47 / 100)
            )

        var kept = 0

        /*
         * As listas M3U normalmente chegam agrupadas: todos os episódios de
         * uma série, depois os da próxima. Quando o teto de memória era
         * alcançado, os episódios restantes do arquivo eram descartados e as
         * categorias finais nunca apareciam.
         *
         * Mantemos o mesmo limite total, mas equilibramos somente o espaço de
         * SÉRIES. Assim, uma série muito longa deixa de ocupar todo o catálogo
         * e séries/categorias encontradas mais tarde também ganham espaço. Os
         * canais e filmes permanecem com o comportamento já aprovado.
         */
        val vodBucket =
            buckets.getValue(ContentType.VOD)

        val vodGroupCounts =
            linkedMapOf<String, Int>()

        fun vodGroupKey(entry: MediaEntry): String =
            entry.group
                .trim()
                .lowercase(Locale.ROOT)
                .ifBlank { "outros" }

        fun trackVod(entry: MediaEntry) {
            val key = vodGroupKey(entry)
            vodGroupCounts[key] = (vodGroupCounts[key] ?: 0) + 1
        }

        fun untrackVod(entry: MediaEntry) {
            val key = vodGroupKey(entry)
            val next = (vodGroupCounts[key] ?: 1) - 1
            if (next <= 0) {
                vodGroupCounts.remove(key)
            } else {
                vodGroupCounts[key] = next
            }
        }

        /*
         * Quando o teto de filmes é alcançado, uma categoria que aparece no
         * fim da M3U não pode simplesmente desaparecer. Se for uma categoria
         * ainda não representada, trocamos um item de uma categoria muito
         * repetida por esse primeiro filme. Isso preserva a variedade de
         * pastas sem aumentar o consumo de memória da box.
         */
        fun removeVodVictimPreservingCategories(): Boolean {
            if (vodBucket.isEmpty()) return false

            val victimKey =
                vodGroupCounts
                    .entries
                    .asSequence()
                    .filter { it.value > VOD_CATEGORY_MIN_ITEMS }
                    .maxByOrNull { it.value }
                    ?.key
                    ?: vodGroupCounts
                        .maxByOrNull { it.value }
                        ?.key
                    ?: return false

            val position =
                vodBucket.indexOfLast {
                    vodGroupKey(it) == victimKey
                }

            if (position < 0) return false

            val removed = vodBucket.removeAt(position)
            untrackVod(removed)
            return true
        }

        fun replaceForVodCategoryDiversity(
            entry: MediaEntry
        ): Boolean {
            if (vodBucket.isEmpty()) return false

            val incomingKey = vodGroupKey(entry)
            if ((vodGroupCounts[incomingKey] ?: 0) > 0) return false

            val victimKey =
                vodGroupCounts
                    .entries
                    .asSequence()
                    .filter { it.key != incomingKey && it.value > VOD_CATEGORY_MIN_ITEMS }
                    .maxByOrNull { it.value }
                    ?.key
                    ?: return false

            val position =
                vodBucket.indexOfLast {
                    vodGroupKey(it) == victimKey
                }

            if (position < 0) return false

            val removed = vodBucket[position]
            untrackVod(removed)
            vodBucket[position] = entry
            trackVod(entry)
            return true
        }

        val seriesBucket =
            buckets.getValue(ContentType.SERIES)

        val seriesPositions =
            linkedMapOf<String, LinkedHashSet<Int>>()

        val seriesKeyAtPosition =
            ArrayList<String>()

        fun trackSeriesAt(
            entry: MediaEntry,
            position: Int
        ) {
            val key = seriesRetentionKey(entry)
            val positions =
                seriesPositions.getOrPut(key) { LinkedHashSet() }

            positions.add(position)

            if (position == seriesKeyAtPosition.size) {
                seriesKeyAtPosition.add(key)
            } else {
                seriesKeyAtPosition[position] = key
            }
        }

        fun untrackLastSeries() {
            if (seriesKeyAtPosition.isEmpty()) return

            val position = seriesKeyAtPosition.lastIndex
            val key = seriesKeyAtPosition.removeAt(position)
            val positions = seriesPositions[key] ?: return

            positions.remove(position)

            if (positions.isEmpty()) {
                seriesPositions.remove(key)
            }
        }

        fun replaceForSeriesDiversity(
            entry: MediaEntry
        ): Boolean {
            if (seriesBucket.isEmpty()) return false

            val incomingKey = seriesRetentionKey(entry)
            val incomingCount =
                seriesPositions[incomingKey]?.size ?: 0

            /*
             * Depois que a cota de séries está cheia, só equilibramos os
             * primeiros episódios de cada título. A versão anterior procurava
             * e trocava uma posição para praticamente todo episódio restante
             * da lista. Em TV Boxes fracas isso prendia o parser por minutos e
             * Filmes/Séries pareciam vazios.
             */
            if (incomingCount >= SERIES_DIVERSITY_EPISODES) return false

            val victim =
                seriesPositions
                    .entries
                    .asSequence()
                    .filter { candidate ->
                        candidate.key != incomingKey &&
                            candidate.value.size > SERIES_DIVERSITY_EPISODES
                    }
                    .maxByOrNull { candidate ->
                        candidate.value.size
                    }
                    ?: return false

            val victimKey = victim.key
            val victimPositions = victim.value

            /* Mantém as quantidades equilibradas e evita troca sem ganho. */
            if (victimPositions.size <= incomingCount + 1) return false
            val position =
                victimPositions.firstOrNull() ?: return false

            victimPositions.remove(position)
            if (victimPositions.isEmpty()) {
                seriesPositions.remove(victimKey)
            }

            seriesBucket[position] = entry
            trackSeriesAt(entry, position)
            return true
        }

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
                !firstPartialEmitted &&
                    (
                        buckets.getValue(ContentType.LIVE).size >= 80 ||
                            kept >= 120
                    )

            val sectionReady =
                changedType != ContentType.LIVE &&
                    changedType !in readyTypePartials &&
                    buckets.getValue(changedType).size >= 120

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
            if (liveOnly && entry.type != ContentType.LIVE) return

            val target = buckets.getValue(entry.type)

            if (kept < limit) {
                val position = target.size
                target += entry
                if (entry.type == ContentType.SERIES) {
                    trackSeriesAt(entry, position)
                } else if (entry.type == ContentType.VOD) {
                    trackVod(entry)
                }
                kept += 1
                emitPartialIfNeeded(entry.type)
                return
            }

            val targetReserve = reserved.getValue(entry.type)
            if (target.size >= targetReserve) {
                val diversified =
                    when (entry.type) {
                        ContentType.SERIES ->
                            replaceForSeriesDiversity(entry)

                        ContentType.VOD ->
                            replaceForVodCategoryDiversity(entry)

                        else ->
                            false
                    }

                if (diversified) {
                    emitPartialIfNeeded(entry.type)
                }
                return
            }

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

            if (victim.key == ContentType.SERIES) {
                untrackLastSeries()
                victim.value.removeAt(victim.value.lastIndex)
            } else if (victim.key == ContentType.VOD) {
                if (!removeVodVictimPreservingCategories()) {
                    victim.value.removeAt(victim.value.lastIndex)
                }
            } else {
                victim.value.removeAt(victim.value.lastIndex)
            }

            val position = target.size
            target += entry
            if (entry.type == ContentType.SERIES) {
                trackSeriesAt(entry, position)
            } else if (entry.type == ContentType.VOD) {
                trackVod(entry)
            }
            emitPartialIfNeeded(entry.type)
        }

        var metadata = ""
        var extGroup = ""

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
                            extGroup = ""
                        }

                        /*
                         * Muitas listas (principalmente VOD grandes) colocam a
                         * categoria em #EXTGRP em vez de group-title. Ignorar
                         * isso fazia milhares de filmes/séries caírem em
                         * "Outros" e dava a impressão de poucas pastas.
                         */
                        line.startsWith(
                            "#EXTGRP:",
                            true
                        ) && metadata.isNotEmpty() -> {
                            extGroup =
                                line.substringAfter(':', "")
                                    .normalizeGroupName()
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
                                listOf(
                                    "group-title",
                                    "group",
                                    "group-name",
                                    "group_name",
                                    "tvg-group",
                                    "tvg_group",
                                    "category",
                                    "category-name",
                                    "category_name"
                                )
                                    .firstNotNullOfOrNull { key ->
                                        attributes[key]
                                            ?.normalizeGroupName()
                                            ?.takeIf { it.isNotBlank() }
                                    }
                                    ?: extGroup.takeIf { it.isNotBlank() }
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
                                    episodeInfo = episodeInfo,
                                    attributes = attributes
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
                            extGroup = ""
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

        if (liveOnly) {
            return buckets.getValue(ContentType.LIVE).toList()
        }

        return normalizeVodGroups(
            normalizeSeriesGroups(keptEntries)
        )
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
                entry.type != ContentType.LIVE
            ) {
                /*
                 * VOD declarado/identificado nunca deve ser promovido para
                 * série só porque divide uma categoria pequena com episódios.
                 */
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

    /*
     * Alguns catálogos usam nomes de pasta genéricos (Ação, Terror, Infantil)
     * e só parte dos itens traz um sinal inequívoco de VOD. Quando o grupo não
     * possui transporte de TV ao vivo e já contém filmes identificados,
     * promovemos os itens restantes do mesmo grupo para VOD. Isso preserva as
     * pastas originais do fornecedor sem transformar canais reais em filmes.
     */
    private fun normalizeVodGroups(
        source: List<MediaEntry>
    ): List<MediaEntry> {
        val promotedGroups =
            source
                .groupBy { it.group.trim().lowercase(Locale.ROOT) }
                .filterValues { items ->
                    val vodCount =
                        items.count { it.type == ContentType.VOD }

                    val hasSeries =
                        items.any { it.type == ContentType.SERIES }

                    val hasLiveTransport =
                        items.any { entry ->
                            val path =
                                entry.url.lowercase(Locale.ROOT)
                                    .substringBefore('?')
                                    .substringBefore('#')

                            ("/live/" in path || path.endsWith(".ts")) &&
                                entry.tvgId.isNotBlank()
                        }

                    val clearlyLiveGroup =
                        items.any {
                            isClearlyLiveGroup(
                                it.group.lowercase(Locale.ROOT)
                            )
                        }

                    !hasSeries &&
                        !hasLiveTransport &&
                        !clearlyLiveGroup &&
                        vodCount > 0 &&
                        (items.size <= 5 || vodCount * 3 >= items.size)
                }
                .keys

        if (promotedGroups.isEmpty()) return source

        return source.map { entry ->
            if (
                entry.group.trim().lowercase(Locale.ROOT) in promotedGroups &&
                entry.type == ContentType.LIVE
            ) {
                entry.copy(type = ContentType.VOD)
            } else {
                entry
            }
        }
    }

    private fun detectType(
        name: String,
        group: String,
        url: String,
        episodeInfo: EpisodeInfo,
        attributes: Map<String, String>
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
         * BUILD 60
         *
         * Alguns fornecedores usam a mesma estrutura de URL para TV, filmes
         * e séries. Antes, um "/live/" na URL podia classificar todo o
         * catálogo como TV mesmo quando group-title/type dizia FILMES/SÉRIES.
         * Primeiro respeitamos metadados explícitos e categorias fortes.
         */
        val declaredType =
            listOf(
                "type",
                "stream-type",
                "stream_type",
                "content-type",
                "content_type",
                "media-type",
                "media_type"
            )
                .firstNotNullOfOrNull { key ->
                    attributes[key]
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotBlank() }
                }
                .orEmpty()

        when {
            containsAny(
                declaredType,
                listOf("series", "serie", "tvshow", "tv show", "episode")
            ) -> return ContentType.SERIES

            containsAny(
                declaredType,
                listOf("movie", "vod", "film", "filme", "cinema")
            ) -> return ContentType.VOD

            containsAny(
                declaredType,
                listOf("live", "channel", "canal", "tv")
            ) -> return ContentType.LIVE
        }

        /* Nome/atributos de episódio são a evidência mais forte para Série. */
        if (
            episodeInfo.episode != null ||
            episodeInfo.season != null ||
            episodeInfo.seriesName.isNotBlank()
        ) {
            return ContentType.SERIES
        }

        /* Caminhos explícitos de catálogo vencem nomes genéricos de pasta. */
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

        if (
            "/movie/" in path ||
            "/vod/" in path ||
            "type=movie" in lowerUrl ||
            "type=vod" in lowerUrl ||
            "content=movie" in lowerUrl ||
            "content=vod" in lowerUrl
        ) {
            return ContentType.VOD
        }

        val liveTransport =
            "/live/" in path || path.endsWith(".ts")

        val hasTvgIdentity =
            attributes["tvg-id"]
                ?.trim()
                ?.isNotBlank() == true

        val channelishName =
            Regex(
                """(?i)^(?:canal|tv|rede|24h|24\s*horas|ao\s*vivo|live\s*[-:])\b"""
            ).containsMatchIn(name.trim())

        /*
         * Corrige o caso relatado em que canais ao vivo da pasta "Séries"
         * viravam capas de série. Transporte LIVE + identidade de EPG/nome de
         * canal é suficiente para manter o item em TV ao vivo.
         */
        val hlsTransport =
            path.endsWith(".m3u8") || "/live/" in path

        if (
            (liveTransport || hlsTransport) &&
            (hasTvgIdentity || channelishName || isClearlyLiveGroup(lowerGroup))
        ) {
            return ContentType.LIVE
        }

        if (isClearlySeriesGroup(lowerGroup)) {
            return ContentType.SERIES
        }

        if (isClearlyVodGroup(lowerGroup)) {
            return ContentType.VOD
        }

        if (
            path.endsWith(".mp4") ||
            path.endsWith(".mkv") ||
            path.endsWith(".avi") ||
            path.endsWith(".mov") ||
            path.endsWith(".wmv") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mpg") ||
            path.endsWith(".mpeg")
        ) {
            return ContentType.VOD
        }

        if (liveTransport) {
            return ContentType.LIVE
        }

        return ContentType.LIVE
    }

    private fun isClearlySeriesGroup(lowerGroup: String): Boolean {
        if (isClearlyLiveGroup(lowerGroup)) return false

        return containsAny(
            lowerGroup,
            listOf(
                "series",
                "séries",
                "serie",
                "série",
                "tv show",
                "tv shows",
                "show de tv",
                "shows de tv",
                "boxset",
                "box set",
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
    }

    private fun isClearlyVodGroup(lowerGroup: String): Boolean {
        if (isClearlyLiveGroup(lowerGroup)) return false

        return containsAny(
            lowerGroup,
            listOf(
                "filmes",
                "filme ",
                " filme",
                "movies",
                "movie ",
                " movie",
                "cinema",
                "vod ",
                " vod",
                "lançamentos",
                "lancamentos"
            )
        )
    }

    private fun isClearlyLiveGroup(lowerGroup: String): Boolean {
        val compact = lowerGroup.trim()

        return compact.startsWith("canais") ||
            compact.startsWith("canal ") ||
            compact.startsWith("tv ao vivo") ||
            compact.startsWith("ao vivo") ||
            compact.startsWith("live tv") ||
            compact.startsWith("tv aberta") ||
            compact.startsWith("canais abertos") ||
            compact.startsWith("canais fechados") ||
            compact.startsWith("24h") ||
            compact.startsWith("24 horas") ||
            compact.startsWith("24/7")
    }

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

    private fun seriesRetentionKey(
        entry: MediaEntry
    ): String {
        val source =
            entry.seriesName
                .ifBlank { cleanSeriesName(entry.name) }
                .ifBlank { entry.name }

        return Normalizer
            .normalize(source, Normalizer.Form.NFD)
            .replace(seriesDiacriticsPattern, "")
            .lowercase(Locale.ROOT)
            .replace(
                seriesQualityPattern,
                " "
            )
            .replace(seriesNonAlphaNumericPattern, " ")
            .replace(seriesRepeatedSpacePattern, " ")
            .trim()
            .ifBlank {
                entry.url.trim().lowercase(Locale.ROOT)
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
