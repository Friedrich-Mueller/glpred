package com.example.glpred.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds are the fix for entries that were accepted and stored: `PLAN.md` records a
 * `120111 mg/dL` glucose, a basis of 105 units and a 25-unit suggestion reaching the diary on
 * 03-11-2026. Both doors - the entry form and the History editor - validate through [EntryLimits],
 * so these cases cover both.
 *
 * The boundaries are tested harder than the middle: an off-by-one at the limit is the realistic bug,
 * and the limit is the value a user actually types when they are at the edge of what they use.
 */
class EntryLimitsTest {

    @Test
    fun glucoseAcceptsOnlyWholeNumbersInRange() {
        assertEquals(20, EntryLimits.glucose("20").getOrThrow())
        assertEquals(600, EntryLimits.glucose("600").getOrThrow())
        assertEquals(120, EntryLimits.glucose(" 120 ").getOrThrow())
        assertRejected(EntryLimits.glucose("19"))
        assertRejected(EntryLimits.glucose("601"))
        assertRejected(EntryLimits.glucose("120111"))
        assertRejected(EntryLimits.glucose("12.5"))
        assertRejected(EntryLimits.glucose(""))
        assertRejected(EntryLimits.glucose("abc"))
    }

    @Test
    fun carbsAcceptZeroToTwentyIncludingCommaDecimals() {
        assertEquals(0.0, EntryLimits.carbs("0").getOrThrow(), 1e-9)
        assertEquals(20.0, EntryLimits.carbs("20").getOrThrow(), 1e-9)
        assertEquals(6.5, EntryLimits.carbs("6,5").getOrThrow(), 1e-9)
        assertRejected(EntryLimits.carbs("20.5"))
        assertRejected(EntryLimits.carbs("-1"))
    }

    @Test
    fun basisAcceptsZeroToThirtyWholeUnits() {
        assertEquals(0, EntryLimits.basis("0").getOrThrow())
        assertEquals(30, EntryLimits.basis("30").getOrThrow())
        assertRejected(EntryLimits.basis("31"))
        assertRejected(EntryLimits.basis("105"))
    }

    @Test
    fun bolusAcceptsZeroToFortyIncludingHalfUnits() {
        assertEquals(0.0, EntryLimits.bolus("0").getOrThrow(), 1e-9)
        assertEquals(40.0, EntryLimits.bolus("40").getOrThrow(), 1e-9)
        assertEquals(6.5, EntryLimits.bolus("6,5").getOrThrow(), 1e-9)
        assertRejected(EntryLimits.bolus("40.5"))
        assertRejected(EntryLimits.bolus("100"))
    }

    @Test
    fun everyRejectionCarriesTheAcceptedRange() {
        val glucose = EntryLimits.glucose("700").exceptionOrNull()?.message
        assertTrue("a rejection must explain itself", !glucose.isNullOrBlank())
        assertTrue(glucose!!.contains("20") && glucose.contains("600"))

        val bolus = EntryLimits.bolus("50").exceptionOrNull()?.message
        assertTrue(bolus!!.contains("40"))
    }

    private fun assertRejected(result: Result<*>) {
        assertTrue("expected a rejection, got ${result.getOrNull()}", result.isFailure)
    }
}
