package com.example.glpred.preprocessing

import com.example.glpred.data.CgmReadingEntity
import com.example.glpred.data.DiaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The history screen's only real logic is the label state, and it has to agree with training about
 * which meals are trainable. These tests pin the three states and the two reasons for being pending.
 */
class MealHistoryTest {

    @Test
    fun anEntryWhoseFourHourHorizonIsCoveredIsTrainable() {
        val mealAt = local(2026, 6, 1, 12, 0)
        val cover = TrainingSamples.toWallClockMillis(mealAt)
        val readings = listOf(
            CgmReadingEntity(timestampMs = cover, glucose = 120.0),
            CgmReadingEntity(timestampMs = cover + THREE_HOURS, glucose = 150.0),
            CgmReadingEntity(timestampMs = cover + FOUR_HOURS, glucose = 140.0)
        )

        val rows = MealHistory.build(
            entries = listOf(entry(mealAt)),
            readings = readings,
            nowMs = mealAt + 10 * 3_600_000L
        )

        assertEquals(1, rows.size)
        assertEquals(HistoryRow.State.TRAINABLE, rows[0].state)
        assertEquals(140.0, rows[0].actual4hMgDl!!, 1e-9)
        assertEquals(150.0, rows[0].actual3hMgDl!!, 1e-9)
    }

    @Test
    fun anElapsedEntryWithoutCgmCoverageNeedsAnImport() {
        val mealAt = local(2026, 6, 1, 12, 0)

        val rows = MealHistory.build(
            entries = listOf(entry(mealAt)),
            readings = emptyList(),
            nowMs = mealAt + 10 * 3_600_000L
        )

        assertEquals(
            "four hours have passed but nothing was imported for that moment",
            HistoryRow.State.NEEDS_IMPORT,
            rows[0].state
        )
        assertNull(rows[0].actual4hMgDl)
    }

    @Test
    fun aRecentEntryIsTooEarlyRatherThanMissingData() {
        val mealAt = local(2026, 6, 1, 12, 0)

        val rows = MealHistory.build(
            entries = listOf(entry(mealAt)),
            readings = emptyList(),
            nowMs = mealAt + 3_600_000L // one hour later
        )

        assertEquals(
            "waiting is a different instruction from re-importing",
            HistoryRow.State.TOO_EARLY,
            rows[0].state
        )
    }

    @Test
    fun predictionErrorIsPredictionMinusActual() {
        val mealAt = local(2026, 6, 1, 12, 0)
        val cover = TrainingSamples.toWallClockMillis(mealAt)
        val rows = MealHistory.build(
            entries = listOf(entry(mealAt, predicted = 120.0)),
            readings = listOf(CgmReadingEntity(timestampMs = cover + FOUR_HOURS, glucose = 150.0)),
            nowMs = mealAt + 10 * 3_600_000L
        )

        // Predicted low, came out high: 120 - 150 = -30, i.e. off by 30 in the dangerous direction.
        assertEquals(-30.0, rows[0].predictionErrorMgDl!!, 1e-9)
    }

    @Test
    fun withoutAStoredPredictionThereIsNoErrorToReport() {
        val mealAt = local(2026, 6, 1, 12, 0)
        val cover = TrainingSamples.toWallClockMillis(mealAt)
        val rows = MealHistory.build(
            entries = listOf(entry(mealAt)),
            readings = listOf(CgmReadingEntity(timestampMs = cover + FOUR_HOURS, glucose = 150.0)),
            nowMs = mealAt + 10 * 3_600_000L
        )

        assertNull(rows[0].predictionErrorMgDl)
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun entry(timestamp: Long, predicted: Double? = null) = DiaryEntity(
        id = 1L,
        timestamp = timestamp,
        glucose = 120,
        carbs = 6f,
        bolus = 6.0,
        basis = 0,
        suggestedBolus = null,
        predictedGlucose = predicted
    )

    private companion object {
        const val THREE_HOURS = 3L * 60L * 60L * 1000L
        const val FOUR_HOURS = 4L * 60L * 60L * 1000L
    }
}
