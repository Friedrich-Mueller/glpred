package com.example.glpred.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glpred.data.DiaryDatabase
import com.example.glpred.data.DiaryEntity
import com.example.glpred.prediction.DiaryEntryInput
import com.example.glpred.prediction.DoseAdvisor
import com.example.glpred.prediction.DoseRecommendation
import com.example.glpred.prediction.UnavailableDoseAdvisor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the enter -> review/prediction -> final confirm flow and persists diary entries.
 *
 * The entry is captured without insulin, the advisor suggests a bolus, and the amount actually
 * injected is entered afterwards - so a saved entry always knows the suggestion and the reality.
 */
class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val diaryDao = DiaryDatabase.getDatabase(application).diaryDao()

    /**
     * Swapped for the trained model once the pipeline exists (PLAN.md phases 2-3). A property
     * rather than a constructor argument, so `viewModel()` can still construct this type with
     * nothing but the Application.
     */
    private val doseAdvisor: DoseAdvisor = UnavailableDoseAdvisor

    val allEntries: StateFlow<List<DiaryEntity>> = diaryDao.getAllEntries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // ---------------------------------------------------------------- draft entry

    var tempDate by mutableStateOf("")
        private set
    var tempTime by mutableStateOf("")
        private set
    var tempGlucose by mutableStateOf("")
        private set
    var tempCarbs by mutableStateOf("")
        private set
    var tempBasis by mutableStateOf("")
        private set
    var tempTimestamp by mutableStateOf(System.currentTimeMillis())
        private set

    /** Insulin that was actually injected, captured on the review screen. */
    var actualBolusStr by mutableStateOf("")
        private set

    var isPredicting by mutableStateOf(false)
        private set

    /** Null until the advisor has answered for the current draft. */
    var recommendation by mutableStateOf<DoseRecommendation?>(null)
        private set

    // ---------------------------------------------------------------- flow

    /**
     * Validates the three insulin-independent fields, runs the advisor, and only then reports
     * readiness - so the review screen always has its prediction in hand when it appears.
     */
    fun validateAndStartReview(
        date: String,
        time: String,
        glucoseStr: String,
        carbsStr: String,
        basisStr: String,
        timestamp: Long,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (glucoseStr.isBlank() || carbsStr.isBlank() || basisStr.isBlank()) {
            onError("Please fill in blood glucose, carbs and basis.")
            return
        }
        val glucose = glucoseStr.toIntOrNull()
        if (glucose == null || glucose <= 0) {
            onError("Blood glucose must be a whole number in mg/dL.")
            return
        }
        val carbs = carbsStr.replace(',', '.').toFloatOrNull()
        if (carbs == null || carbs < 0f) {
            onError("Carbohydrates must be a number.")
            return
        }
        val basis = basisStr.toIntOrNull()
        if (basis == null || basis < 0) {
            onError("Basis must be a whole number of units.")
            return
        }

        tempDate = date
        tempTime = time
        tempGlucose = glucoseStr
        tempCarbs = carbsStr
        tempBasis = basisStr
        tempTimestamp = timestamp
        actualBolusStr = ""
        recommendation = null
        isPredicting = true

        viewModelScope.launch {
            val input = DiaryEntryInput(
                timestampMs = timestamp,
                glucose = glucose,
                carbs = carbs,
                basis = basis
            )
            recommendation = runCatching { doseAdvisor.recommend(input) }
                .getOrElse { DoseRecommendation.Unavailable("Prediction failed: ${it.message}") }
            isPredicting = false
            onReady()
        }
    }

    fun setActualBolus(value: String) {
        actualBolusStr = value
    }

    /** Validates the injected amount before moving on to the final confirmation. */
    fun validateActualBolus(onValid: () -> Unit, onError: (String) -> Unit) {
        val parsed = actualBolusStr.replace(',', '.').toDoubleOrNull()
        if (parsed == null || parsed < 0.0) {
            onError("Enter the insulin you injected, in units (e.g. 6 or 6.5).")
            return
        }
        if (parsed > 100.0) {
            onError("That looks too high - please check the value.")
            return
        }
        onValid()
    }

    /** Saves the entry with the injected dose plus, when there was one, the suggestion it beat. */
    fun confirmAndSaveEntry(onSuccess: () -> Unit) {
        val bolus = actualBolusStr.replace(',', '.').toDoubleOrNull() ?: 0.0
        val suggestion = recommendation as? DoseRecommendation.Available

        viewModelScope.launch {
            diaryDao.insertEntry(
                DiaryEntity(
                    timestamp = tempTimestamp,
                    glucose = tempGlucose.toIntOrNull() ?: 0,
                    carbs = tempCarbs.replace(',', '.').toFloatOrNull() ?: 0f,
                    bolus = bolus,
                    basis = tempBasis.toIntOrNull() ?: 0,
                    suggestedBolus = suggestion?.suggestedUnits,
                    predictedGlucose = suggestion?.predictedGlucoseMgDl
                )
            )
            onSuccess()
        }
    }
}
