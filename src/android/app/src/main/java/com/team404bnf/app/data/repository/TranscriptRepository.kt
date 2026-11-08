package com.team404bnf.app.data.repository

import androidx.room.withTransaction
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import com.team404bnf.app.data.db.AppDatabase
import com.team404bnf.app.data.db.TranscriptSnapshotEntity

import com.team404bnf.app.transcription.ParsedTranscript
import com.team404bnf.app.transcription.TranscriptJsonParser
import com.team404bnf.app.transcription.TranscriptSegment
import com.team404bnf.app.transcription.TranscriptUserEdits

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

import java.util.UUID
import kotlin.math.abs

enum class TranscriptSaveResult {
    SAVED,
    ALREADY_PRESENT
}

data class TranscriptRecoveryState(
    val deletedSegments: List<TranscriptSegment> = emptyList(),
    val hasUserEdits: Boolean = false
)

data class EditableTranscriptWithOrder(
    val transcript: ParsedTranscript,
    val originalPositions: Map<String, Int>,
    val splitBeforeSegmentIds: Set<String> = emptySet()
)

class TranscriptRepository(
    private val database: AppDatabase,
    private val recordingRepository: RecordingRepository
) {

    private val dao = database.transcriptSnapshotDao()

    fun observeEditableWithOrder(
        recordingId: String
    ): Flow<EditableTranscriptWithOrder?> {

        return dao.observeSnapshot(recordingId)
            .map { snapshot ->

                snapshot?.let {

                    val original = TranscriptJsonParser.parse(
                        it.rawJson
                    )

                    val positions = original.segments
                        .mapIndexed { index, segment ->
                            segment.id to index
                        }
                        .toMap()

                    val editsObject = JsonParser.parseString(
                        it.userEditsJson
                    ).asJsonObject

                    TranscriptUserEdits.clean(editsObject, original)
                    val splitBeforeIds = TranscriptUserEdits.splitBeforeIds(editsObject)

                    val editable = TranscriptUserEdits.apply(
                        original,
                        editsObject.toString()
                    )

                    EditableTranscriptWithOrder(
                        transcript = editable,
                        originalPositions = positions,
                        splitBeforeSegmentIds = splitBeforeIds
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

    fun observeRecoveryState(
        recordingId: String
    ): Flow<TranscriptRecoveryState> {

        return dao.observeSnapshot(recordingId)
            .map { snapshot ->

                if (snapshot == null) {
                    TranscriptRecoveryState()
                } else {

                    val original = TranscriptJsonParser.parse(
                        snapshot.rawJson
                    )

                    val edits = JsonParser.parseString(
                        snapshot.userEditsJson
                    ).asJsonObject

                    TranscriptUserEdits.clean(edits, original)

                    val deletedSegments = TranscriptUserEdits.deletedSegments(edits, original)

                    val hasEdits = TranscriptUserEdits.hasUserEdits(
                        edits,
                        original
                    )

                    TranscriptRecoveryState(
                        deletedSegments = deletedSegments,
                        hasUserEdits = hasEdits
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

    private suspend fun updateEdits(
        recordingId: String,
        change: (JsonObject, ParsedTranscript) -> Unit
    ) {
        withContext(Dispatchers.IO) {

            database.withTransaction {

                val snapshot = dao.get(recordingId)
                    ?: error("Расшифровка не найдена")

                val original = TranscriptJsonParser.parse(
                    snapshot.rawJson
                )

                val edits = JsonParser.parseString(
                    snapshot.userEditsJson
                ).asJsonObject

                TranscriptUserEdits.clean(edits, original)

                change(edits, original)

                TranscriptUserEdits.clean(edits, original)

                dao.updateUserEdits(
                    recordingId,
                    edits.toString()
                )
            }
        }
    }

    suspend fun updateSegmentEdits(
        recordingId: String,
        segmentId: String,
        text: String,
        speakerId: String?
    ) {
        updateEdits(recordingId) { edits, original ->
            TranscriptUserEdits.updateSegment(edits, original, segmentId, text, speakerId)
        }
    }

    suspend fun updateSegmentSpeaker(recordingId: String, segmentId: String, speakerId: String?) {
        updateEdits(recordingId) { edits, original ->
            TranscriptUserEdits.changeSpeakers(edits, original, listOf(segmentId), speakerId)
        }
    }

    suspend fun renameSpeaker(
        recordingId: String,
        speakerId: String,
        newName: String
    ) {
        val normalized = newName.trim()

        require(normalized.isNotEmpty()) {
            "Имя участника не может быть пустым"
        }

        require(normalized.length <= 60) {
            "Имя слишком длинное"
        }

        updateEdits(recordingId) { edits, original ->

            val speakerExists =
                speakerId in original.speakerLabels ||
                    original.segments.any {
                        it.speakerId == speakerId
                    }

            require(speakerExists) {
                "Участник не найден"
            }

            val names = TranscriptUserEdits.section(
                edits,
                "names"
            )

            names.addProperty(speakerId, normalized)
        }
    }

    suspend fun deleteSegment(recordingId: String, segmentId: String) {
        updateEdits(recordingId) { edits, original ->
            TranscriptUserEdits.hideSegments(edits, original, listOf(segmentId))
        }
    }

    suspend fun restoreDeletedSegment(recordingId: String, segmentId: String) {
        updateEdits(recordingId) { edits, original -> TranscriptUserEdits.restoreSegment(edits, original, segmentId) }
    }

    suspend fun resetAllUserEdits(recordingId: String) {
        updateEdits(recordingId) { edits, _ -> TranscriptUserEdits.reset(edits) }
    }

    suspend fun changeGroupSpeaker(recordingId: String, segmentIds: List<String>, speakerId: String?) {
        updateEdits(recordingId) { edits, original -> TranscriptUserEdits.changeGroupSpeaker(edits, original, segmentIds, speakerId) }
    }

    suspend fun deleteGroup(recordingId: String, segmentIds: List<String>) {
        updateEdits(recordingId) { edits, original -> TranscriptUserEdits.hideGroup(edits, original, segmentIds) }
    }

    suspend fun splitGroup(recordingId: String, segmentIds: List<String>) {
        updateEdits(recordingId) { edits, original -> TranscriptUserEdits.splitGroup(edits, original, segmentIds) }
    }

    suspend fun allowAutoMergeBefore(recordingId: String, segmentId: String) {
        updateEdits(recordingId) { edits, original -> TranscriptUserEdits.restoreMerge(edits, original, segmentId) }
    }

    suspend fun getStoredTranscriptBytes(
        recordingId: String
    ): Long? = withContext(Dispatchers.IO) {

        val snapshot = dao.get(recordingId)
            ?: return@withContext null

        val originalBytes = snapshot.rawJson
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()

        val editsBytes = snapshot.userEditsJson
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()

        originalBytes + editsBytes
    }

    suspend fun save(
        recordingId: String,
        rawJson: String
    ): TranscriptSaveResult {

        val validId = runCatching {
            UUID.fromString(recordingId).toString() == recordingId
        }.getOrDefault(false)

        require(validId) {
            "Некорректный идентификатор записи"
        }

        // Разбор JSON выполняется вне главного потока.
        val parsed = withContext(Dispatchers.Default) {
            TranscriptJsonParser.parse(rawJson)
        }

        // Проверяем существование аудиозаписи.
        val recording = recordingRepository
            .getPlayableRecording(recordingId)
            ?: throw IllegalArgumentException(
                "Аудиозапись отсутствует или недоступна"
            )

        // Проверяем приблизительное соответствие
        // длительности JSON и аудиофайла.
        val allowedDifference = maxOf(
            2_000L,
            recording.durationMs / 20L
        )

        val difference = abs(
            recording.durationMs - parsed.durationMs
        )

        require(difference <= allowedDifference) {
            "Длительность расшифровки не соответствует аудио"
        }

        val entity = TranscriptSnapshotEntity(
            recordingId = recordingId,
            sourceId = parsed.sourceId,
            resultId = parsed.resultId,
            createdAtEpochMs = System.currentTimeMillis(),
            rawJson = rawJson
        )

        return withContext(Dispatchers.IO) {

            val inserted = dao.insertIfAbsent(entity)

            if (inserted != -1L) {
                TranscriptSaveResult.SAVED
            } else {

                val existing = dao.get(recordingId)

                if (existing?.rawJson == rawJson) {
                    TranscriptSaveResult.ALREADY_PRESENT
                } else {
                    throw IllegalStateException(
                        "У записи уже существует другая расшифровка"
                    )
                }
            }
        }
    }

    suspend fun get(
        recordingId: String
    ): ParsedTranscript? {

        val snapshot = withContext(Dispatchers.IO) {
            dao.get(recordingId)
        } ?: return null

        return withContext(Dispatchers.Default) {
            TranscriptJsonParser.parse(snapshot.rawJson)
        }
    }

    fun observeExists(
        recordingId: String
    ): Flow<Boolean> {
        return dao.observeExists(recordingId)
    }

    suspend fun checkTxtExportOptions(recordingId: String): com.team404bnf.app.transcription.ExportOptions = withContext(Dispatchers.IO) {
        val snapshot = dao.get(recordingId) ?: error("Расшифровка не найдена")
        com.team404bnf.app.transcription.TranscriptTxtFormatter.checkExportOptions(
            TranscriptJsonParser.parse(snapshot.rawJson), snapshot.userEditsJson)
    }

    /** Read one persisted snapshot for the selected version; exporting never updates Room. */
    suspend fun getForTxtExport(recordingId: String, version: com.team404bnf.app.transcription.ExportVersion): ParsedTranscript? =
        withContext(Dispatchers.IO) {
            val snapshot = dao.get(recordingId) ?: return@withContext null
            com.team404bnf.app.transcription.TranscriptTxtFormatter.selectVersion(
                TranscriptJsonParser.parse(snapshot.rawJson), snapshot.userEditsJson, version)
        }

    suspend fun checkExportOptions(
        recordingId: String
    ): com.team404bnf.app.transcription.ExportOptions = withContext(Dispatchers.IO) {
        val snapshot = dao.get(recordingId)
            ?: throw IllegalStateException("Расшифровка не найдена")

        val original = TranscriptJsonParser.parse(snapshot.rawJson)

        com.team404bnf.app.transcription.TranscriptJsonExporter.checkExportOptions(
            original = original,
            userEditsJson = snapshot.userEditsJson
        )
    }

    suspend fun exportTranscriptJson(
        recordingId: String,
        version: com.team404bnf.app.transcription.ExportVersion = com.team404bnf.app.transcription.ExportVersion.CORRECTED
    ): String? = withContext(Dispatchers.IO) {
        val snapshot = dao.get(recordingId) ?: return@withContext null
        val original = TranscriptJsonParser.parse(snapshot.rawJson)
        com.team404bnf.app.transcription.TranscriptJsonExporter.export(
            original = original,
            userEditsJson = snapshot.userEditsJson,
            version = version
        )
    }

}
