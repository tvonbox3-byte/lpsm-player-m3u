package com.lpsm.player.data

import android.content.Context
import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry
import com.lpsm.player.model.Playlist
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Cópia compacta da última lista autorizada.
 *
 * Ela permite exibir o conteúdo logo após a validação do MAC enquanto a
 * versão atualizada é baixada em segundo plano. O cache é separado pela
 * combinação de URLs liberadas para o aparelho e expira automaticamente.
 */
class PlaylistCache(
    context: Context
) {

    /*
     * filesDir nao e limpo automaticamente pelo Android como cacheDir.
     * Isso evita que TV Boxes com pouco armazenamento percam a lista e
     * precisem processar dezenas de milhares de itens em toda abertura.
     */
    private val file =
        File(
            context.filesDir,
            "authorized_playlist_v1.gz"
        )

    private val legacyCacheFile =
        File(
            context.cacheDir,
            "authorized_playlist_v1.gz"
        )

    init {
        if (!file.isFile && legacyCacheFile.isFile) {
            try {
                legacyCacheFile.copyTo(file, overwrite = false)
            } catch (_: Throwable) {
                // Se a migracao falhar, a lista sera recriada normalmente.
            }
        }
    }

    fun signature(
        playlists: List<Playlist>
    ): String {

        val source =
            playlists
                .map {
                    it.url
                        .trim()
                        .lowercase()
                }
                .sorted()
                .joinToString("\n")

        return MessageDigest
            .getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }
    }

    fun read(
        expectedSignature: String,
        maxAgeMillis: Long = 24L * 60L * 60L * 1000L
    ): List<MediaEntry> {

        if (!file.isFile) {
            return emptyList()
        }

        return try {

            DataInputStream(
                BufferedInputStream(
                    GZIPInputStream(
                        file.inputStream()
                    ),
                    64 * 1024
                )
            ).use { input ->

                if (input.readInt() != FORMAT_VERSION) {
                    return emptyList()
                }

                if (readString(input) != expectedSignature) {
                    return emptyList()
                }

                val savedAt = input.readLong()

                if (
                    savedAt <= 0L ||
                    System.currentTimeMillis() - savedAt > maxAgeMillis
                ) {
                    return emptyList()
                }

                val count = input.readInt()

                if (count !in 1..MAX_ENTRIES) {
                    return emptyList()
                }

                ArrayList<MediaEntry>(count).apply {
                    repeat(count) {
                        add(
                            MediaEntry(
                                name = readString(input),
                                url = readString(input),
                                logo = readString(input),
                                group = readString(input),
                                tvgId = readString(input),
                                type =
                                    ContentType.valueOf(
                                        readString(input)
                                    ),
                                seriesName = readString(input),
                                season = input.readInt().takeIf { it >= 0 },
                                episode = input.readInt().takeIf { it >= 0 }
                            )
                        )
                    }
                }
            }

        } catch (_: Throwable) {

            file.delete()
            emptyList()
        }
    }

    fun write(
        signature: String,
        entries: List<MediaEntry>
    ) {

        if (entries.isEmpty()) {
            return
        }

        val temporary =
            File(
                file.parentFile,
                "${file.name}.tmp"
            )

        try {

            DataOutputStream(
                BufferedOutputStream(
                    GZIPOutputStream(
                        temporary.outputStream()
                    ),
                    64 * 1024
                )
            ).use { output ->

                output.writeInt(FORMAT_VERSION)
                writeString(output, signature)
                output.writeLong(System.currentTimeMillis())
                output.writeInt(entries.size.coerceAtMost(MAX_ENTRIES))

                entries
                    .take(MAX_ENTRIES)
                    .forEach { entry ->
                        writeString(output, entry.name)
                        writeString(output, entry.url)
                        writeString(output, entry.logo)
                        writeString(output, entry.group)
                        writeString(output, entry.tvgId)
                        writeString(output, entry.type.name)
                        writeString(output, entry.seriesName)
                        output.writeInt(entry.season ?: -1)
                        output.writeInt(entry.episode ?: -1)
                    }
            }

            if (file.exists()) {
                file.delete()
            }

            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }

        } catch (_: Throwable) {

            temporary.delete()
        }
    }

    private fun writeString(
        output: DataOutputStream,
        value: String
    ) {

        val bytes =
            value
                .toByteArray(Charsets.UTF_8)
                .let {
                    if (it.size <= MAX_STRING_BYTES) {
                        it
                    } else {
                        it.copyOf(MAX_STRING_BYTES)
                    }
                }

        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(
        input: DataInputStream
    ): String {

        val size = input.readInt()

        require(size in 0..MAX_STRING_BYTES)

        val bytes = ByteArray(size)
        input.readFully(bytes)

        return bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 60_000
        const val MAX_STRING_BYTES = 256 * 1024
    }
}
