package com.lpsm.player.model
data class Playlist(val id:String,val name:String,val url:String,val xmltvUrl:String="",val expiresAt:String?=null)
data class Appearance(val bannerUrl:String="",val wallpaperUrl:String="",val supportMessage:String="")
data class DeviceConfig(val clientName:String,val playlists:List<Playlist>,val appearance:Appearance)
enum class ContentType { LIVE, VOD, SERIES }
data class MediaEntry(val name:String,val url:String,val logo:String="",val group:String="Outros",val tvgId:String="",val type:ContentType=ContentType.LIVE)
