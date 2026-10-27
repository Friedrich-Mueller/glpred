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
        val glucose = glucoseStr.toIntOrNull() ?: 0
        val carbs = carbsStr.toFloatOrNull() ?: 0.0f
        val bolus = bolusStr.toIntOrNull() ?: 0
        val basis = basisStr.toIntOrNull() ?: 0

        // Validation: At least one input field must be filled/non-zero
        if (glucose == 0 && carbs == 0.0f && bolus == 0 && basis == 0) {
            onError("At least one entry value (Glucose, Carbs, Bolus, or Basis) is required.")
            return
        }

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
