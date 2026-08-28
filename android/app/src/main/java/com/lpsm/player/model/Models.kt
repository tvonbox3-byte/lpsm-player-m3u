package com.lpsm.player.model

data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val xmltvUrl: String = "",
    val expiresAt: String? = null
)

data class Appearance(
    val bannerUrl: String = "",
    val wallpaperUrl: String = "",
    val supportMessage: String = "",
    val adultPin: String = "0202"
)

data class DeviceConfig(
    val clientName: String,
    val playlists: List<Playlist>,
    val appearance: Appearance
)

enum class ContentType {
    LIVE,
    VOD,
    SERIES
}

data class MediaEntry(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "Outros",
    val tvgId: String = "",
    val description: String = "",
    val type: ContentType = ContentType.LIVE,

    // Usados somente para séries.
    val seriesName: String = "",
    val season: Int? = null,
    val episode: Int? = null
)
