package com.example.glpred.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diary_table")
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,       // e.g., "2026-10-19"
    val time: String,       // e.g., "12:40"
    val glucose: Int,       // Initial blood glucose (mg/dL)
    val carbs: Float,       // Carbohydrates consumed
    val bolus: Int,         // Short-acting insulin (units)
    val basis: Int          // Long-acting insulin (units)
)