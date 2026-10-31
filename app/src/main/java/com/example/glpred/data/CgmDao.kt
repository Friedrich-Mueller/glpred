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

    /**
     * What is actually stored right now: the import summary on the fetch screen reports this span.
     * Counts and extremes come from one aggregate query, not from materialising the table.
     */
    @Query(
        "SELECT MIN(timestampMs) AS firstMs, MAX(timestampMs) AS lastMs, COUNT(*) AS readings " +
            "FROM cgm_table"
    )
    suspend fun storedSpan(): CgmSpan
}

/**
 * Extent of the stored CGM telemetry. Null extremes mean the table is empty; the row itself always
 * exists because `COUNT(*)` always produces one.
 *
 * Times are wall-clock-as-UTC millis, exactly as stored - format them in UTC, never in the device
 * zone, or the reported span shifts by the current UTC offset.
 */
data class CgmSpan(
    val firstMs: Long?,
    val lastMs: Long?,
    val readings: Int
)
