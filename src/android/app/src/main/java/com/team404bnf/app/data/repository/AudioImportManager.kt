package com.team404bnf.app.data.repository

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

data class ImportedAudio(
    val relativePath: String,
    val durationMs: Long
)

class AudioImportManager(
    private val context: Context
) {
    companion object {
        val SUPPORTED_EXTENSIONS = setOf(
            "wav", "mp3", "m4a", "aac",
            "flac", "ogg", "opus", "amr", "3gp"
        )

        private const val MAX_BYTES = 1024L * 1024 * 1024
    }

    suspend fun copyFromUri(
        uri: Uri,
        id: String
    ): ImportedAudio = withContext(Dispatchers.IO) {

        require(uri.scheme == "content") {
            "Не поддерживается выбранный источник"
        }

        require(UUID.fromString(id).toString() == id) {
            "Некорректный идентификатор"
        }

        val extension = determineExtension(uri)

        val root = File(
            context.filesDir,
            "recordings"
        ).canonicalFile

        check(root.isDirectory || root.mkdirs()) {
            "Не удалось создать каталог записей"
        }

        val directory = File(root, id).canonicalFile

        check(directory.parentFile == root) {
            "Некорректный путь"
        }

        check(!directory.exists() && directory.mkdirs()) {
            "Не удалось создать папку импорта"
        }

        val temporary = File(
            directory,
            "audio.$extension.part"
        )

        val destination = File(
            directory,
            "audio.$extension"
        )

        try {
            val input = context.contentResolver
                .openInputStream(uri)
                ?: error("Не удалось открыть файл")

            var copied = 0L

            input.use { source ->
                FileOutputStream(temporary).use { output ->

                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = source.read(buffer)

                        if (count == -1) break
                        if (count == 0) continue

                        copied += count

                        check(copied <= MAX_BYTES) {
                            "Файл превышает ограничение 1 ГБ"
                        }

                        output.write(buffer, 0, count)
                    }

                    output.fd.sync()
                }
            }

            check(copied > 0) {
                "Выбранный файл пуст"
            }

            val durationMs = inspectAudio(temporary)

            check(temporary.renameTo(destination)) {
                "Не удалось завершить импорт"
            }

            ImportedAudio(
                relativePath = "recordings/$id/audio.$extension",
                durationMs = durationMs
            )

        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun removeCopiedFile(id: String) {
        val valid = runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)

        if (!valid) return

        val root = File(
            context.filesDir,
            "recordings"
        ).canonicalFile

        val directory = File(root, id).canonicalFile

        if (directory.parentFile == root) {
            directory.deleteRecursively()
        }
    }

    private fun determineExtension(uri: Uri): String {

        val resolver = context.contentResolver

        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->

            val index = cursor.getColumnIndex(
                OpenableColumns.DISPLAY_NAME
            )

            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        if (extension in SUPPORTED_EXTENSIONS) {
            return extension
        }

        val mime = resolver.getType(uri)
            ?.lowercase(Locale.ROOT)

        val detected = when (mime) {
            "audio/wav", "audio/x-wav",
            "audio/wave", "audio/vnd.wave" -> "wav"

            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac", "audio/aacp" -> "aac"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/amr" -> "amr"
            "audio/3gpp" -> "3gp"
            else -> null
        }

        return detected
            ?: throw IllegalArgumentException(
                "Неподдерживаемый формат аудио"
            )
    }

    private fun inspectAudio(file: File): Long {

        val extractor = MediaExtractor()
        var durationMs = 0L
        var hasAudio = false
        var hasVideo = false

        try {
            FileInputStream(file).use { input ->
                extractor.setDataSource(input.fd)

                for (i in 0 until extractor.trackCount) {

                    val format = extractor.getTrackFormat(i)

                    val mime = format.getString(
                        MediaFormat.KEY_MIME
                    ).orEmpty()

                    if (mime.startsWith("video/")) {
                        hasVideo = true
                    }

                    if (mime.startsWith("audio/")) {
                        hasAudio = true

                        if (format.containsKey(
                                MediaFormat.KEY_DURATION
                            )
                        ) {
                            val microseconds = format.getLong(
                                MediaFormat.KEY_DURATION
                            )

                            if (microseconds > 0) {
                                durationMs = maxOf(
                                    durationMs,
                                    microseconds / 1000
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            extractor.release()
        }

        require(hasAudio && !hasVideo) {
            "Файл не является поддерживаемой аудиозаписью"
        }

        if (durationMs <= 0L) {
            val retriever = MediaMetadataRetriever()

            try {
                FileInputStream(file).use { input ->

                    retriever.setDataSource(input.fd)

                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                }
            } finally {
                retriever.release()
            }
        }

        require(durationMs > 0L) {
            "Не удалось определить длительность аудио"
        }

        return durationMs
    }
}