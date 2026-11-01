package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis screen draws conclusions about a person, so its arithmetic is pinned here: the ratio
 * derivation, the band boundaries, the follow tolerance, and the fact that empty buckets report
 * nothing rather than zero.
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
    fun followingIsJudgedWithinHalfAUnit() {
        val entries = listOf(
            entry(id = 1L, hour = 12, carbs = 5f, bolus = 5.5, suggested = 5.0), // followed
            entry(id = 2L, hour = 12, carbs = 5f, bolus = 6.0, suggested = 5.0), // 1.0 over: not
            entry(id = 3L, hour = 12, carbs = 5f, bolus = 4.5, suggested = 5.0)  // followed
        )
        val samples = entries.map { sample(it.id, after4h = 120.0) }

        val outcome = ModelVsHuman.build(entries, samples).outcome

        assertEquals(2, outcome.followed.meals)
        assertEquals(1, outcome.notFollowed.meals)
    }

    @Test
    fun theBandIsInclusiveAtBothEnds() {
        val entries = listOf(
            entry(id = 1L, hour = 12, carbs = 5f, bolus = 5.0, suggested = 5.0),
            entry(id = 2L, hour = 12, carbs = 5f, bolus = 5.0, suggested = 5.0),
            entry(id = 3L, hour = 12, carbs = 5f, bolus = 5.0, suggested = 5.0)
        )
        val samples = listOf(
            sample(1L, after4h = 90.0),   // in
            sample(2L, after4h = 140.0),  // in
            sample(3L, after4h = 141.0)   // out
        )

        val bucket = ModelVsHuman.build(entries, samples).outcome.followed

        assertEquals(3, bucket.meals)
        assertEquals(2, bucket.inBand)
        assertEquals(2.0 / 3.0, bucket.inBandShare!!, 1e-9)
    }

    @Test
    fun mealsWithoutAStoredSuggestionCannotJudgeTheModel() {
        // Entered before any model existed: no suggestion, so it says nothing about following it.
        val entries = listOf(entry(id = 1L, hour = 12, carbs = 5f, bolus = 5.0, suggested = null))
        val samples = listOf(sample(1L, after4h = 120.0))

        val analysis = ModelVsHuman.build(entries, samples)

        assertTrue(analysis.outcome.followed.meals == 0 && analysis.outcome.notFollowed.meals == 0)
        assertNull("no evidence is reported as nothing, never as 0 %", analysis.outcome.followed.inBandShare)
        assertEquals(1, analysis.labelledMeals)
    }

    @Test
    fun anUnlabelledMealCannotCountAsInOrOutOfBand() {
        val entries = listOf(entry(id = 1L, hour = 12, carbs = 5f, bolus = 5.0, suggested = 5.0))

        // No 4 h label yet.
        val analysis = ModelVsHuman.build(entries, listOf(sample(1L, after4h = null)))

        assertEquals(0, analysis.outcome.followed.meals)
        assertEquals(0, analysis.labelledMeals)
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

    private fun sample(id: Long, after4h: Double?) = TrainingSample(
        entryId = id,
        timestampMs = timestampAt(12, id.toInt()),
        glucose = 120.0,
        carbs = 5.0,
        bolus = 5.0,
        basis = 0.0,
        after3h = null,
        after4h = after4h
    )

    private fun timestampAt(hour: Int, dayOffset: Int): Long =
        java.time.LocalDateTime.of(2026, 6, 1 + (dayOffset % 27), hour, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
