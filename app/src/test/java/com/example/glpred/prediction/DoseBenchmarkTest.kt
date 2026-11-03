package com.example.glpred.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic both plots are drawn from: the counterfactual projection, and the three reasons
 * a meal cannot be compared. Every expected value below can be checked on paper from the inputs in the
 * same test.
 *
 * There is no verdict, no win count, no CUSUM and no binomial interval to pin any more - they were
 * removed with the charts that drew them.
 */
class DoseBenchmarkTest {

    private fun meal(
        id: Long = 1L,
        injected: Double = 4.5,
        suggested: Double = 6.0,
        actual: Double = 190.0,
        slope: Double? = -45.0,
        carbs: Double = 4.0,
        start: Double = 150.0,
        predicted: Double = 92.0
    ) = DoseBenchmark.Meal(
        entryId = id,
        hourOfDay = 13,
        startGlucose = start,
        carbs = carbs,
        injectedUnits = injected,
        suggestedUnits = suggested,
        predictedForSuggestion = predicted,
        actual4h = actual,
        slopeMgDlPerUnit = slope
    )

    @Test
    fun `the model's dose is projected from the observed outcome`() {
        // delta = +1.5, projected = 190 + (-45 * 1.5) = 122.5
        val score = DoseBenchmark.evaluate(listOf(meal())).scores.single()

        assertEquals(1.5, score.deltaUnits, 1e-9)
        assertEquals(122.5, score.projectedModelGlucose!!, 1e-9)
        assertTrue(score.isScored)
        assertNull(score.exclusion)
    }

    @Test
    fun `a suggestion below the injected dose projects upward`() {
        // delta = -1, projected = 120 + (-45 * -1) = 165
        val score = DoseBenchmark.evaluate(
            listOf(meal(injected = 6.0, suggested = 5.0, actual = 120.0))
        ).scores.single()

        assertEquals(165.0, score.projectedModelGlucose!!, 1e-9)
    }

    @Test
    fun `the projection is linear in the dose gap and does not clamp at zero`() {
        // delta = +5 at 50 mg/dL per unit takes 250 to 0: projected as-is, not floored.
        val deep = DoseBenchmark.evaluate(
            listOf(meal(injected = 1.0, suggested = 6.0, actual = 250.0, slope = -50.0))
        ).scores.single()

        assertEquals(0.0, deep.projectedModelGlucose!!, 1e-9)
        assertTrue(deep.isScored)
    }

    @Test
    fun `the model's claimed level cancels out of the projection`() {
        // A model that claims 92 and one that claims 300 project the same dose outcome.
        val modest = DoseBenchmark.evaluate(listOf(meal(predicted = 92.0))).scores.single()
        val boastful = DoseBenchmark.evaluate(listOf(meal(predicted = 300.0))).scores.single()

        assertEquals(modest.projectedModelGlucose!!, boastful.projectedModelGlucose!!, 1e-9)
    }

    @Test
    fun `doses within half a unit are the same decision`() {
        val metric = DoseBenchmark.evaluate(listOf(meal(injected = 4.5, suggested = 4.9)))
        val score = metric.scores.single()

        assertEquals(DoseBenchmark.Exclusion.SAME_DOSE, score.exclusion)
        assertNull(score.projectedModelGlucose)
        assertFalse(score.isScored)
        assertEquals(1, metric.sameDose)
    }

    @Test
    fun `a missing slope is a different exclusion from an inverted one`() {
        val missing = DoseBenchmark.evaluate(listOf(meal(slope = null)))
        val inverted = DoseBenchmark.evaluate(listOf(meal(slope = 2.5)))

        assertEquals(DoseBenchmark.Exclusion.NO_SLOPE, missing.scores.single().exclusion)
        assertEquals(1, missing.noSlope)
        assertEquals(0, missing.invertedCurve)

        assertEquals(DoseBenchmark.Exclusion.INVERTED_CURVE, inverted.scores.single().exclusion)
        assertEquals(1, inverted.invertedCurve)
        assertEquals(0, inverted.scored)
    }

    @Test
    fun `the slope boundary is inclusive at one mg per dL per unit`() {
        val atBoundary = DoseBenchmark.evaluate(listOf(meal(slope = -1.0))).scores.single()
        val justInside = DoseBenchmark.evaluate(listOf(meal(slope = -0.999))).scores.single()

        assertTrue("a slope of -1.0 must still be projected", atBoundary.isScored)
        assertEquals(DoseBenchmark.Exclusion.INVERTED_CURVE, justInside.exclusion)
    }

    @Test
    fun `the three exclusions are counted separately and nothing is dropped`() {
        val metric = DoseBenchmark.evaluate(
            listOf(
                meal(id = 1L),
                meal(id = 2L, injected = 2.0, suggested = 4.0, actual = 200.0),
                meal(id = 3L, injected = 4.5, suggested = 4.6),
                meal(id = 4L, slope = 2.5),
                meal(id = 5L, slope = null)
            )
        )

        assertEquals(5, metric.scores.size)
        assertEquals(2, metric.scored)
        assertEquals(1, metric.sameDose)
        assertEquals(1, metric.invertedCurve)
        assertEquals(1, metric.noSlope)
    }

    @Test
    fun `an empty or fully excluded history has no evidence`() {
        assertEquals(DoseBenchmark.EMPTY, DoseBenchmark.evaluate(emptyList()))

        val noEvidence = DoseBenchmark.evaluate(listOf(meal(slope = 2.5), meal(slope = null)))
        assertEquals(0, noEvidence.scored)
        assertFalse(noEvidence.hasEvidence)
    }

    @Test
    fun `evaluating twice gives the same answer`() {
        val meals = listOf(meal(), meal(id = 2L, injected = 3.0, suggested = 5.0))
        assertEquals(DoseBenchmark.evaluate(meals), DoseBenchmark.evaluate(meals))
    }
}
