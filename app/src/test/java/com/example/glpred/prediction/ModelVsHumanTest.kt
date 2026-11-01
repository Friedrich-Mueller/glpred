package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The analysis screen draws conclusions about a person, so its ratio arithmetic is pinned here: the
 * weighting by carb units, the fact that a meal with no suggestion still counts for the user, and
 * that hours are sorted with correction-only entries left out.
 */
class ModelVsHumanTest {

    @Test
    fun theModelRatioComesFromItsOwnSuggestion() {
        // Same hour, two meals; the model suggested 5 units for 5 CU and 7 for 7 CU.
        val entries = listOf(
            entry(id = 1L, hour = 12, carbs = 5f, bolus = 6.0, suggested = 5.0),
            entry(id = 2L, hour = 12, carbs = 7f, bolus = 7.0, suggested = 7.0)
        )

        val analysis = ModelVsHuman.build(entries, samples = emptyList())

        val point = analysis.ratioByHour.single()
        assertEquals(12, point.hourOfDay)
        // Yours: (6 + 7) / (5 + 7) = 1.0833...
        assertEquals(13.0 / 12.0, point.youUnitsPerCarb, 1e-9)
        // The model's: (5 + 7) / (5 + 7) = 1.0
        assertEquals(1.0, point.modelUnitsPerCarb!!, 1e-9)
        assertEquals(2, point.meals)
    }

    @Test
    fun mealsWithoutASuggestionStillCountForYourRatio() {
        val entries = listOf(
            entry(id = 1L, hour = 8, carbs = 4f, bolus = 4.0, suggested = null)
        )

        val point = ModelVsHuman.build(entries, samples = emptyList()).ratioByHour.single()

        assertEquals(1.0, point.youUnitsPerCarb, 1e-9)
        assertNull("nothing was suggested, so there is no model ratio to report", point.modelUnitsPerCarb)
    }

    @Test
    fun hoursAreSortedAndCarbslessMealsAreIgnored() {
        val entries = listOf(
            entry(id = 1L, hour = 19, carbs = 6f, bolus = 6.0, suggested = 6.0),
            entry(id = 2L, hour = 8, carbs = 5f, bolus = 5.0, suggested = 5.0),
            entry(id = 3L, hour = 8, carbs = 0f, bolus = 2.0, suggested = 2.0) // a correction dose
        )

        val hours = ModelVsHuman.build(entries, samples = emptyList()).ratioByHour.map { it.hourOfDay }

        assertEquals(listOf(8, 19), hours)
    }

    private fun entry(
        id: Long,
        hour: Int,
        carbs: Float,
        bolus: Double,
        suggested: Double?
    ) = DiaryEntity(
        id = id,
        timestamp = timestampAt(hour, id.toInt()),
        glucose = 120,
        carbs = carbs,
        bolus = bolus,
        basis = 0,
        suggestedBolus = suggested,
        predictedGlucose = null
    )

    private fun timestampAt(hour: Int, dayOffset: Int): Long =
        java.time.LocalDateTime.of(2026, 6, 1 + (dayOffset % 27), hour, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
