package com.team404bnf.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {

    @Test
    fun migrateFromVersion3PreservesDataAndUsesTextAttemptId() = runBlocking {
        verifyMigration(3)
    }

    @Test
    fun migrateFromVersion4PreservesDataAndUsesTextAttemptId() = runBlocking {
        verifyMigration(4)
    }

    private suspend fun verifyMigration(oldVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-$oldVersion-${UUID.randomUUID()}.db"
        createLegacyDatabase(context, name, oldVersion)

        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        val recording = database.recordingDao().getRecordingById(RECORDING_ID)
        assertNotNull(recording)
        assertEquals("Migration fixture", recording?.title)

        val task = database.transcriptionTaskDao().getTask(RECORDING_ID)
        if (oldVersion == 3) {
            assertEquals(null, task)
        } else {
            assertNotNull(task)
            assertEquals("42", task?.attemptId)
        }
        val snapshot = database.transcriptSnapshotDao().get(RECORDING_ID)
        assertNotNull(snapshot)
        assertEquals("source", snapshot?.sourceId)

        database.openHelper.readableDatabase.query("PRAGMA table_info(transcription_tasks)").use { cursor ->
            val typeColumn = cursor.getColumnIndexOrThrow("type")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "attemptId") {
                    assertEquals("TEXT", cursor.getString(typeColumn))
                    found = true
                }
            }
            assertEquals(true, found)
        }
        database.close()
        context.deleteDatabase(name)
    }

    private fun createLegacyDatabase(context: Context, name: String, version: Int) {
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        db.execSQL(
            "CREATE TABLE recordings (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                "createdAtEpochMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, " +
                "sourceType TEXT NOT NULL, audioRelativePath TEXT, processingStatus TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE speakers (id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, colorIndex INTEGER NOT NULL, " +
                "FOREIGN KEY(recordingId) REFERENCES recordings(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX index_speakers_recordingId ON speakers(recordingId)")
        db.execSQL(
            "CREATE TABLE utterances (id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL, " +
                "speakerId TEXT, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, " +
                "sortOrder INTEGER NOT NULL, isTextEdited INTEGER NOT NULL, isSpeakerEdited INTEGER NOT NULL, " +
                "FOREIGN KEY(recordingId) REFERENCES recordings(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(speakerId) REFERENCES speakers(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL("CREATE INDEX index_utterances_recordingId_startMs ON utterances(recordingId, startMs)")
        db.execSQL("CREATE INDEX index_utterances_speakerId ON utterances(speakerId)")
        db.execSQL(
            "CREATE TABLE transcript_snapshots (recordingId TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, " +
                "resultId TEXT, createdAtEpochMs INTEGER NOT NULL, rawJson TEXT NOT NULL, " +
                "userEditsJson TEXT NOT NULL DEFAULT '{}', " +
                "FOREIGN KEY(recordingId) REFERENCES recordings(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("INSERT INTO recordings VALUES ('$RECORDING_ID', 'Migration fixture', 1, 2, 'MIC', NULL, 'CREATED')")
        db.execSQL(
            "INSERT INTO transcript_snapshots VALUES " +
                "('$RECORDING_ID', 'source', NULL, 1, '{}', '{}')"
        )
        if (version == 4) {
            db.execSQL(
                "CREATE TABLE transcription_tasks (recordingId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, " +
                    "stageMessage TEXT NOT NULL DEFAULT '', errorMessage TEXT, attemptId INTEGER NOT NULL DEFAULT 0, " +
                    "createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO transcription_tasks VALUES " +
                    "('$RECORDING_ID', 'QUEUED', 'queued', NULL, 42, 1, 1)"
            )
        }
        db.execSQL("PRAGMA user_version = $version")
        db.close()
    }

    private companion object {
        const val RECORDING_ID = "00000000-0000-0000-0000-000000000001"
    }
}
