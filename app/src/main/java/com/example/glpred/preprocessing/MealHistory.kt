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
    val actual4hMgDl: Double?
) {
    enum class State {
        /** The 4 h label resolved: this meal is (or will be) a training row. */
        TRAINABLE,

        /** Fewer than four hours have passed since the meal; nothing can be known yet. */
        TOO_EARLY,

        /** Four hours have passed, but no imported CGM data covers that moment. */
        NEEDS_IMPORT
    }

    /** How far the stored prediction was from the truth, once both exist. */
    val predictionErrorMgDl: Double?
        get() {
            val predicted = entry.predictedGlucose ?: return null
            val actual = actual4hMgDl ?: return null
            return predicted - actual
        }
}

/**
 * Builds the history list from the two stores.
 *
 * Deliberately routed through [TrainingSamples.toWallClockMillis] and [LabelGenerator.labelsFor],
 * the same two functions training uses: if the history screen computed the 4 h value its own way,
 * it would eventually disagree with training about which meals are labelled, and the disagreement
 * would be invisible.
 */
object MealHistory {

    private const val FOUR_HOURS_MS = 4L * 60L * 60L * 1000L

    fun build(
        entries: List<DiaryEntity>,
        readings: List<CgmReadingEntity>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<HistoryRow> {
        val series = CgmSeries.from(readings)
        return entries.map { entry ->
            val wallClock = TrainingSamples.toWallClockMillis(entry.timestamp, zone)
            val labels = LabelGenerator.labelsFor(wallClock, series)
            val state = when {
                labels.after4h != null -> HistoryRow.State.TRAINABLE
                entry.timestamp + FOUR_HOURS_MS > nowMs -> HistoryRow.State.TOO_EARLY
                else -> HistoryRow.State.NEEDS_IMPORT
            }
            HistoryRow(
                entry = entry,
                state = state,
                actual3hMgDl = labels.after3h,
                actual4hMgDl = labels.after4h
            )
        }
    }
}
