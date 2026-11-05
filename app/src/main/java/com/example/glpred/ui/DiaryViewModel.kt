package com.example.glpred.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.glpred.data.CgmDatabase
import com.example.glpred.data.DataVersion
import com.example.glpred.data.DiaryCsv
import com.example.glpred.data.DiaryDatabase
import com.example.glpred.data.DiaryEntity
import com.example.glpred.prediction.DiaryEntryInput
import com.example.glpred.prediction.DoseAdvisor
import com.example.glpred.prediction.DoseRecommendation
import com.example.glpred.prediction.DoseSlopes
import com.example.glpred.prediction.LeaveOneOut
import com.example.glpred.prediction.ModelDoseAdvisor
import com.example.glpred.prediction.ModelVsHuman
import com.example.glpred.prediction.PredictionCoverage
import com.example.glpred.prediction.TrainingResult
import com.example.glpred.prediction.TrainingRunner
import com.example.glpred.prediction.toRidgeModel
import com.example.glpred.preprocessing.CgmSeries
import com.example.glpred.preprocessing.HistoryRow
import com.example.glpred.preprocessing.MealHistory
import com.example.glpred.preprocessing.TrainingSample
import com.example.glpred.preprocessing.TrainingSamples
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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
     * Diary version this screen has seen. Edits and deletes bump it; new meals do not. Initialised
     * here rather than in `init`, because Kotlin resolves initialisers in declaration order.
     */
    var dataVersion by mutableStateOf(DataVersion.current(getApplication()))
        private set

    private var trainedVersion by mutableStateOf(DataVersion.trainedVersion(getApplication()))

    /**
     * True when the stored model was fitted on rows that have since been edited or removed. It is
     * not refitted automatically: silently changing the numbers under the user would hide that the
     * data moved, and a refit is one tap away.
     */
    val isModelStale: Boolean
        get() = trainingResult is TrainingResult.Trained && trainedVersion != dataVersion

    /** A model exists, whether or not it still describes the current diary. */
    val hasModel: Boolean
        get() = trainingResult is TrainingResult.Trained

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
            try {
                if (entries.isEmpty()) {
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
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Keep the flow collecting and the spinner down. Residual: the screen still cannot
                // tell "no meals" from "the read failed", because it has no error state (AUDIT.md M4).
                emptyList()
            } finally {
                isHistoryLoading = false
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    var analysis by mutableStateOf<ModelVsHuman?>(null)
        private set

    var isAnalysisLoading by mutableStateOf(false)
        private set

    /**
     * Recomputed on demand rather than cached: the figure is only interesting because it changes as
     * meals accumulate. The cost is one CGM read plus a handful of small solves.
     */
    fun refreshAnalysis() {
        if (isAnalysisLoading) return
        isAnalysisLoading = true
        viewModelScope.launch {
            try {
                analysis = withContext(Dispatchers.IO) {
                    val entries = diaryDao.getAllEntriesList()
                    if (entries.isEmpty()) {
                        ModelVsHuman.build(emptyList(), emptyList())
                    } else {
                        val series = CgmSeries.from(cgmDao.getAllReadings())
                        val samples = TrainingSamples.build(entries, series)
                        ModelVsHuman.build(
                            entries = entries,
                            samples = samples,
                            validationErrorMgDl = LeaveOneOut.rmse4h(samples)?.rmse,
                            modelCurve = modelCurveByHour(entries),
                            slopeFor = { timestamp -> DoseSlopes.load(getApplication(), timestamp) }
                        )
                    }
                }
            } finally {
                // Reset on every path; a throw used to leave the screen loading for ever (AUDIT.md M4).
                isAnalysisLoading = false
            }
        }
    }

    /**
     * The model's dose per carb unit at every hour of the day, obtained by asking it for a reference
     * meal at each one. Entry points alone cannot give a continuous curve - they only exist where the
     * user actually ate - and re-deriving the curve from the coefficients would mean dividing
     * standardised coefficients by feature scales, which is an interpretation rather than a record of
     * what the model says. The reference meal is the median of the user's own meals.
     */
    private suspend fun modelCurveByHour(entries: List<DiaryEntity>): List<Double?> {
        val withCarbs = entries.filter { it.carbs > 0f }
        if (withCarbs.isEmpty()) return emptyList()
        val carbs = median(withCarbs.map { it.carbs.toDouble() })
        if (carbs <= 0.0) return emptyList()
        val glucose = median(entries.map { it.glucose.toDouble() }).roundToInt()
        val basis = median(entries.map { it.basis.toDouble() }).roundToInt()

        val advisor = ModelDoseAdvisor(database.trainedModelDao())
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
        return (0 until 24).map { hour ->
            val timestamp = dayStart.plusHours(hour.toLong()).toInstant().toEpochMilli()
            val recommendation = runCatching {
                advisor.recommend(DiaryEntryInput(timestamp, glucose, carbs.toFloat(), basis))
            }.getOrNull() as? DoseRecommendation.Available
            recommendation?.let { it.suggestedUnits / carbs }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

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

    /**
     * Written by the entry screen's pickers on every pick, so the choice survives a rotation and the
     * trip to the review screen. Read back when the form is composed, which is what stops the date
     * and time from silently reverting to "now" when the entry is edited (AUDIT.md M1).
     */
    fun setDraftDate(formatted: String) {
        tempDate = formatted
    }

    fun setDraftTime(formatted: String) {
        tempTime = formatted
    }

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
        // The same ranges the History editor enforces: a value one screen rejects must not be
        // accepted on the other, and both used to accept anything that merely parsed (AUDIT.md M3).
        val glucose = EntryLimits.glucose(glucoseStr)
            .getOrElse { onError(it.message.orEmpty()); return }
        val carbs = EntryLimits.carbs(carbsStr)
            .getOrElse { onError(it.message.orEmpty()); return }.toFloat()
        val basis = EntryLimits.basis(basisStr)
            .getOrElse { onError(it.message.orEmpty()); return }

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
            try {
                val base = runCatching { doseAdvisor.recommend(input) }
                    .getOrElse { DoseRecommendation.Unavailable("Prediction failed: ${it.message}") }
                // A failure in the guard costs the warning, not the prediction itself.
                recommendation = runCatching { withCoverageWarning(base, timestamp) }.getOrElse { base }
                onReady()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Without this the form kept isPredicting = true for ever: the Review button did
                // nothing and said nothing (AUDIT.md M4).
                onError("The prediction could not be shown: ${error.message ?: error::class.simpleName}")
            } finally {
                isPredicting = false
            }
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
        if (actualBolusStr.isBlank()) {
            onError("Enter the insulin you injected, in units (e.g. 6 or 6.5).")
            return
        }
        // One bound, shared with the sweep ceiling, instead of the old "anything up to 100" check.
        EntryLimits.bolus(actualBolusStr).getOrElse { onError(it.message.orEmpty()); return }
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
            // Navigate first, then forget: the final-confirm card is still composed while this
            // runs, so emptying the draft before the destination changes would flash blank values.
            suggestion?.slopeMgDlPerUnit?.let { slope ->
                DoseSlopes.save(getApplication(), tempTimestamp, slope)
            }
            onSuccess()
            clearDraft()
        }
    }

    /**
     * Forgets the draft. The enter screen seeds its fields from these values, so without this the next
     * meal starts pre-filled with the last one's glucose, carbs and basis - typing into a pre-filled
     * form is how 120111 mg/dL got entered and stored on 03-11-2026.
     *
     * Called after a successful save, and through [discardDraft] when the form is left without
     * saving: an abandoned draft used to survive and pre-fill the next entry (AUDIT.md M8).
     */
    fun discardDraft() = clearDraft()

    private fun clearDraft() {
        tempDate = ""
        tempTime = ""
        tempGlucose = ""
        tempCarbs = ""
        tempBasis = ""
        actualBolusStr = ""
        recommendation = null
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
                val base = when {
                    validation != null ->
                        "Model: ${result.samples4h} samples, validation error ${validation.toInt()} mg/dL " +
                            "(in-sample ${result.rmse4h.toInt()}, ${result.looFolds4h} folds)"
                    result.restoredAtStartup ->
                        "Model: ${result.samples4h} samples, restored from storage - the validation " +
                            "error is recomputed at the next import"
                    else ->
                        "Model: ${result.samples4h} samples, in-sample RMSE ${result.rmse4h.toInt()} mg/dL " +
                            "- validation needs one more labelled entry"
                }
                if (isModelStale) "$base - a meal was edited or deleted since" else base
            }
        }

    /**
     * How much the current model can be trusted, shown beside every suggestion.
     *
     * The sweep always finds *some* dose that lands in band under the model, however wrong the model
     * is, so a dose printed without its error is the app's most misleading output. This is why the
     * figure lives here rather than only on the main screen.
     */
    val modelQuality: String?
        get() {
            val trained = trainingResult as? TrainingResult.Trained ?: return null
            val validation = trained.looRmse4h
            return if (validation != null) {
                "Model: ${trained.samples4h} samples - typical miss " +
                    "${validation.roundToInt()} mg/dL on meals it did not see"
            } else {
                "Model: ${trained.samples4h} samples - accuracy not validated yet"
            }
        }

    /**
     * Removes a meal. The model was fitted with this row in it, so the edit marks it stale rather
     * than refitting on the spot - refitting silently would hide that the numbers changed.
     */
    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            diaryDao.deleteEntry(id)
            dataVersion = DataVersion.bump(getApplication())
        }
    }

    /**
     * Applies an edit to any of the six recorded values, returning null when it was accepted or the
     * reason it was not - so the editor can stay open rather than silently discard a correction.
     */
    fun updateEntry(
        original: DiaryEntity,
        date: String,
        time: String,
        glucoseStr: String,
        carbsStr: String,
        bolusStr: String,
        basisStr: String
    ): String? {
        val timestamp = runCatching {
            LocalDateTime.parse(
                "${date.trim()} ${time.trim()}",
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            )
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return "Date and time must look like 01.11.2026 and 18:20."

        // The ranges live in EntryLimits so this door and the entry form cannot disagree.
        val glucose = EntryLimits.glucose(glucoseStr).getOrElse { return it.message ?: "Invalid blood glucose." }
        val carbs = EntryLimits.carbs(carbsStr).getOrElse { return it.message ?: "Invalid carb units." }
        val bolus = EntryLimits.bolus(bolusStr).getOrElse { return it.message ?: "Invalid injected units." }
        val basis = EntryLimits.basis(basisStr).getOrElse { return it.message ?: "Invalid basis." }

        val mealChanged = timestamp != original.timestamp || glucose != original.glucose ||
            carbs.toFloat() != original.carbs || basis != original.basis

        viewModelScope.launch {
            diaryDao.updateEntry(
                original.copy(
                    timestamp = timestamp,
                    glucose = glucose,
                    carbs = carbs.toFloat(),
                    bolus = bolus,
                    basis = basis,
                    // The suggestion was made for the meal as it was. Once the meal itself changes it
                    // no longer describes this entry, so it is dropped rather than left to mislead.
                    suggestedBolus = if (mealChanged) null else original.suggestedBolus,
                    predictedGlucose = if (mealChanged) null else original.predictedGlucose
                )
            )
            dataVersion = DataVersion.bump(getApplication())
        }
        return null
    }

    /**
     * Writes the whole diary to [uri] and reports the row count.
     *
     * The count is reported rather than assumed: an export that silently wrote nothing looks
     * identical to one that worked, and the user finds out when they need the file.
     */
    fun exportDiary(uri: Uri, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val entries = diaryDao.getAllEntriesList()
                    val csv = DiaryCsv.render(entries)
                    val stream = getApplication<Application>().contentResolver
                        .openOutputStream(uri) ?: error("The selected file could not be opened.")
                    stream.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    entries.size
                }
            }
            onDone(result)
        }
    }

    fun trainModel() {
        if (isTraining) return
        isTraining = true
        viewModelScope.launch {
            try {
                val result = trainingRunner.train()
                trainingResult = result
            // Coverage is only known at fit time and is not part of the model row, so it is kept
            // beside the model in preferences for the out-of-distribution guard.
                (result as? TrainingResult.Trained)?.let { trained ->
                    PredictionCoverage.save(getApplication(), trained.coveredHours)
                    // The fit describes the diary as it is now, so this is the version it is valid for.
                    DataVersion.markTrained(getApplication())
                    trainedVersion = dataVersion
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Training used to leave isTraining = true for ever on a throw, and the retrain button
                // then only ever answered "Training is already running" (AUDIT.md M4).
                trainingResult = TrainingResult.Failed(
                    error.message ?: error::class.simpleName ?: "unknown error"
                )
            } finally {
                isTraining = false
            }
        }
    }
}
