package com.lpsm.player.data
import com.lpsm.player.model.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
class LpsmApi(private val store:SecureStore){
 private fun call(path:String,method:String="GET",payload:JSONObject?=null,auth:Boolean=true):JSONObject{val c=URL(store.serverUrl+path).openConnection() as HttpURLConnection;c.requestMethod=method;c.connectTimeout=12000;c.readTimeout=20000;c.setRequestProperty("Accept","application/json");if(auth)store.token?.let{c.setRequestProperty("Authorization","Bearer $it")};if(payload!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(payload.toString().toByteArray())}};val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream;val raw=stream.bufferedReader().use{it.readText()};if(c.responseCode !in 200..299)throw IllegalStateException(JSONObject(raw).optString("error","Erro ${c.responseCode}"));return JSONObject(raw)}
 fun activate(macAddress:String,code:String):String=call("/api/device/activate","POST",JSONObject().put("macAddress",macAddress).put("code",code),false).getString("token")
 fun config():DeviceConfig {
  val j=call("/api/device/config")
  val a=j.getJSONObject("appearance")
  val arr=j.getJSONArray("playlists")
  val lists=(0 until arr.length()).map { index ->
   val p=arr.getJSONObject(index)
   Playlist(p.getString("id"),p.getString("name"),p.getString("url"),p.optString("xmltvUrl"),p.optString("expiresAt").takeIf { value -> value.isNotBlank()&&value!="null" })
  }
  return DeviceConfig(j.getJSONObject("client").optString("name","Cliente"),lists,Appearance(a.optString("bannerUrl"),a.optString("wallpaperUrl"),a.optString("supportMessage")))
 }
 fun download(url:String):String{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=30000;c.instanceFollowRedirects=true;if(c.responseCode !in 200..299)throw IllegalStateException("Falha ao carregar lista (${c.responseCode})");return c.inputStream.bufferedReader().use{it.readText()}}
 fun downloadPlaylist(url:String,limit:Int):List<MediaEntry>{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=45000;c.instanceFollowRedirects=true;try{if(c.responseCode !in 200..299)throw IllegalStateException("Falha ao carregar lista (${c.responseCode})");return c.inputStream.bufferedReader().use{M3uParser.parse(it,limit)}}finally{c.disconnect()}}
}
