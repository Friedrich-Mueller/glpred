package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model-math checks: the ridge solve is the one place where a silent error would make every
 * prediction meaningless while still producing numbers, so it is tested against relations that have
 * an exact answer.
 */
class RidgeModelTest {

    @Test
    fun recoversAnExactLinearRelation() {
        // y = 3*x0 - 2*x1 + 7
        val x = arrayOf(
            doubleArrayOf(1.0, 0.0),
            doubleArrayOf(2.0, 1.0),
            doubleArrayOf(3.0, 5.0),
            doubleArrayOf(4.0, 2.0)
        )
        val y = doubleArrayOf(3 * 1 - 2 * 0 + 7.0, 3 * 2 - 2 * 1 + 7.0, 3 * 3 - 2 * 5 + 7.0, 3 * 4 - 2 * 2 + 7.0)

        val model = RidgeMath.fitTarget(x, y, lambda = 0.0)!!

        assertEquals(3.0, model.coefficients[0], 1e-9)
        assertEquals(-2.0, model.coefficients[1], 1e-9)
        // Intercept is mean(y) because the design matrix is centred.
        assertEquals(y.average(), model.intercept, 1e-9)
        assertEquals(0.0, model.rmse, 1e-9)

        // ... and it reproduces a held-out point of the same relation.
        val prediction = model.intercept + model.coefficients[0] * (10.0 - x.map { it[0] }.average()) +
            model.coefficients[1] * (4.0 - x.map { it[1] }.average())
        assertEquals(3 * 10 - 2 * 4 + 7.0, prediction, 1e-9)
    }

    @Test
    fun aSingularSystemIsRejectedInsteadOfExploding() {
        // Two identical columns: no unique solution without a penalty.
        val x = arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 2.0), doubleArrayOf(3.0, 3.0))
        val y = doubleArrayOf(1.0, 2.0, 3.0)
        assertNull(RidgeMath.fitTarget(x, y, lambda = 0.0))
    }

    @Test
    fun thePenaltyMakesTheSameSystemSolvable() {
        val x = arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 2.0), doubleArrayOf(3.0, 3.0))
        val y = doubleArrayOf(1.0, 2.0, 3.0)
        val model = RidgeMath.fitTarget(x, y, lambda = 0.01)
        assertNotNull(model)
        // The penalty splits the weight between the two identical features.
        assertEquals(model!!.coefficients[0], model.coefficients[1], 1e-9)
        assertTrue(model.rmse < 0.1)
    }

    @Test
    fun fitsSamplesAndRefusesToFitTooFew() {
        val samples = listOf(
            sample(1L, 120.0, 4.0, 6.0, 0.0, after3h = 130.0, after4h = 112.0),
            sample(2L, 130.0, 6.0, 9.0, 0.0, after3h = 150.0, after4h = 151.0),
            sample(3L, 146.0, 8.0, 16.0, 12.0, after3h = 129.0, after4h = 129.5),
            sample(4L, 80.0, 10.0, 10.0, 12.0, after3h = 173.0, after4h = 173.6)
        )
        val model = RidgeModel.fit(samples)
        assertNotNull(model)
        assertEquals(6, model!!.featureCount)
        assertTrue(model.predict4h(samples[0].features()).isFinite())
        assertNotNull(model.predict3h(samples[0].features()))

        // Two points always admit a perfect line, so the fit is refused.
        assertNull(RidgeModel.fit(samples.take(2)))
    }

    @Test
    fun theSecondaryTargetIsDroppedWhenItHasTooFewLabels() {
        val samples = listOf(
            sample(1L, 120.0, 4.0, 6.0, 0.0, after3h = 130.0, after4h = 112.0),
            sample(2L, 130.0, 6.0, 9.0, 0.0, after3h = null, after4h = 151.0),
            sample(3L, 146.0, 8.0, 16.0, 12.0, after3h = null, after4h = 129.5),
            sample(4L, 80.0, 10.0, 10.0, 12.0, after3h = null, after4h = 173.6)
        )
        val model = RidgeModel.fit(samples)
        assertNotNull(model)
        assertNull(model!!.predict3h(samples[0].features()))
    }

    private fun sample(
        id: Long,
        glucose: Double,
        carbs: Double,
        bolus: Double,
        basis: Double,
        after3h: Double?,
        after4h: Double?
    ) = TrainingSample(
        entryId = id,
        // Midday, so the cyclical time features are well defined.
        timestampMs = 1_790_000_000_000L + id * 86_400_000L,
        glucose = glucose,
        carbs = carbs,
        bolus = bolus,
        basis = basis,
        after3h = after3h,
        after4h = after4h
    )
}
