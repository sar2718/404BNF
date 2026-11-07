package com.team404bnf.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    // Наблюдение за всеми записями
    @Query(
        "SELECT * FROM recordings ORDER BY createdAtEpochMs DESC"
    )
    fun observeRecordings(): Flow<List<RecordingEntity>>

    // Получить запись по ID
    @Query(
        "SELECT * FROM recordings WHERE id = :id LIMIT 1"
    )
    suspend fun getRecordingById(
        id: String
    ): RecordingEntity?

    // Добавить новую запись
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecording(
        recording: RecordingEntity
    )

    // Переименовать запись
    @Query(
        """
        UPDATE recordings
        SET title = :newTitle
        WHERE id = :recordingId
        """
    )
    suspend fun renameRecording(
        recordingId: String,
        newTitle: String
    ): Int

    // Удалить запись из базы
    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(
        id: String
    ): Int
}