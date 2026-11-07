package com.team404bnf.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSnapshotDao {

    @Query(
        """
        SELECT *
        FROM transcript_snapshots
        WHERE recordingId = :recordingId
        LIMIT 1
        """
    )
    suspend fun get(
        recordingId: String
    ): TranscriptSnapshotEntity?

    @Query(
        "SELECT * FROM transcript_snapshots WHERE recordingId = :recordingId LIMIT 1"
    )
    fun observeSnapshot(
        recordingId: String
    ): Flow<TranscriptSnapshotEntity?>

    @Insert(
        onConflict = OnConflictStrategy.IGNORE
    )
    suspend fun insertIfAbsent(
        entity: TranscriptSnapshotEntity
    ): Long

    @Query(
        """
        UPDATE transcript_snapshots
        SET userEditsJson = :editsJson
        WHERE recordingId = :recordingId
        """
    )
    suspend fun updateUserEdits(
        recordingId: String,
        editsJson: String
    )

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM transcript_snapshots
            WHERE recordingId = :recordingId
        )
        """
    )
    fun observeExists(
        recordingId: String
    ): Flow<Boolean>
}