package com.example.glpred.preprocessing

import com.example.glpred.data.CgmReadingEntity
import com.example.glpred.data.DiaryEntity
import java.time.ZoneId

/**
 * One past meal, as the history screen shows it.
 *
 * The distinction that matters is [State]: a meal is only a training row once the imported CGM
 * covers the moment four hours after it. Everything else is pending, and the two reasons for being
 * pending lead to different actions - wait, or re-import the export.
 */
data class HistoryRow(
    val entry: DiaryEntity,
    val state: State,
    val actual3hMgDl: Double?,
    val actual4hMgDl: Double?,
    /**
     * The model's 4 h prediction *for the dose that was actually injected*, so it can sit beside
     * [actual4hMgDl] and describe the same meal and the same dose. The prediction stored on the
     * entry was made for the suggested dose and is not comparable when the two doses differ.
     *
     * Null when no model has been trained. Deliberately not turned into an error or a verdict:
     * judging the model is a separate concern with its own screen.
     */
    val predictedForInjectedDoseMgDl: Double? = null
) {
    enum class State {
        /** The 4 h label resolved: this meal is (or will be) a training row. */
        TRAINABLE,

        /** Fewer than four hours have passed since the meal; nothing can be known yet. */
        TOO_EARLY,

        /** Four hours have passed, but no imported CGM data covers that moment. */
        NEEDS_IMPORT
    }
}

/**
 * Builds the history list from the two stores.
 *
 * The labels and the state come from [TrainingSamples.build] - the identical join training performs
 * - so "trainable" here means precisely "appears in the training set with a 4 h label". A second
 * implementation would drift eventually, and both would keep rendering plausible numbers, so the
 * disagreement would never surface.
 */
object MealHistory {

    private const val FOUR_HOURS_MS = 4L * 60L * 60L * 1000L

    /**
     * @param predict4h the trained model, passed in as a function so this package keeps no
     *   dependency on the prediction package (which already depends on this one). Called once per
     *   entry with that entry's own training row, so the dose in the prediction is the injected one.
     */
    fun build(
        entries: List<DiaryEntity>,
        readings: List<CgmReadingEntity>,
        predict4h: ((TrainingSample) -> Double?)? = null,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<HistoryRow> {
        val series = CgmSeries.from(readings)
        val samples = TrainingSamples.build(entries, series, zone)
        return entries.zip(samples).map { (entry, sample) ->
            val state = when {
                sample.after4h != null -> HistoryRow.State.TRAINABLE
                entry.timestamp + FOUR_HOURS_MS > nowMs -> HistoryRow.State.TOO_EARLY
                else -> HistoryRow.State.NEEDS_IMPORT
            }
            HistoryRow(
                entry = entry,
                state = state,
                actual3hMgDl = sample.after3h,
                actual4hMgDl = sample.after4h,
                predictedForInjectedDoseMgDl = predict4h?.invoke(sample)
            )
        }
    }
}
