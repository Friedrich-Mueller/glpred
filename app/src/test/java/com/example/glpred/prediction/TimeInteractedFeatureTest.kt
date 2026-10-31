package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * The point of feature set B: the carb factor and the insulin sensitivity may vary across the day.
 *
 * These tests pin the two things that matter - it *can* express a time-varying factor, and it
 * collapses back to a single constant factor when the data shows no time dependence (which matters,
 * because the four interaction columns are otherwise four chances to fit noise).
 */
class TimeInteractedFeatureTest {

    @Test
    fun theCarbFactorCanDifferBetweenBreakfastAndDinner() {
        // Breakfast: 3.0 mg/dL per carb unit. Dinner: 0.8. Same starting glucose, no insulin.
        val samples = mutableListOf<TrainingSample>()
        var id = 1L
        for ((hour, factor) in listOf(8 to 3.0, 18 to 0.8)) {
            for (carbs in listOf(4.0, 6.0, 8.0, 10.0)) {
                samples += sample(id++, atHour(hour), carbs = carbs, after4h = 80.0 + factor * carbs)
            }
        }

        val model = RidgeModel.fit(samples)
        assertNotNull(model)

        val breakfast = model!!.predict4h(featuresAt(atHour(8), carbs = 8.0))
        val dinner = model.predict4h(featuresAt(atHour(18), carbs = 8.0))

        assertTrue(
            "the same meal must be predicted higher at breakfast ($breakfast) than at dinner ($dinner)",
            breakfast - dinner > 5.0
        )
    }

    @Test
    fun withoutATimeSignalTheFactorStaysConstant() {
        // Identical relation at both hours: there is nothing to learn about time.
        val samples = mutableListOf<TrainingSample>()
        var id = 11L
        for (hour in listOf(8, 18)) {
            for (carbs in listOf(4.0, 6.0, 8.0, 10.0)) {
                samples += sample(id++, atHour(hour), carbs = carbs, after4h = 80.0 + 1.5 * carbs)
            }
        }

        val model = RidgeModel.fit(samples)
        assertNotNull(model)

        val breakfast = model!!.predict4h(featuresAt(atHour(8), carbs = 8.0))
        val dinner = model.predict4h(featuresAt(atHour(18), carbs = 8.0))

        assertTrue(
            "with no time signal the predictions must agree, got $breakfast vs $dinner",
            abs(breakfast - dinner) < 3.0
        )
    }

    private fun featuresAt(timestampMs: Long, carbs: Double): DoubleArray =
        TrainingSample.featuresOf(
            glucose = 120.0,
            carbs = carbs,
            bolus = 0.0,
            basis = 0.0,
            timestampMs = timestampMs
        )

    private fun sample(id: Long, timestampMs: Long, carbs: Double, after4h: Double) = TrainingSample(
        entryId = id,
        timestampMs = timestampMs,
        glucose = 120.0,
        carbs = carbs,
        bolus = 0.0,
        basis = 0.0,
        after3h = null,
        after4h = after4h
    )

    /** A fixed local instant, so the test does not depend on when it is run. */
    private fun atHour(hour: Int): Long = LocalDateTime.of(2026, 6, 1, hour, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
