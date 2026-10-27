package com.example.glpred.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glpred.data.AppDatabase
import com.example.glpred.data.DiaryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val diaryDao = AppDatabase.getDatabase(application).diaryDao()

    val allEntries: StateFlow<List<DiaryEntity>> = diaryDao.getAllEntries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun saveEntry(
        glucoseStr: String,
        carbsStr: String,
        bolusStr: String,
        basisStr: String,
        timestamp: Long = System.currentTimeMillis(),
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Validation: Every single field must be filled
        if (glucoseStr.isBlank() || carbsStr.isBlank() || bolusStr.isBlank() || basisStr.isBlank()) {
            onError("Please fill in every single field (Glucose, Carbs, Bolus, and Basis).")
            return
        }

        val glucose = glucoseStr.toIntOrNull() ?: 0
        val carbs = carbsStr.toFloatOrNull() ?: 0.0f
        val bolus = bolusStr.toIntOrNull() ?: 0
        val basis = basisStr.toIntOrNull() ?: 0

        viewModelScope.launch {
            val entity = DiaryEntity(
                timestamp = timestamp,
                glucose = glucose,
                carbs = carbs,
                bolus = bolus,
                basis = basis
            )
            diaryDao.insertEntry(entity)
            onSuccess()
        }
    }
}
