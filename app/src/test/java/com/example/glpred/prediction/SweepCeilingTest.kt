package com.example.glpred.prediction

import com.example.glpred.ui.EntryLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep has to reach every dose the diary can record, and nothing beyond it.
 *
 * A ceiling below the entry maximum offers a dose the form then rejects; a ceiling above it sweeps
 * doses that can never be stored. The two numbers are therefore held together here rather than by a
 * comment (AUDIT.md M3, raised to 40 units on 05-11-2026).
 */
class SweepCeilingTest {

    @Test
    fun theSweepEndsAtTheLargestRecordableBolus() {
        assertEquals(EntryLimits.BOLUS.endInclusive, SWEEP_MAX_UNITS, 0.0)
    }

    @Test
    fun everySweptDoseIsRecordable() {
        val doses = generateSequence(0) { it + 1 }
            .map { SWEEP_MIN_UNITS + it * SWEEP_STEP_UNITS }
            .takeWhile { it <= SWEEP_MAX_UNITS + 1e-9 }
            .toList()

        assertTrue("every swept dose must be recordable", doses.all { it in EntryLimits.BOLUS })
        assertEquals(79, doses.size)                 // 1..40 in half units
        assertEquals(40.0, doses.last(), 1e-9)
    }
}
