package com.unishare.app.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ActivityEntity::class, ActivityPreviewEntity::class], version = 2, exportSchema = true)
@TypeConverters(ActivityConverters::class)
abstract class ActivityDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `activity_previews` (
                        `activityId` TEXT NOT NULL,
                        `textBody` TEXT,
                        `imagePreviewBytes` BLOB,
                        PRIMARY KEY(`activityId`),
                        FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
            }
        }
    }
}
