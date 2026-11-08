package com.team404bnf.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionTaskDao {
    @Query("SELECT * FROM transcription_tasks WHERE recordingId = :recordingId")
    suspend fun getTask(recordingId: String): TranscriptionTaskEntity?

    @Query("SELECT * FROM transcription_tasks WHERE recordingId = :recordingId")
    fun observeTask(recordingId: String): Flow<TranscriptionTaskEntity?>

    @Query("SELECT * FROM transcription_tasks")
    fun observeAllTasks(): Flow<List<TranscriptionTaskEntity>>

    @Query("SELECT * FROM transcription_tasks")
    suspend fun getAllTasks(): List<TranscriptionTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TranscriptionTaskEntity)

    @Query("DELETE FROM transcription_tasks WHERE recordingId = :recordingId")
    suspend fun deleteTask(recordingId: String)
}
