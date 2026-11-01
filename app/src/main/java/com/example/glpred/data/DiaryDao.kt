package com.example.glpred.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Insert
    suspend fun insertEntry(entry: DiaryEntity)

    // 1. Reactive Flow (For UI screens)
    @Query("SELECT * FROM diary_table ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<DiaryEntity>>

    // 2. One-shot suspend List (For ML training)
    @Query("SELECT * FROM diary_table ORDER BY timestamp ASC")
    suspend fun getAllEntriesList(): List<DiaryEntity>

    /** Correcting a meal in place: the row keeps its id, so suggestions stay attached to it. */
    @Update
    suspend fun updateEntry(entry: DiaryEntity)

    @Query("DELETE FROM diary_table WHERE id = :id")
    suspend fun deleteEntry(id: Long)
}