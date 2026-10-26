package com.example.glpred.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Insert
    suspend fun insertEntry(entry: DiaryEntity)

    @Query("SELECT * FROM diary_table ORDER BY id DESC")
    fun getAllEntries(): Flow<List<DiaryEntity>>
}