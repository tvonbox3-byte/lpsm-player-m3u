package com.lpsm.player.data

import com.lpsm.player.BuildConfig
import com.lpsm.player.model.ContentType

object CatalogScope {
    val allowedTypes: Set<ContentType> = if (BuildConfig.CINEMA)
        setOf(ContentType.VOD, ContentType.SERIES) else setOf(ContentType.LIVE)
    fun allows(type: ContentType) = type in allowedTypes
    val updateUrls: List<String> = if (BuildConfig.CINEMA) listOf(
        "https://github.com/tvonbox3-byte/lpsm-player-m3u/releases/latest/download/update-cinema.json"
    ) else listOf(
        BuildConfig.API_BASE_URL.trimEnd('/') + "/api/app/update",
        "https://github.com/tvonbox3-byte/lpsm-player-m3u/releases/latest/download/update.json"
    )
}
