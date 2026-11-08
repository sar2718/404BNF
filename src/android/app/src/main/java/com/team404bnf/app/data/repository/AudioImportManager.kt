package com.team404bnf.app.data.repository

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID

internal class AudioImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

private const val UNSUPPORTED_AUDIO = "Неподдерживаемый формат. Выберите аудиофайл."
private const val UNREADABLE_AUDIO = "Не удалось прочитать аудиофайл."

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

        // Snapshot the preference once. A changed setting applies to the next import.
        val limit = ImportSettings(context).currentLimit()
        val extensionHint = determineExtension(uri)

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
            "audio.part"
        )

        try {
            val input = context.contentResolver
                .openInputStream(uri)
                ?: error("Не удалось открыть файл")

            val copied = input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val count = copyImportStream(source, output, limit)
                    output.fd.sync()
                    count
                }
            }

            if (copied == 0L) throw AudioImportException(UNREADABLE_AUDIO)

            validateWavBounds(temporary)
            val (extension, durationMs) = inspectAudio(temporary, extensionHint)
            currentCoroutineContext().ensureActive()
            val destination = File(directory, "audio.$extension")

            check(temporary.renameTo(destination)) {
                "Не удалось завершить импорт"
            }

            ImportedAudio(
                relativePath = "recordings/$id/audio.$extension",
                durationMs = durationMs
            )

        } catch (error: Throwable) {
            directory.deleteRecursively()
            when (error) {
                is CancellationException -> throw error
                is AudioImportException -> throw error
                is Exception -> throw AudioImportException(UNREADABLE_AUDIO, error)
                else -> throw error
            }
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

    private fun determineExtension(uri: Uri): String? {

        val resolver = context.contentResolver

        // Provider metadata is only a hint; failure here must not veto readable audio.
        val displayName = runCatching { resolver.query(
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
        } }.getOrNull()

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        if (extension in SUPPORTED_EXTENSIONS) {
            return extension
        }

        return extensionForMime(runCatching { resolver.getType(uri) }.getOrNull())
    }

    private fun extensionForMime(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
            "audio/wav", "audio/x-wav",
            "audio/wave", "audio/vnd.wave" -> "wav"

            "audio/mpeg", "audio/x-mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac", "audio/aacp", "audio/mp4a-latm" -> "aac"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "audio/x-ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/amr", "audio/amr-wb" -> "amr"
            "audio/3gpp", "audio/3gpp2" -> "3gp"
            else -> null
        }

    private suspend fun inspectAudio(file: File, extensionHint: String?): Pair<String, Long> {

        val extractor = MediaExtractor()
        var durationMs = 0L
        var hasAudio = false
        var hasVideo = false
        val audioTracks = mutableListOf<Int>()

        try {
            FileInputStream(file).use { input ->
                try {
                    extractor.setDataSource(input.fd)
                } catch (error: Exception) {
                    throw AudioImportException(
                        if (extensionHint == null) UNSUPPORTED_AUDIO else UNREADABLE_AUDIO, error
                    )
                }

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
                        audioTracks += i

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
                if (!hasAudio || hasVideo) throw AudioImportException(UNSUPPORTED_AUDIO)
                for (track in audioTracks) validateAudioTrack(extractor, track)
            }
        } finally {
            extractor.release()
        }

        var detectedExtension: String? = null
        run {
            val retriever = MediaMetadataRetriever()

            try {
                FileInputStream(file).use { input ->

                    retriever.setDataSource(input.fd)

                    val containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    detectedExtension = when (containerMime?.lowercase(Locale.ROOT)) {
                        "video/mp4", "application/mp4" -> "m4a" // Video tracks were rejected above.
                        "video/3gpp" -> "3gp"
                        else -> extensionForMime(containerMime)
                    }
                    if (detectedExtension == null && containerMime != null) {
                        throw AudioImportException(UNSUPPORTED_AUDIO)
                    }
                    if (durationMs <= 0L) durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                }
            } catch (error: AudioImportException) {
                throw error
            } catch (error: Exception) {
                // Some extractors support audio that the metadata retriever cannot open.
                if (durationMs <= 0L || extensionHint == null) throw error
            } finally {
                retriever.release()
            }
        }

        if (durationMs <= 0L) throw AudioImportException(UNREADABLE_AUDIO)
        val extension = detectedExtension ?: extensionHint
            ?: throw AudioImportException(UNSUPPORTED_AUDIO)
        return extension to durationMs
    }

    private suspend fun validateWavBounds(file: File) {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12L || input.readInt() != 0x52494646) return
            val riffEnd = (Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL) + 8L
            if (input.readInt() != 0x57415645) return
            if (riffEnd < 12L || riffEnd > input.length()) throw AudioImportException(UNREADABLE_AUDIO)
            while (input.filePointer < riffEnd) {
                currentCoroutineContext().ensureActive()
                if (riffEnd - input.filePointer < 8L) throw AudioImportException(UNREADABLE_AUDIO)
                input.readInt() // Chunk identifier.
                val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val end = input.filePointer + size
                if (end > riffEnd) throw AudioImportException(UNREADABLE_AUDIO)
                // RIFF chunks are word aligned; tolerate an absent pad after the final chunk.
                input.seek(if (end == riffEnd) end else end + (size and 1L))
            }
        }
    }

    /** Read and decode the complete track; metadata alone can describe an empty/broken payload. */
    private suspend fun validateAudioTrack(extractor: MediaExtractor, track: Int) {
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw AudioImportException(UNSUPPORTED_AUDIO)
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            throw AudioImportException(UNSUPPORTED_AUDIO, error)
        }
        var started = false
        try {
            extractor.selectTrack(track)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedBytes = 0L
            var lastProgress = System.nanoTime()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                            ?: throw AudioImportException(UNREADABLE_AUDIO)
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (size == 0 || size > buffer.capacity() ||
                                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                                throw AudioImportException(UNREADABLE_AUDIO)
                            }
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    decodedBytes += info.size
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    lastProgress = System.nanoTime()
                }
                if (System.nanoTime() - lastProgress > 30_000_000_000L) {
                    throw AudioImportException(UNREADABLE_AUDIO)
                }
            }
            if (decodedBytes == 0L) throw AudioImportException(UNREADABLE_AUDIO)
        } finally {
            try {
                if (started) decoder.stop()
            } finally {
                decoder.release()
                extractor.unselectTrack(track)
            }
        }
    }

}

/** Enforce the actual byte count, independently of provider metadata or InputStream.available(). */
internal suspend fun copyImportStream(source: InputStream, output: OutputStream, limit: ImportFileLimit): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = source.read(buffer)
        if (count == -1) break
        if (count == 0) continue
        if (!limit.isUnlimited && count.toLong() > limit.bytes - copied) throw AudioImportException(
            "Файл превышает ограничение ${limit.label}"
        )
        output.write(buffer, 0, count)
        copied += count
    }
    return copied
}
