package com.example.glpred.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Entity(tableName = "diary_table")
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,    // Epoch timestamp in milliseconds
    val glucose: Int,       // Blood glucose level (mg/dL)
    val carbs: Float,       // Carbohydrates consumed
    val bolus: Int,         // Short-acting insulin injected
    val basis: Int          // Long-acting insulin injected
) {
    private val localDateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())

    // ----------------------------------------------------------------
    // For ML Model (GlucosePredictor Engine)
    // ----------------------------------------------------------------
    val hourOfDay: Int
        get() = localDateTime.hour

    val minuteOfHour: Int
        get() = localDateTime.minute

    // ----------------------------------------------------------------
    // For UI Screens (Meal Log List / History Cards)
    // ----------------------------------------------------------------
    val formattedDate: String
        get() = localDateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

    val formattedTime: String
        get() = localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
}