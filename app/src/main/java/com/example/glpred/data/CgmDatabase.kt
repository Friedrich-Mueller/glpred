package com.example.glpred.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Database for the imported CGM data, deliberately separate from the diary database
 * ([AppDatabase]): the CGM export is a large, replaceable table that is re-imported as a whole,
 * while the diary entries are written one by one by the user.
 */
@Database(entities = [CgmReadingEntity::class], version = 5, exportSchema = false)
abstract class CgmDatabase : RoomDatabase() {
    abstract fun cgmDao(): CgmDao

    companion object {
        @Volatile
        private var INSTANCE: CgmDatabase? = null

        fun getDatabase(context: Context): CgmDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CgmDatabase::class.java,
                    "cgm.db"
                )
                    // The table is a re-importable copy of the CSV, so a schema change drops it.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
