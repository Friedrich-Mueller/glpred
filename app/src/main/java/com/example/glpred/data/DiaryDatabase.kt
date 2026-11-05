package com.example.glpred.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DiaryEntity::class, TrainedModelEntity::class], version = 3, exportSchema = false)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun trainedModelDao(): TrainedModelDao

    companion object {
        @Volatile
        private var INSTANCE: DiaryDatabase? = null

        fun getDatabase(context: Context): DiaryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DiaryDatabase::class.java,
                    "diary.db"
                )
                    // Deliberately no fallbackToDestructiveMigration: this file holds the hand-entered
                    // meals, the only data in the app that cannot be re-created. With the fallback, a
                    // version bump that forgot its Migration would drop both tables before any screen
                    // could warn; without it Room throws on open and the rows are still on disk
                    // (AUDIT.md H2). A bump therefore needs a written Migration in the same commit.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}