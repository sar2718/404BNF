package com.team404bnf.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Аудиозапись
@Entity(tableName = "recordings")
data class RecordingEntity(

    @PrimaryKey
    val id: String,

    val title: String,

    val createdAtEpochMs: Long,

    val durationMs: Long = 0,

    val sourceType: String = "MIC",

    val audioRelativePath: String? = null,

    val processingStatus: String = "CREATED"
)


// Участник разговора
@Entity(
    tableName = "speakers",

    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],

    indices = [
        Index("recordingId")
    ]
)
data class SpeakerEntity(

    @PrimaryKey
    val id: String,

    val recordingId: String,

    val displayName: String,

    val colorIndex: Int
)


// Реплика разговора
@Entity(
    tableName = "utterances",

    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        ),

        ForeignKey(
            entity = SpeakerEntity::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],

    indices = [
        Index(value = ["recordingId", "startMs"]),
        Index("speakerId")
    ]
)
data class UtteranceEntity(

    @PrimaryKey
    val id: String,

    val recordingId: String,

    val speakerId: String?,

    val startMs: Long,

    val endMs: Long,

    val text: String,

    val sortOrder: Int = 0,

    val isTextEdited: Boolean = false,

    val isSpeakerEdited: Boolean = false
)