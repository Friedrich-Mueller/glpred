package com.example.glpred.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glpred.data.CgmDatabase
import com.example.glpred.data.DiaryDatabase
import com.example.glpred.data.DiaryEntity
import com.example.glpred.prediction.DiaryEntryInput
import com.example.glpred.prediction.DoseAdvisor
import com.example.glpred.prediction.DoseRecommendation
import com.example.glpred.prediction.ModelDoseAdvisor
import com.example.glpred.prediction.PredictionCoverage
import com.example.glpred.prediction.TrainingResult
import com.example.glpred.prediction.TrainingRunner
import com.example.glpred.prediction.toRidgeModel
import com.example.glpred.preprocessing.HistoryRow
import com.example.glpred.preprocessing.MealHistory
import com.example.glpred.preprocessing.TrainingSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the enter -> review/prediction -> final confirm flow and persists diary entries.
 *
 * The entry is captured without insulin, the advisor suggests a bolus, and the amount actually
 * injected is entered afterwards - so a saved entry always knows the suggestion and the reality.
 */
class DiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = DiaryDatabase.getDatabase(application)
    private val diaryDao = database.diaryDao()

    /** Reads the stored model; reports Unavailable until one has been trained. */
    private val doseAdvisor: DoseAdvisor = ModelDoseAdvisor(database.trainedModelDao())

    init {
        restoreStoredModel()
    }

    /**
     * The model survives the process, so the status line must not claim otherwise: without this the
     * screen said "not trained yet" after every restart while the advisor went on using the model.
     */
    private fun restoreStoredModel() {
        viewModelScope.launch {
            val stored = database.trainedModelDao().get() ?: return@launch
            trainingResult = TrainingResult.Trained(
                entries = 0, // not persisted; the sample counts are what matter for the status line
                samples4h = stored.samples4h,
                samples3h = stored.samples3h,
                rmse4h = stored.rmse4h,
                rmse3h = stored.rmse3h,
                trainedAt = stored.trainedAt,
                restoredAtStartup = true
            )
        }
    }

    val allEntries: StateFlow<List<DiaryEntity>> = diaryDao.getAllEntries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val cgmDao = CgmDatabase.getDatabase(application).cgmDao()

    var isHistoryLoading by mutableStateOf(true)
        private set

    /**
     * Past meals with their label state. Rebuilt whenever the diary changes, and the CGM series is
     * re-read on each rebuild, so a fresh import shows up without a cache to invalidate.
     *
     * The flag is set from inside the mapping because an empty initial flow value is
     * indistinguishable from "no meals recorded" otherwise, and the screen would flash the empty
     * message on every open.
     */
    val history: StateFlow<List<HistoryRow>> = allEntries
        .map { entries ->
            if (entries.isEmpty()) {
                isHistoryLoading = false
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    val model = database.trainedModelDao().get()?.toRidgeModel()
                    MealHistory.build(
                        entries = entries,
                        readings = cgmDao.getAllReadings(),
                        predict4h = model?.let { trained ->
                            { sample: TrainingSample -> trained.predict4h(sample.features()) }
                        }
                    )
                }.also { isHistoryLoading = false }
            }
        }
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
            val base = runCatching { doseAdvisor.recommend(input) }
                .getOrElse { DoseRecommendation.Unavailable("Prediction failed: ${it.message}") }
            recommendation = withCoverageWarning(base, timestamp)
            isPredicting = false
            onReady()
        }
    }

    /**
     * Phase-5 guard. The time interactions are linear in sin/cos, so at an hour where no meal was
     * ever recorded the model still returns a confident number - an extrapolation presented as an
     * estimate. The suggestion is kept, because a withheld prediction teaches nothing, and flagged.
     */
    private fun withCoverageWarning(
        recommendation: DoseRecommendation,
        timestampMs: Long
    ): DoseRecommendation {
        if (recommendation !is DoseRecommendation.Available) return recommendation
        val covered = PredictionCoverage.load(getApplication()) ?: return recommendation
        val minuteOfDay = TrainingSample.minuteOfDay(timestampMs)
        if (PredictionCoverage.isCovered(covered, minuteOfDay)) return recommendation
        return recommendation.copy(
            coverageWarning = "No recorded meal within an hour of this time of day. " +
                "The prediction is an extrapolation - treat it with extra caution."
        )
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

    // ---------------------------------------------------------------- training

    private val trainingRunner = TrainingRunner(
        diaryDao = diaryDao,
        cgmDao = CgmDatabase.getDatabase(application).cgmDao(),
        modelDao = database.trainedModelDao()
    )

    var isTraining by mutableStateOf(false)
        private set

    var trainingResult by mutableStateOf<TrainingResult?>(null)
        private set

    /**
     * One line the main screen shows verbatim. Refusals are phrased as the next action, because
     * "re-import your export" and "record more meals" are different instructions.
     */
    val trainingStatus: String
        get() = when (val result = trainingResult) {
            null -> "Model: not trained yet"
            is TrainingResult.NoEntries -> "Model: no diary entries yet - record a meal first"
            is TrainingResult.NoLabelledSamples ->
                "Model: ${result.entries} entries, none labelled yet - re-import your CGM export " +
                    "so the 4 h values exist"
            is TrainingResult.TooFewSamples ->
                "Model: ${result.labelled} of ${result.required} labelled entries - record more meals"
            is TrainingResult.Failed -> "Model: training failed - ${result.reason}"
            is TrainingResult.Trained -> {
                val validation = result.looRmse4h
                when {
                    validation != null ->
                        "Model: ${result.samples4h} samples, validation error ${validation.toInt()} mg/dL " +
                            "(in-sample ${result.rmse4h.toInt()}, ${result.looFolds4h} folds)"
                    result.restoredAtStartup ->
                        "Model: ${result.samples4h} samples, in-sample RMSE ${result.rmse4h.toInt()} mg/dL " +
                            "(restored after restart - press Train to recompute validation)"
                    else ->
                        "Model: ${result.samples4h} samples, in-sample RMSE ${result.rmse4h.toInt()} mg/dL " +
                            "- validation needs one more labelled entry"
                }
            }
        }

    fun trainModel() {
        if (isTraining) return
        isTraining = true
        viewModelScope.launch {
            val result = trainingRunner.train()
            trainingResult = result
            // Coverage is only known at fit time and is not part of the model row, so it is kept
            // beside the model in preferences for the out-of-distribution guard.
            (result as? TrainingResult.Trained)?.let { trained ->
                PredictionCoverage.save(getApplication(), trained.coveredHours)
            }
            isTraining = false
        }
    }
}
