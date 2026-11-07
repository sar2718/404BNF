package com.team404bnf.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TranscriptSnapshotEntity(
    @PrimaryKey
    val recordingId: String,

    val sourceId: String,
    val resultId: String?,
    val createdAtEpochMs: Long,

    // Оригинальный JSON модели без изменений.
    val rawJson: String,

    @ColumnInfo(defaultValue = "'{}'")
    val userEditsJson: String = "{}"
)