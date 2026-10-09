package com.unishare.app.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = true)
@TypeConverters(ActivityConverters::class)
abstract class ActivityDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}
