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

    /**
     * The cross-check that keeps the screen honest: "trainable" must mean exactly "has a 4 h label
     * in the training set". If someone re-implements the label lookup here, this fails instead of
     * the two screens disagreeing in silence.
     */
    @Test
    fun trainableMealsAreExactlyTheLabelledTrainingRows() {
        val covered = local(2026, 6, 1, 12, 0)
        val uncovered = local(2026, 6, 2, 12, 0)
        val entries = listOf(
            entry(covered, id = 1L),
            entry(uncovered, id = 2L) // far after the imported data ends
        )
        val readings = readingsAround(TrainingSamples.toWallClockMillis(covered))
        val nowMs = uncovered + 10 * 3_600_000L

        val rows = MealHistory.build(entries, readings, nowMs = nowMs)
        val labelled = TrainingSamples
            .labelled(TrainingSamples.build(entries, CgmSeries.from(readings)))
            .size

        assertEquals(1, labelled) // the fixture must exercise a trainable and a pending meal
        assertEquals(labelled, rows.count { it.state == HistoryRow.State.TRAINABLE })
    }

    @Test
    fun thePredictionIsRequestedForTheDoseThatWasInjected() {
        val mealAt = local(2026, 6, 1, 12, 0)
        val cover = TrainingSamples.toWallClockMillis(mealAt)

        var bolusSeen: Double? = null
        val rows = MealHistory.build(
            entries = listOf(entry(mealAt, bolus = 6.5)),
            readings = readingsAround(cover),
            predict4h = { sample ->
                bolusSeen = sample.bolus
                121.0
            },
            nowMs = mealAt + 10 * 3_600_000L
        )

        assertEquals("the injected dose, not the suggested one", 6.5, bolusSeen!!, 1e-9)
        assertEquals(121.0, rows[0].predictedForInjectedDoseMgDl!!, 1e-9)
    }

    @Test
    fun withoutAModelThereIsNoPrediction() {
        val mealAt = local(2026, 6, 1, 12, 0)
        val cover = TrainingSamples.toWallClockMillis(mealAt)
        val rows = MealHistory.build(
            entries = listOf(entry(mealAt)),
            readings = readingsAround(cover),
            nowMs = mealAt + 10 * 3_600_000L
        )

        assertNull(rows[0].predictedForInjectedDoseMgDl)
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** Coverage from the meal itself out to four hours, so both horizons resolve. */
    private fun readingsAround(wallClockMeal: Long) = listOf(
        CgmReadingEntity(timestampMs = wallClockMeal, glucose = 120.0),
        CgmReadingEntity(timestampMs = wallClockMeal + THREE_HOURS, glucose = 150.0),
        CgmReadingEntity(timestampMs = wallClockMeal + FOUR_HOURS, glucose = 140.0)
    )

    private fun entry(
        timestamp: Long,
        id: Long = 1L,
        bolus: Double = 6.0
    ) = DiaryEntity(
        id = id,
        timestamp = timestamp,
        glucose = 120,
        carbs = 6f,
        bolus = bolus,
        basis = 0,
        suggestedBolus = 5.5,
        predictedGlucose = 118.0
    )

    private companion object {
        const val THREE_HOURS = 3L * 60L * 60L * 1000L
        const val FOUR_HOURS = 4L * 60L * 60L * 1000L
    }
}
