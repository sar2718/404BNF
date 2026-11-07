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

@Database(
    entities = [
        RecordingEntity::class,
        SpeakerEntity::class,
        UtteranceEntity::class,
        TranscriptSnapshotEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSnapshotDao(): TranscriptSnapshotDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also {
                        INSTANCE = it
                    }
            }
        }
    }
}