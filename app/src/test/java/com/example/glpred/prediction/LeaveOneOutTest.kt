package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LOO measure is only trustworthy if it *fails* the model that memorises. These tests pin both
 * directions: an overfitted model must look much worse under LOO than in-sample, and a genuinely
 * linear relation must keep a small LOO error.
 */
class LeaveOneOutTest {

    @Test
    fun overfittingLooksMuchWorseUnderLoo() {
        // Five rows and six features: in-sample fits almost perfectly, which is exactly the trap.
        val samples = listOf(
            sample(1L, 120.0, 4.0, 6.0, 0.0, hour = 8, after4h = 100.0),
            sample(2L, 160.0, 6.0, 9.0, 0.0, hour = 12, after4h = 160.0),
            sample(3L, 90.0, 10.0, 16.0, 12.0, hour = 18, after4h = 90.0),
            sample(4L, 210.0, 3.0, 4.0, 12.0, hour = 20, after4h = 210.0),
            sample(5L, 150.0, 8.0, 12.0, 0.0, hour = 15, after4h = 130.0)
        )
        val lambda = 1e-6
        val inSample = RidgeModel.fit(samples, lambda)!!.model4h.rmse
        val loo = LeaveOneOut.rmse4h(samples, lambda)

        assertNotNull(loo)
        assertTrue("in-sample should be tiny for 5 rows / 6 features, was $inSample", inSample < 1.0)
        assertTrue(
            "LOO must expose the overfit: in-sample=$inSample loo=${loo!!.rmse}",
            loo.rmse > 10 * inSample && loo.rmse > 10.0
        )
        assertTrue("every row should be a fold", loo.folds == 5)
    }

    @Test
    fun aRealLinearRelationKeepsASmallLooError() {
        // 4 h glucose depends on the dose only: 200 - 4*bolus. Other features are held constant.
        val samples = (4..13).map { dose ->
            sample(
                id = dose.toLong(),
                glucose = 140.0,
                carbs = 6.0,
                bolus = dose.toDouble(),
                basis = 0.0,
                hour = 12,
                after4h = 200.0 - 4.0 * dose
            )
        }
        val loo = LeaveOneOut.rmse4h(samples, lambda = 1e-6)

        assertNotNull(loo)
        assertTrue("a clean relation should validate well, was ${loo!!.rmse}", loo.rmse < 1.0)
    }

    @Test
    fun noLooWhenAFoldWouldFallBelowTheRowFloor() {
        val samples = listOf(
            sample(1L, 120.0, 4.0, 6.0, 0.0, hour = 8, after4h = 100.0),
            sample(2L, 160.0, 6.0, 9.0, 0.0, hour = 12, after4h = 160.0),
            sample(3L, 90.0, 10.0, 16.0, 12.0, hour = 18, after4h = 90.0)
        )
        // Leaving one out leaves two rows, below RidgeModel.MIN_SAMPLES.
        assertNull(LeaveOneOut.rmse4h(samples))
    }

    @Test
    fun theSecondaryTargetNeedsItsOwnLabels() {
        val samples = listOf(
            sample(1L, 120.0, 4.0, 6.0, 0.0, hour = 8, after4h = 100.0, after3h = null),
            sample(2L, 160.0, 6.0, 9.0, 0.0, hour = 12, after4h = 160.0, after3h = null),
            sample(3L, 90.0, 10.0, 16.0, 12.0, hour = 18, after4h = 90.0, after3h = null),
            sample(4L, 210.0, 3.0, 4.0, 12.0, hour = 20, after4h = 210.0, after3h = null),
            sample(5L, 150.0, 8.0, 12.0, 0.0, hour = 15, after4h = 130.0, after3h = 120.0)
        )
        assertNotNull("4 h has five labels", LeaveOneOut.rmse4h(samples))
        assertNull("3 h has one label", LeaveOneOut.rmse3h(samples))
    }

    private fun sample(
        id: Long,
        glucose: Double,
        carbs: Double,
        bolus: Double,
        basis: Double,
        hour: Int,
        after4h: Double?,
        after3h: Double? = null
    ) = TrainingSample(
        entryId = id,
        timestampMs = timestampFor(hour, id.toInt()),
        glucose = glucose,
        carbs = carbs,
        bolus = bolus,
        basis = basis,
        after3h = after3h,
        after4h = after4h
    )

    /** A local instant on a fixed day at [hour], offset by [dayOffset] so rows are distinct. */
    private fun timestampFor(hour: Int, dayOffset: Int): Long {
        val base = java.time.LocalDateTime.of(2026, 6, 1, 0, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return base + dayOffset * 2L * 86_400_000L + hour * 3_600_000L
    }
}
