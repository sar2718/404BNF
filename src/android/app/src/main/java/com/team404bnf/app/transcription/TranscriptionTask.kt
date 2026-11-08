package com.team404bnf.app.transcription

import com.team404bnf.app.data.db.TranscriptionTaskEntity
import java.util.UUID

enum class TranscriptionTaskStatus {
    WAITING_FOR_MODELS,
    QUEUED,
    PREPARING,
    RUNNING_ASR,
    RUNNING_DIARIZATION,
    SAVING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

data class TranscriptionTask(
    val recordingId: String,
    val status: TranscriptionTaskStatus,
    val stageMessage: String = "",
    val errorMessage: String? = null,
    val attemptId: String = UUID.randomUUID().toString(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val isProcessing: Boolean
        get() = isActive && status != TranscriptionTaskStatus.QUEUED

    val isActive: Boolean
        get() = status in setOf(
            TranscriptionTaskStatus.QUEUED,
            TranscriptionTaskStatus.PREPARING,
            TranscriptionTaskStatus.RUNNING_ASR,
            TranscriptionTaskStatus.RUNNING_DIARIZATION,
            TranscriptionTaskStatus.SAVING
        )

    val isFinished: Boolean
        get() = status in setOf(
            TranscriptionTaskStatus.COMPLETED,
            TranscriptionTaskStatus.FAILED,
            TranscriptionTaskStatus.CANCELLED,
            TranscriptionTaskStatus.INTERRUPTED
        )

    fun toEntity(): TranscriptionTaskEntity {
        return TranscriptionTaskEntity(
            recordingId = recordingId,
            status = status.name,
            stageMessage = stageMessage,
            errorMessage = errorMessage,
            attemptId = attemptId,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }

    companion object {
        fun fromEntityOrNull(entity: TranscriptionTaskEntity?): TranscriptionTask? =
            entity?.let(::fromEntity)

        fun fromEntity(entity: TranscriptionTaskEntity): TranscriptionTask {
            val taskStatus = runCatching {
                TranscriptionTaskStatus.valueOf(entity.status)
            }.getOrDefault(TranscriptionTaskStatus.FAILED)

            return TranscriptionTask(
                recordingId = entity.recordingId,
                status = taskStatus,
                stageMessage = entity.stageMessage,
                errorMessage = entity.errorMessage,
                attemptId = entity.attemptId,
                createdAtEpochMs = entity.createdAtEpochMs,
                updatedAtEpochMs = entity.updatedAtEpochMs
            )
        }
    }
}
