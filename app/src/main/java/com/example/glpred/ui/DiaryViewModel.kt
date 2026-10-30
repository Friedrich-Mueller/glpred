package com.example.glpred.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glpred.data.DiaryDatabase
import com.example.glpred.data.DiaryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val diaryDao = DiaryDatabase.getDatabase(application).diaryDao()

    val allEntries: StateFlow<List<DiaryEntity>> = diaryDao.getAllEntries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Temporary form state for review flow
    var tempDate by mutableStateOf("")
    var tempTime by mutableStateOf("")
    var tempGlucose by mutableStateOf("")
    var tempCarbs by mutableStateOf("")
    var tempBolus by mutableStateOf("")
    var tempBasis by mutableStateOf("")
    var tempTimestamp by mutableStateOf(System.currentTimeMillis())

    fun validateAndStoreTemp(
        date: String,
        time: String,
        glucoseStr: String,
        carbsStr: String,
        bolusStr: String,
        basisStr: String,
        timestamp: Long,
        onValid: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (glucoseStr.isBlank() || carbsStr.isBlank() || bolusStr.isBlank() || basisStr.isBlank()) {
            onError("Please fill in every single field (Glucose, Carbs, Bolus, and Basis).")
            return
        }

        tempDate = date
        tempTime = time
        tempGlucose = glucoseStr
        tempCarbs = carbsStr
        tempBolus = bolusStr
        tempBasis = basisStr
        tempTimestamp = timestamp

        onValid()
    }

    fun confirmAndSaveEntry(
        onSuccess: () -> Unit
    ) {
        val glucose = tempGlucose.toIntOrNull() ?: 0
        val carbs = tempCarbs.toFloatOrNull() ?: 0.0f
        val bolus = tempBolus.toIntOrNull() ?: 0
        val basis = tempBasis.toIntOrNull() ?: 0

        viewModelScope.launch {
            val entity = DiaryEntity(
                timestamp = tempTimestamp,
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
