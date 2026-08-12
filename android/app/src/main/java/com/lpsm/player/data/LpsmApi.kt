package com.lpsm.player.data

import com.lpsm.player.model.Appearance
import com.lpsm.player.model.DeviceConfig
import com.lpsm.player.model.MediaEntry
import com.lpsm.player.model.Playlist

import org.json.JSONObject

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

import java.net.HttpURLConnection
import java.net.URL

import java.nio.charset.Charset

import java.util.zip.GZIPInputStream


class LpsmApi(
    private val store: SecureStore
) {

    companion object {

        private const val API_CONNECT_TIMEOUT =
            15_000

        private const val API_READ_TIMEOUT =
            30_000

        private const val PLAYLIST_CONNECT_TIMEOUT =
            20_000

        private const val PLAYLIST_READ_TIMEOUT =
            90_000

        private const val MAX_REDIRECTS =
            8

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; TV) " +
                "AppleWebKit/537.36 " +
                "(KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 " +
                "LPSM/2.1"
    }


    /*
     * =====================================================
     * URL DO PAINEL
     * =====================================================
     */

    private fun apiUrl(
        path: String
    ): String {

        val base =
            store.serverUrl
                .trim()
                .trimEnd('/')

        val route =
            if (
                path.startsWith("/")
            ) {
                path
            } else {
                "/$path"
            }

        return base + route
    }


    /*
     * =====================================================
     * CABEÇALHOS PADRÃO
     * =====================================================
     */

    private fun applyCommonHeaders(
        connection: HttpURLConnection,
        accept: String
    ) {

        connection.setRequestProperty(
            "User-Agent",
            USER_AGENT
        )

        connection.setRequestProperty(
            "Accept",
            accept
        )

        connection.setRequestProperty(
            "Accept-Language",
            "pt-BR,pt;q=0.9,en;q=0.8"
        )

        connection.setRequestProperty(
            "Accept-Encoding",
            "gzip"
        )

        connection.setRequestProperty(
            "Connection",
            "close"
        )

        connection.setRequestProperty(
            "Cache-Control",
            "no-cache"
        )

        connection.useCaches =
            false
    }


    /*
     * =====================================================
     * API DO PAINEL
     * =====================================================
     */

    private fun call(
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        auth: Boolean = true
    ): JSONObject {

        val connection =
            URL(
                apiUrl(path)
            )
                .openConnection()
                as HttpURLConnection

        try {

            connection.requestMethod =
                method

            connection.connectTimeout =
                API_CONNECT_TIMEOUT

            connection.readTimeout =
                API_READ_TIMEOUT

            connection.instanceFollowRedirects =
                true

            applyCommonHeaders(
                connection,
                "application/json"
            )


            if (auth) {

                store.token
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        token ->

                        connection
                            .setRequestProperty(
                                "Authorization",
                                "Bearer $token"
                            )
                    }
            }


            if (
                payload != null
            ) {

                connection.doOutput =
                    true

                connection
                    .setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )

                connection.outputStream
                    .buffered()
                    .use {
                        output ->

                        output.write(
                            payload
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                    }
            }


            val code =
                connection.responseCode


            val raw =
                readResponseText(
                    connection,
                    code in 200..299
                )


            if (
                code !in 200..299
            ) {

                val message =
                    try {

                        JSONObject(raw)
                            .optString(
                                "error",
                                "Erro $code"
                            )

                    } catch (
                        _: Exception
                    ) {

                        raw
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?.take(250)
                            ?: "Erro $code"
                    }


                throw IllegalStateException(
                    message
                )
            }


            if (
                raw.isBlank()
            ) {

                return JSONObject()
            }


            return JSONObject(
                raw
            )

        } finally {

            connection.disconnect()
        }
    }


    /*
     * =====================================================
     * ATIVAÇÃO
     * =====================================================
     */

    fun activate(
        macAddress: String,
        code: String
    ): String {

        val payload =
            JSONObject()
                .put(
                    "macAddress",
                    macAddress
                )
                .put(
                    "code",
                    code
                )


        return call(
            "/api/device/activate",
            "POST",
            payload,
            false
        )
            .getString(
                "token"
            )
    }


    /*
     * =====================================================
     * CONFIGURAÇÃO DO CLIENTE
     * =====================================================
     */

    fun config():
        DeviceConfig {

        val json =
            call(
                "/api/device/config"
            )


        val appearanceJson =
            json.optJSONObject(
                "appearance"
            )
                ?: JSONObject()


        val playlistsJson =
            json.optJSONArray(
                "playlists"
            )


        val playlists =
            if (
                playlistsJson != null
            ) {

                (
                    0 until
                        playlistsJson.length()
                    )
                    .mapNotNull {
                        index ->

                        val item =
                            playlistsJson
                                .optJSONObject(
                                    index
                                )
                                ?: return@mapNotNull null


                        val url =
                            item
                                .optString(
                                    "url",
                                    ""
                                )
                                .trim()


                        if (
                            url.isBlank()
                        ) {

                            return@mapNotNull null
                        }


                        Playlist(
                            id =
                                item
                                    .optString(
                                        "id",
                                        "lista-$index"
                                    ),

                            name =
                                item
                                    .optString(
                                        "name",
                                        "Lista"
                                    ),

                            url =
                                url,

                            xmltvUrl =
                                item
                                    .optString(
                                        "xmltvUrl",
                                        ""
                                    )
                                    .takeUnless {
                                        it == "null"
                                    }
                                    ?: "",

                            expiresAt =
                                item
                                    .optString(
                                        "expiresAt",
                                        ""
                                    )
                                    .takeIf {
                                        value ->

                                        value.isNotBlank() &&
                                            value != "null"
                                    }
                        )
                    }

            } else {

                emptyList()
            }


        val clientName =
            json
                .optJSONObject(
                    "client"
                )
                ?.optString(
                    "name",
                    "Cliente"
                )
                ?.ifBlank {
                    "Cliente"
                }
                ?: "Cliente"


        return DeviceConfig(

            clientName =
                clientName,

            playlists =
                playlists,

            appearance =
                Appearance(

                    bannerUrl =
                        appearanceJson
                            .optString(
                                "bannerUrl",
                                ""
                            ),

                    wallpaperUrl =
                        appearanceJson
                            .optString(
                                "wallpaperUrl",
                                ""
                            ),

                    supportMessage =
                        appearanceJson
                            .optString(
                                "supportMessage",
                                ""
                            )
                )
        )
    }


    /*
     * =====================================================
     * ABRIR URL COM REDIRECIONAMENTOS
     * =====================================================
     */

    private fun openGet(
        originalUrl: String,
        accept: String,
        connectTimeout: Int,
        readTimeout: Int
    ): HttpURLConnection {

        var current =
            originalUrl
                .trim()


        if (
            !current.startsWith(
                "http://",
                true
            ) &&
            !current.startsWith(
                "https://",
                true
            )
        ) {

            throw IllegalStateException(
                "URL inválida. Use http:// ou https://"
            )
        }


        repeat(
            MAX_REDIRECTS + 1
        ) {
            redirectCount ->


            val connection =
                URL(current)
                    .openConnection()
                    as HttpURLConnection


            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                connectTimeout

            connection.readTimeout =
                readTimeout

            connection.instanceFollowRedirects =
                false


            applyCommonHeaders(
                connection,
                accept
            )


            val responseCode =
                connection.responseCode


            if (
                responseCode in
                    listOf(
                        301,
                        302,
                        303,
                        307,
                        308
                    )
            ) {

                val location =
                    connection
                        .getHeaderField(
                            "Location"
                        )
                        ?.trim()


                if (
                    location.isNullOrBlank()
                ) {

                    connection.disconnect()

                    throw IllegalStateException(
                        "O servidor redirecionou a lista sem informar o novo endereço."
                    )
                }


                if (
                    redirectCount >=
                    MAX_REDIRECTS
                ) {

                    connection.disconnect()

                    throw IllegalStateException(
                        "A lista possui redirecionamentos demais."
                    )
                }


                val nextUrl =
                    URL(
                        URL(current),
                        location
                    )
                        .toString()


                connection.disconnect()


                current =
                    nextUrl


                return@repeat
            }


            return connection
        }


        throw IllegalStateException(
            "Não foi possível abrir a URL."
        )
    }


    /*
     * =====================================================
     * INPUT STREAM
     * =====================================================
     */

    private fun responseStream(
        connection: HttpURLConnection,
        success: Boolean
    ): InputStream {

        val base =
            if (success) {

                connection.inputStream

            } else {

                connection.errorStream
                    ?: connection.inputStream
            }


        val buffered =
            BufferedInputStream(
                base
            )


        val encoding =
            connection
                .contentEncoding
                ?.lowercase()
                ?: ""


        return if (
            "gzip" in encoding
        ) {

            GZIPInputStream(
                buffered
            )

        } else {

            buffered
        }
    }


    /*
     * =====================================================
     * CHARSET
     * =====================================================
     */

    private fun responseCharset(
        connection: HttpURLConnection
    ): Charset {

        val contentType =
            connection.contentType
                ?: ""


        val charsetName =
            Regex(
                """charset\s*=\s*["']?([^;"'\s]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    contentType
                )
                ?.groupValues
                ?.getOrNull(1)


        return try {

            if (
                charsetName.isNullOrBlank()
            ) {

                Charsets.UTF_8

            } else {

                Charset.forName(
                    charsetName
                )
            }

        } catch (
            _: Exception
        ) {

            Charsets.UTF_8
        }
    }


    /*
     * =====================================================
     * LER TEXTO
     * =====================================================
     */

    private fun readResponseText(
        connection: HttpURLConnection,
        success: Boolean
    ): String {

        return responseStream(
            connection,
            success
        )
            .bufferedReader(
                responseCharset(
                    connection
                )
            )
            .use {
                reader ->

                reader.readText()
            }
    }


    /*
     * =====================================================
     * DOWNLOAD GENÉRICO
     * XMLTV / EPG
     * =====================================================
     */

    fun download(
        url: String
    ): String {

        val connection =
            openGet(
                originalUrl =
                    url,

                accept =
                    "application/xml,text/xml,text/plain,*/*",

                connectTimeout =
                    PLAYLIST_CONNECT_TIMEOUT,

                readTimeout =
                    PLAYLIST_READ_TIMEOUT
            )


        try {

            val code =
                connection.responseCode


            if (
                code !in 200..299
            ) {

                val error =
                    try {

                        readResponseText(
                            connection,
                            false
                        )
                            .trim()
                            .take(200)

                    } catch (
                        _: Exception
                    ) {

                        ""
                    }


                throw IllegalStateException(
                    if (
                        error.isBlank()
                    ) {

                        "Servidor respondeu HTTP $code"

                    } else {

                        "Servidor respondeu HTTP $code: $error"
                    }
                )
            }


            return readResponseText(
                connection,
                true
            )

        } finally {

            connection.disconnect()
        }
    }


    /*
     * =====================================================
     * DOWNLOAD DA LISTA M3U
     * =====================================================
     */

    fun downloadPlaylist(
        url: String,
        limit: Int
    ): List<MediaEntry> {

        val connection =
            openGet(

                originalUrl =
                    url,

                accept =
                    "application/x-mpegURL," +
                        "application/vnd.apple.mpegurl," +
                        "audio/x-mpegurl," +
                        "text/plain," +
                        "*/*",

                connectTimeout =
                    PLAYLIST_CONNECT_TIMEOUT,

                readTimeout =
                    PLAYLIST_READ_TIMEOUT
            )


        try {

            val code =
                connection.responseCode


            if (
                code !in 200..299
            ) {

                val errorText =
                    try {

                        readResponseText(
                            connection,
                            false
                        )
                            .replace(
                                Regex(
                                    "\\s+"
                                ),
                                " "
                            )
                            .trim()
                            .take(200)

                    } catch (
                        _: Exception
                    ) {

                        ""
                    }


                val message =
                    when {

                        code ==
                            401 ->

                            "A lista recusou usuário ou senha (HTTP 401)."


                        code ==
                            403 ->

                            "O servidor da lista bloqueou o acesso (HTTP 403)."


                        code ==
                            404 ->

                            "A URL da lista não foi encontrada (HTTP 404)."


                        code >=
                            500 ->

                            "O servidor da lista está com erro (HTTP $code)."


                        errorText
                            .isNotBlank() ->

                            "Falha HTTP $code: $errorText"


                        else ->

                            "Falha ao carregar lista (HTTP $code)."
                    }


                throw IllegalStateException(
                    message
                )
            }


            val stream =
                responseStream(
                    connection,
                    true
                )


            val reader =
                BufferedReader(
                    InputStreamReader(
                        stream,
                        responseCharset(
                            connection
                        )
                    ),
                    64 * 1024
                )


            reader.use {

                /*
                 * Verifica o início da resposta
                 * antes de mandar para o parser.
                 */
                it.mark(
                    32 * 1024
                )


                val previewChars =
                    CharArray(
                        8 * 1024
                    )


                val read =
                    it.read(
                        previewChars
                    )


                val preview =
                    if (
                        read > 0
                    ) {

                        String(
                            previewChars,
                            0,
                            read
                        )

                    } else {

                        ""
                    }


                it.reset()


                val normalizedPreview =
                    preview
                        .trimStart(
                            '\uFEFF',
                            ' ',
                            '\n',
                            '\r',
                            '\t'
                        )
                        .lowercase()


                if (
                    normalizedPreview.isBlank()
                ) {

                    throw IllegalStateException(
                        "O servidor retornou uma lista vazia."
                    )
                }


                if (
                    normalizedPreview
                        .startsWith(
                            "<!doctype html"
                        ) ||
                    normalizedPreview
                        .startsWith(
                            "<html"
                        ) ||
                    "<body" in
                        normalizedPreview
                ) {

                    throw IllegalStateException(
                        "O endereço retornou uma página HTML em vez de uma lista M3U."
                    )
                }


                if (
                    "invalid username" in
                        normalizedPreview ||
                    "invalid password" in
                        normalizedPreview ||
                    "wrong username" in
                        normalizedPreview ||
                    "wrong password" in
                        normalizedPreview ||
                    "authentication failed" in
                        normalizedPreview
                ) {

                    throw IllegalStateException(
                        "Usuário ou senha da lista estão incorretos."
                    )
                }


                val parsed =
                    M3uParser.parse(
                        it,
                        limit
                    )


                if (
                    parsed.isEmpty()
                ) {

                    throw IllegalStateException(
                        "A URL respondeu, mas nenhum canal, filme ou episódio M3U foi encontrado."
                    )
                }


                return parsed
            }

        } finally {

            connection.disconnect()
        }
    }
}
