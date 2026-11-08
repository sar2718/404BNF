package com.team404bnf.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transcript_snapshots` (
                `recordingId` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `resultId` TEXT,
                `createdAtEpochMs` INTEGER NOT NULL,
                `rawJson` TEXT NOT NULL,
                PRIMARY KEY(`recordingId`),
                FOREIGN KEY(`recordingId`)
                    REFERENCES `recordings`(`id`)
                    ON UPDATE NO ACTION
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE transcript_snapshots
            ADD COLUMN userEditsJson TEXT NOT NULL DEFAULT '{}'
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transcription_tasks` (
                `recordingId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `stageMessage` TEXT NOT NULL DEFAULT '',
                `errorMessage` TEXT,
                `attemptId` INTEGER NOT NULL DEFAULT 0,
                `createdAtEpochMs` INTEGER NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`recordingId`)
            )
            """.trimIndent()
        )
    }

}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transcription_tasks RENAME TO transcription_tasks_old")
        db.execSQL(
            """
            CREATE TABLE `transcription_tasks` (
                `recordingId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `stageMessage` TEXT NOT NULL DEFAULT '',
                `errorMessage` TEXT,
                `attemptId` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`recordingId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO transcription_tasks
            (recordingId, status, stageMessage, errorMessage, attemptId, createdAtEpochMs, updatedAtEpochMs)
            SELECT recordingId, status, stageMessage, errorMessage,
                   CAST(attemptId AS TEXT), createdAtEpochMs, updatedAtEpochMs
            FROM transcription_tasks_old
            """.trimIndent()
        )
        db.execSQL("DROP TABLE transcription_tasks_old")
    }
}

@Database(
    entities = [
        RecordingEntity::class,
        SpeakerEntity::class,
        UtteranceEntity::class,
        TranscriptSnapshotEntity::class,
        TranscriptionTaskEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSnapshotDao(): TranscriptSnapshotDao
    abstract fun transcriptionTaskDao(): TranscriptionTaskDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {

                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "404bnf_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also {
                        INSTANCE = it
                    }
            }
        }
    }
}
