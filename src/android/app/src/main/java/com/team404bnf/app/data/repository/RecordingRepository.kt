package com.team404bnf.app.data.repository

import com.team404bnf.app.data.db.RecordingDao
import com.team404bnf.app.data.db.RecordingEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

data class PlayableRecording(
    val id: String,
    val title: String,
    val durationMs: Long,
    val file: File
)

data class RecordingDetails(
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sourceType: String,
    val audioExtension: String?,
    val audioBytes: Long?,
    val recordingFilesBytes: Long
)

class RecordingRepository(
    private val dao: RecordingDao,
    private val context: Context
) {

    fun observeRecordings(): Flow<List<RecordingEntity>> {
        return dao.observeRecordings()
    }

    suspend fun saveAudioRecording(
        id: String,
        createdAtEpochMs: Long,
        durationMs: Long,
        relativePath: String
    ) {
        val formatter = SimpleDateFormat(
            "dd.MM, HH:mm",
            Locale.getDefault()
        )

        val recording = RecordingEntity(
            id = id,
            title = "Запись " + formatter.format(
                Date(createdAtEpochMs)
            ),
            createdAtEpochMs = createdAtEpochMs,
            durationMs = durationMs,
            sourceType = "MIC",
            audioRelativePath = relativePath,
            processingStatus = "AUDIO_ONLY"
        )

        dao.insertRecording(recording)
    }

    suspend fun importAudio(uri: Uri): String = withContext(Dispatchers.IO) {

        val id = UUID.randomUUID().toString()
        val manager = AudioImportManager(context)

        val imported = manager.copyFromUri(uri, id)

        val createdAt = System.currentTimeMillis()

        val title = "Запись " + SimpleDateFormat(
            "dd.MM, HH:mm",
            Locale.getDefault()
        ).format(Date(createdAt))

        try {
            dao.insertRecording(
                RecordingEntity(
                    id = id,
                    title = title,
                    createdAtEpochMs = createdAt,
                    durationMs = imported.durationMs,
                    sourceType = "IMPORT",
                    audioRelativePath = imported.relativePath,
                    processingStatus = "AUDIO_ONLY"
                )
            )
        } catch (error: Throwable) {
            manager.removeCopiedFile(id)
            throw error
        }

        // Schedule only after the validated file and its Room row exist. ML failures keep the audio.
        com.team404bnf.app.models.RecognitionModels.get(context).launchTranscription(id)
        id
    }

    suspend fun renameRecording(
        recordingId: String,
        newTitle: String
    ) = withContext(Dispatchers.IO) {

        val validId = runCatching {
            java.util.UUID.fromString(recordingId).toString() ==
                recordingId
        }.getOrDefault(false)

        require(validId) {
            "Некорректный идентификатор записи"
        }

        val normalized = newTitle.trim()

        require(normalized.isNotEmpty()) {
            "Название не может быть пустым"
        }

        require(normalized.length <= 80) {
            "Название слишком длинное"
        }

        val updated = dao.renameRecording(
            recordingId = recordingId,
            newTitle = normalized
        )

        require(updated == 1) {
            "Запись не найдена"
        }
    }

    suspend fun getRecordingDetails(
        recordingId: String
    ): RecordingDetails? = withContext(Dispatchers.IO) {

        val validId = runCatching {
            java.util.UUID.fromString(recordingId).toString() ==
                recordingId
        }.getOrDefault(false)

        if (!validId) {
            return@withContext null
        }

        val recording = dao.getRecordingById(recordingId)
            ?: return@withContext null

        val playable = getPlayableRecording(recordingId)

        val root = File(
            context.filesDir,
            "recordings"
        ).canonicalFile

        val directory = File(
            root,
            recordingId
        ).canonicalFile

        val directoryIsValid =
            directory.parentFile == root &&
                directory.isDirectory

        val filesBytes = if (directoryIsValid) {
            directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } else {
            0L
        }

        RecordingDetails(
            createdAtEpochMs = recording.createdAtEpochMs,
            durationMs = recording.durationMs,
            sourceType = recording.sourceType,
            audioExtension = playable?.file?.extension,
            audioBytes = playable?.file?.length(),
            recordingFilesBytes = filesBytes
        )
    }

    suspend fun getPlayableRecording(
        id: String
    ): PlayableRecording? = withContext(Dispatchers.IO) {

        val validId = runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)

        if (!validId) return@withContext null

        val recording = dao.getRecordingById(id)
            ?: return@withContext null

        val relativePath = recording.audioRelativePath
            ?: return@withContext null

        val prefix = "recordings/$id/audio."

        if (!relativePath.startsWith(prefix)) {
            return@withContext null
        }

        val extension = relativePath.removePrefix(prefix)

        if (extension !in AudioImportManager.SUPPORTED_EXTENSIONS) {
            return@withContext null
        }

        val root = File(
            context.filesDir,
            "recordings"
        ).canonicalFile

        val directory = File(root, id).canonicalFile

        if (directory.parentFile != root) {
            return@withContext null
        }

        val audioFile = File(
            directory,
            "audio.$extension"
        ).canonicalFile

        if (
            audioFile.parentFile != directory ||
            !audioFile.isFile ||
            !audioFile.canRead()
        ) {
            return@withContext null
        }

        PlayableRecording(
            id = recording.id,
            title = recording.title,
            durationMs = recording.durationMs,
            file = audioFile
        )
    }

    suspend fun deleteRecordings(ids: Set<String>) =
        withContext(Dispatchers.IO) {

            val root = File(
                context.filesDir,
                "recordings"
            ).canonicalFile

            for (id in ids) {

                // Защита от некорректных идентификаторов
                require(
                    runCatching {
                        UUID.fromString(id).toString() == id
                    }.getOrDefault(false)
                ) {
                    "Некорректный ID записи"
                }

                val recording = dao.getRecordingById(id)
                    ?: continue

                check(
                    recording.processingStatus !in setOf(
                        "RECORDING",
                        "PAUSED",
                        "FINISHING_AUDIO",
                        "PROCESSING"
                    )
                ) {
                    "Активную запись удалять нельзя"
                }

                val directory = File(root, id).canonicalFile

                check(directory.parentFile == root) {
                    "Недопустимый путь к аудиофайлу"
                }

                com.team404bnf.app.transcription.TranscriptionTaskManager.deleteIfInactive(id) {
                    // Удаляем аудио
                    if (directory.exists()) {
                        check(directory.deleteRecursively()) {
                            "Не удалось удалить аудиофайлы"
                        }
                    }

                    // Удаляем запись и связанные данные Room
                    dao.deleteRecordingById(id)
                }
            }
        }
}
