package com.team404bnf.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcription_tasks")
data class TranscriptionTaskEntity(
    @PrimaryKey val recordingId: String,
    val status: String,
    val stageMessage: String = "",
    val errorMessage: String? = null,
    val attemptId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
