package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import java.io.Reader

object M3uParser {
    private val attributePattern = Regex("([\\w-]+)=\"([^\"]*)\"")
    fun parse(reader: Reader, limit: Int = 60_000): List<MediaEntry> {
        val output = ArrayList<MediaEntry>(4096)
        var metadata = ""
        reader.buffered(64 * 1024).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", true) -> metadata = line
                    line.isNotEmpty() && !line.startsWith("#") && metadata.isNotEmpty() -> {
                        val attributes = attributePattern
                            .findAll(metadata)
                            .associate { it.groupValues[1] to it.groupValues[2] }
                        val name = metadata.substringAfterLast(',').trim().ifBlank { "Sem nome" }
                        val group = attributes["group-title"] ?: "Outros"
                        val lower = "$group $name".lowercase()
                        val path = line.lowercase()
                        val type = when {
                            "/series/" in path -> ContentType.SERIES
                            "/movie/" in path -> ContentType.VOD
                            "/live/" in path -> ContentType.LIVE
                            listOf("series", "série", "season", "temporada").any { it in lower } -> ContentType.SERIES
                            listOf("vod", "movie", "filme", "cinema").any { it in lower } -> ContentType.VOD
                            else -> ContentType.LIVE
                        }
                        output += MediaEntry(name, line, attributes["tvg-logo"] ?: "", group, attributes["tvg-id"] ?: "", type)
                        metadata = ""
                        if (output.size >= limit) return@useLines
                    }
                }
            }
        }
        return output
    }
}
