package com.team404bnf.app.transcription

/** Результат модели; времена в миллисекундах, исходный JSON не изменяется. */
data class ParsedTranscript(
    val schemaVersion: Int?,        // null для JSON до введения schema_version
    val sourceId: String,           // id модели, НЕ UUID записи Android
    val resultId: String?,
    val audioSha256: String?,
    val sourceAudioPath: String?,   // путь в Python, НЕ локальный путь Android
    val durationMs: Long,
    val asrModel: String?,
    val diarizationModel: String?,
    val pipelineVersion: Int?,
    val timingPrecision: String?,
    val asrChunking: String?,
    val fullText: String,
    val segments: List<TranscriptSegment>,
    val words: List<TranscriptWord>,
    val chunks: List<TranscriptChunk>,
    val speakerLabels: Map<String, String>,
    val speakersDetected: Int?,
    val wordsUnassigned: Int?,
    val wordsAmbiguous: Int?,
    val diagnostics: Map<String, Double>,
    val rawJson: String             // для будущего сохранения оригинала без потерь
)

data class TranscriptSegment(
    val id: String,
    val startMs: Long?,
    val endMs: Long?,
    val speakerId: String?,
    val speakerLabel: String?,
    val text: String,
    val overlap: Boolean?,
    val speakerAmbiguous: Boolean?
)

data class TranscriptWord(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val speakerId: String?,
    val speakerLabel: String?,
    val text: String,
    val timingSource: String?,
    val overlap: Boolean?,
    val speakerAmbiguous: Boolean?
)

data class TranscriptChunk(
    val startMs: Long,
    val endMs: Long,
    val wordCount: Int?
)

class TranscriptFormatException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)