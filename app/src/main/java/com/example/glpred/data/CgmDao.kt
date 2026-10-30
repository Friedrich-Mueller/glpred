package com.example.glpred.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CgmDao {
    @Query("DELETE FROM cgm_table")
    suspend fun clearAll()

    // One-shot suspend List (For preprocessing and ML training)
    @Query("SELECT * FROM cgm_table ORDER BY timestampMs ASC")
    suspend fun getAllReadings(): List<CgmReadingEntity>

    // Closed millisecond range, for label generation around a single diary entry.
    @Query("SELECT * FROM cgm_table WHERE timestampMs BETWEEN :fromMs AND :toMs ORDER BY timestampMs ASC")
    suspend fun getReadingsBetween(fromMs: Long, toMs: Long): List<CgmReadingEntity>
}
