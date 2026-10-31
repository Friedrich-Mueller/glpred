package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The coverage guard decides whether a prediction is an interpolation or a leap into unobserved
 * hours. Its only interesting logic is the circular distance, so that is what is pinned here - a
 * naive subtraction would mark 23:55 as far away from a 00:30 meal.
 */
class PredictionCoverageTest {

    @Test
    fun anHourNearARecordedMealIsCovered() {
        val covered = setOf(8, 18)
        assertTrue(PredictionCoverage.isCovered(covered, 8 * 60))       // same hour
        assertTrue(PredictionCoverage.isCovered(covered, 8 * 60 + 55))  // +55 min
        assertTrue(PredictionCoverage.isCovered(covered, 18 * 60 - 60)) // exactly the tolerance
    }

    @Test
    fun aQuietStretchOfTheDayIsNotCovered() {
        val covered = setOf(8, 18)
        assertFalse(PredictionCoverage.isCovered(covered, 8 * 60 + 70)) // 70 min past breakfast
        assertFalse(PredictionCoverage.isCovered(covered, 14 * 60))     // nothing at lunch
        assertFalse(PredictionCoverage.isCovered(covered, 3 * 60))      // nothing at night
    }

    @Test
    fun coverageWrapsAcrossMidnight() {
        val lateNight = setOf(23)
        assertTrue("23:40 is 20 min from 23:00", PredictionCoverage.isCovered(lateNight, 23 * 60 + 40))
        assertFalse("00:20 is 80 min from 23:00, outside the tolerance", PredictionCoverage.isCovered(lateNight, 20))
        assertTrue(
            "a midnight meal covers 00:30 in the same hour",
            PredictionCoverage.isCovered(setOf(0), 30)
        )
        assertTrue(
            "a midnight meal covers 23:20, which is 40 minutes earlier, not 1400",
            PredictionCoverage.isCovered(setOf(0), 23 * 60 + 20)
        )
    }

    @Test
    fun noCoverageAtAllCoversNothing() {
        assertFalse(PredictionCoverage.isCovered(emptySet(), 8 * 60))
    }

    @Test
    fun hoursComeFromTheSampleTimestamps() {
        val samples = listOf(
            sample(1L, hour = 8, minute = 30),
            sample(2L, hour = 8, minute = 5),
            sample(3L, hour = 19, minute = 0)
        )
        assertEquals(setOf(8, 19), PredictionCoverage.hoursFrom(samples))
    }

    private fun sample(id: Long, hour: Int, minute: Int) = TrainingSample(
        entryId = id,
        timestampMs = LocalDateTime.of(2026, 6, id.toInt(), hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli(),
        glucose = 120.0,
        carbs = 6.0,
        bolus = 0.0,
        basis = 0.0,
        after3h = null,
        after4h = null
    )
}
