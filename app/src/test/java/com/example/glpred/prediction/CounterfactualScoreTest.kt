package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probability is the number the user will act on, so it is pinned against hand arithmetic.
 *
 * The folded fit is exercised only lightly: it needs a half-sane regression to say anything, and a
 * synthetic fixture that produced one would mostly be testing the fixture. What must be exact is the
 * mapping from "how well is the sensitivity known" to "how confident is the verdict", because that is
 * where a metric silently becomes overconfident.
 */
class CounterfactualScoreTest {

    @Test
    fun `landing at 250 after 4 units, model wanting 6, is a confident win`() {
        // 50 mg/dL per unit: 6 u projects to 150, loss 3600, against your 160^2 = 25600.
        val probability = CounterfactualScore.probabilityModelBetter(
            actualMgDl = 250.0,
            injectedUnits = 4.0,
            suggestedUnits = 6.0,
            isfMean = 50.0,
            isfSd = 5.0
        )
        assertTrue("expected a confident win, got $probability", probability > 0.99)
    }

    @Test
    fun `a suggestion that overshoots is your win`() {
        // 250 after 4 u, model wanted 9: 50 mg/dL per unit projects to 0, a hypo tripled to 24300.
        val probability = CounterfactualScore.probabilityModelBetter(
            actualMgDl = 250.0,
            injectedUnits = 4.0,
            suggestedUnits = 9.0,
            isfMean = 50.0,
            isfSd = 5.0
        )
        assertTrue("expected a loss, got $probability", probability < 0.01)
    }

    @Test
    fun `an unknown sensitivity leaves the verdict undecided`() {
        val probability = CounterfactualScore.probabilityModelBetter(
            actualMgDl = 250.0,
            injectedUnits = 4.0,
            suggestedUnits = 6.0,
            isfMean = 50.0,
            isfSd = 400.0
        )
        assertTrue("expected roughly a coin flip, got $probability", probability in 0.4..0.6)
    }

    @Test
    fun `the same dose is not a contest`() {
        val probability = CounterfactualScore.probabilityModelBetter(
            actualMgDl = 250.0,
            injectedUnits = 4.0,
            suggestedUnits = 4.2,
            isfMean = 50.0,
            isfSd = 5.0
        )
        assertEquals(0.5, probability, 1e-9)
    }

    @Test
    fun `the loss penalises landing low, and a projected hypo cannot win`() {
        // A projection under 70 carries a floor penalty, so it is never the better dose even when the
        // meal it is compared against ended at 250.
        assertTrue(
            "a hypo must cost more than an equal miss high",
            CounterfactualScore.errorOf(0.0) > CounterfactualScore.errorOf(250.0)
        )
        assertEquals(90.0 * 90.0, CounterfactualScore.errorOf(180.0), 1e-9)
        assertEquals(40.0 * 40.0, CounterfactualScore.errorOf(130.0), 1e-9)
        assertEquals(
            CounterfactualScore.HYPO_FLOOR_PENALTY + 70.0 * 70.0 * CounterfactualScore.HYPO_WEIGHT,
            CounterfactualScore.errorOf(0.0),
            1e-9
        )
    }

    @Test
    fun `meals without a suggestion are not judged and nothing is claimed`() {
        val entries = listOf(
            DiaryEntity(
                id = 1,
                timestamp = 1000,
                glucose = 120,
                carbs = 5f,
                bolus = 4.0,
                basis = 0,
                suggestedBolus = null
            )
        )
        val samples = listOf(
            TrainingSample(
                entryId = 1,
                timestampMs = 1000,
                glucose = 120.0,
                carbs = 5.0,
                bolus = 4.0,
                basis = 0.0,
                after3h = null,
                after4h = 250.0
            )
        )

        val result = CounterfactualScore.build(entries, samples)

        assertTrue(result.meals.isEmpty())
        assertFalse(result.hasEvidence)
        // No external figure is supplied, so an unknown sensitivity stays unknown rather than being
        // replaced by a textbook value.
        assertTrue(result.estimate.isEmpty)
        assertEquals(0.0, result.estimate.meanMgDlPerUnit, 1e-9)
    }

    @Test
    fun `an empty history claims nothing at all`() {
        val none = CounterfactualScore.build(emptyList(), emptyList())
        assertTrue(none.estimate.isEmpty)
        assertEquals(0, none.estimate.meals)
        assertEquals(0.0, none.estimate.meanMgDlPerUnit, 1e-9)
        assertEquals(0.0, none.estimate.sdMgDlPerUnit, 1e-9)
    }
}
