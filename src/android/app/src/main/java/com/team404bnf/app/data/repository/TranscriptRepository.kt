package com.team404bnf.app.data.repository

import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonNull
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

    fun observeEditable(
        recordingId: String
    ): Flow<ParsedTranscript?> {

        return dao.observeSnapshot(recordingId)
            .map { snapshot ->

                snapshot?.let {
                    val original = TranscriptJsonParser.parse(
                        it.rawJson
                    )

                    TranscriptUserEdits.apply(
                        original,
                        it.userEditsJson
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

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

                    val splitBeforeIds = editsObject
                        .get("splitBeforeSegmentIds")
                        ?.takeIf { element -> element.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { element ->
                            if (
                                element.isJsonPrimitive &&
                                element.asJsonPrimitive.isString
                            ) {
                                element.asString
                            } else {
                                null
                            }
                        }
                        ?.toSet()
                        ?: emptySet()

                    val editable = TranscriptUserEdits.apply(
                        original,
                        it.userEditsJson
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

                    val hiddenIds = edits
                        .get("hiddenSegments")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { element ->
                            if (
                                element.isJsonPrimitive &&
                                element.asJsonPrimitive.isString
                            ) {
                                element.asString
                            } else {
                                null
                            }
                        }
                        ?.toSet()
                        ?: emptySet()

                    val deletedSegments =
                        if (hiddenIds.isEmpty()) {
                            emptyList()
                        } else {
                            original.segments.filter {
                                it.id in hiddenIds
                            }
                        }

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

    suspend fun updateSegmentText(
        recordingId: String,
        segmentId: String,
        text: String
    ) {
        val normalized = text.trim()

        require(normalized.isNotEmpty()) {
            "Текст реплики не может быть пустым"
        }

        require(normalized.length <= 5000) {
            "Текст слишком длинный"
        }

        updateEdits(recordingId) { edits, original ->

            val segment = original.segments
                .firstOrNull { it.id == segmentId }
                ?: error("Реплика не найдена")

            val texts = TranscriptUserEdits.section(
                edits,
                "texts"
            )

            if (normalized == segment.text) {
                texts.remove(segmentId)
            } else {
                texts.addProperty(segmentId, normalized)
            }
        }
    }

    suspend fun updateSegmentSpeaker(
        recordingId: String,
        segmentId: String,
        speakerId: String?
    ) {
        updateEdits(recordingId) { edits, original ->

            val segment = original.segments
                .firstOrNull { it.id == segmentId }
                ?: error("Реплика не найдена")

            val knownSpeakers = (
                original.speakerLabels.keys +
                    original.segments.mapNotNull { it.speakerId }
            ).toSet()

            require(speakerId == null || speakerId in knownSpeakers) {
                "Участник не найден"
            }

            val speakers = TranscriptUserEdits.section(
                edits,
                "speakers"
            )

            if (speakerId == segment.speakerId) {
                speakers.remove(segmentId)
            } else if (speakerId == null) {
                speakers.add(segmentId, JsonNull.INSTANCE)
            } else {
                speakers.addProperty(segmentId, speakerId)
            }
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

    suspend fun deleteSegment(
        recordingId: String,
        segmentId: String
    ) {
        updateEdits(recordingId) { edits, original ->

            require(original.segments.any { it.id == segmentId }) {
                "Реплика не найдена"
            }

            val hidden = edits.getAsJsonArray("hiddenSegments")
                ?: JsonArray().also {
                    edits.add("hiddenSegments", it)
                }

            if (hidden.none { it.asString == segmentId }) {
                hidden.add(segmentId)
            }
        }
    }

    suspend fun restoreDeletedSegment(
        recordingId: String,
        segmentId: String
    ) {
        updateEdits(recordingId) { edits, original ->

            require(
                original.segments.any { it.id == segmentId }
            ) {
                "Реплика не найдена"
            }

            val existing = edits.get("hiddenSegments")

            if (existing != null && existing.isJsonArray) {

                val remaining = JsonArray()

                existing.asJsonArray.forEach { element ->

                    val isTarget =
                        element.isJsonPrimitive &&
                            element.asJsonPrimitive.isString &&
                            element.asString == segmentId

                    if (!isTarget) {
                        remaining.add(element)
                    }
                }

                if (remaining.size() == 0) {
                    edits.remove("hiddenSegments")
                } else {
                    edits.add("hiddenSegments", remaining)
                }
            }
        }
    }

    suspend fun resetAllUserEdits(
        recordingId: String
    ) {
        updateEdits(recordingId) { edits, _ ->

            edits.keySet()
                .toList()
                .forEach { key ->
                    edits.remove(key)
                }
        }
    }

    suspend fun changeGroupSpeaker(
        recordingId: String,
        segmentIds: List<String>,
        speakerId: String?
    ) {
        updateEdits(recordingId) { edits, original ->

            validateOriginalGroup(segmentIds, original)

            val knownSpeakers = (
                original.speakerLabels.keys +
                    original.segments.mapNotNull {
                        it.speakerId
                    }
            ).toSet()

            require(
                speakerId == null ||
                    speakerId in knownSpeakers
            ) {
                "Участник не найден"
            }

            val speakers = TranscriptUserEdits.section(
                edits,
                "speakers"
            )

            val originalById = original.segments
                .associateBy { it.id }

            segmentIds.forEach { id ->

                val originalSpeaker = originalById[id]?.speakerId

                when {
                    speakerId == originalSpeaker ->
                        speakers.remove(id)

                    speakerId == null ->
                        speakers.add(id, JsonNull.INSTANCE)

                    else ->
                        speakers.addProperty(id, speakerId)
                }
            }

            if (speakers.size() == 0) {
                edits.remove("speakers")
            }
        }
    }

    suspend fun deleteGroup(
        recordingId: String,
        segmentIds: List<String>
    ) {
        updateEdits(recordingId) { edits, original ->

            validateOriginalGroup(segmentIds, original)

            val hidden = edits
                .get("hiddenSegments")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: JsonArray()

            val existingIds = hidden
                .mapNotNull { element ->
                    if (
                        element.isJsonPrimitive &&
                        element.asJsonPrimitive.isString
                    ) {
                        element.asString
                    } else {
                        null
                    }
                }
                .toMutableSet()

            segmentIds.forEach { id ->
                if (existingIds.add(id)) {
                    hidden.add(id)
                }
            }

            edits.add("hiddenSegments", hidden)
        }
    }

    suspend fun splitGroup(
        recordingId: String,
        segmentIds: List<String>
    ) {
        updateEdits(recordingId) { edits, original ->

            validateOriginalGroup(segmentIds, original)

            val boundaries = edits
                .get("splitBeforeSegmentIds")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: JsonArray()

            val existingIds = boundaries
                .mapNotNull { element ->
                    if (
                        element.isJsonPrimitive &&
                        element.asJsonPrimitive.isString
                    ) {
                        element.asString
                    } else {
                        null
                    }
                }
                .toMutableSet()

            segmentIds.drop(1).forEach { id ->
                if (existingIds.add(id)) {
                    boundaries.add(id)
                }
            }

            edits.add("splitBeforeSegmentIds", boundaries)
        }
    }

    suspend fun allowAutoMergeBefore(
        recordingId: String,
        segmentId: String
    ) {
        updateEdits(recordingId) { edits, original ->

            require(
                original.segments.any { it.id == segmentId }
            ) {
                "Реплика не найдена"
            }

            val boundaries = edits
                .get("splitBeforeSegmentIds")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return@updateEdits

            val remaining = JsonArray()

            boundaries.forEach { element ->
                if (
                    !element.isJsonPrimitive ||
                    !element.asJsonPrimitive.isString ||
                    element.asString != segmentId
                ) {
                    remaining.add(element)
                }
            }

            if (remaining.size() == 0) {
                edits.remove("splitBeforeSegmentIds")
            } else {
                edits.add("splitBeforeSegmentIds", remaining)
            }
        }
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

    suspend fun validateAndCheckExportDifferences(
        recordingId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val options = checkExportOptions(recordingId)
        options.showVersionSelection
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

    suspend fun hasExportableJsonDifferences(
        recordingId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val snapshot = dao.get(recordingId) ?: return@withContext false
        val original = TranscriptJsonParser.parse(snapshot.rawJson)
        com.team404bnf.app.transcription.TranscriptJsonExporter.hasExportableDifferences(
            original = original,
            userEditsJson = snapshot.userEditsJson
        )
    }

    private fun validateOriginalGroup(
        segmentIds: List<String>,
        original: ParsedTranscript
    ) {
        require(
            segmentIds.size >= 2 &&
                segmentIds.distinct().size == segmentIds.size
        ) {
            "Некорректная группа реплик"
        }

        val positions = original.segments
            .mapIndexed { index, segment ->
                segment.id to index
            }
            .toMap()

        val indices = segmentIds.map { id ->
            positions[id]
                ?: throw IllegalArgumentException(
                    "Реплика не найдена"
                )
        }

        require(
            indices.zipWithNext().all { (first, second) ->
                second == first + 1
            }
        ) {
            "Фрагменты не являются соседними"
        }
    }
}