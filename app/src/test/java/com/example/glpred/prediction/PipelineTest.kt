package com.example.glpred.prediction

import com.example.glpred.data.CgmReadingEntity
import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.CgmSeries
import com.example.glpred.preprocessing.TrainingSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * End-to-end pipeline check with synthetic data: canned entries + a canned CGM series are wired
 * through [TrainingSamples] -> [RidgeModel] -> [DoseSweep].
 *
 * This is the loop that real data would drive, exercised without a database or an export - the app
 * has no diary history yet, so this is the only way to prove the stages fit together.
 */
class PipelineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val hourMs = 3_600_000L
    private val minuteMs = 60_000L

    @Test
    fun theFullPipelineRecommendsTheDoseThatLandsInBand() {
        // Underlying truth: 4 h glucose = 260 - 6 * bolus, so 90 mg/dL needs ~28 units and the
        // target band 90..140 is reached from ~20 units upwards.
        val entries = (4..14).map { bolus ->
            // 12 h apart, so the +-5 h anchor windows of neighbouring entries cannot collide and
            // get averaged into a bucket.
            entry(id = bolus.toLong(), wallClock = wallClock(2026, 6, 1, 8, 0) + bolus * 12 * hourMs, bolus = bolus.toDouble())
        }
        val readings = entries.flatMap { entry ->
            val wallClock = TrainingSamples.toWallClockMillis(entry.timestamp, zone)
            listOf(
                // Anchor readings either side of the 4 h target so the lookup can interpolate.
                reading(wallClock + 3 * hourMs, 260.0 - 6.0 * entry.bolus),
                reading(wallClock + 4 * hourMs - 5 * minuteMs, 260.0 - 6.0 * entry.bolus),
                reading(wallClock + 4 * hourMs + 5 * minuteMs, 260.0 - 6.0 * entry.bolus),
                reading(wallClock + 5 * hourMs, 260.0 - 6.0 * entry.bolus)
            )
        }

        val series = CgmSeries.from(readings, zone)
        val samples = TrainingSamples.build(entries, series, zone)
        assertEquals(11, samples.size)
        assertTrue("every entry must be labelled", samples.all { it.hasPrimaryLabel })

        val model = RidgeModel.fit(samples, lambda = 1e-6)
        assertNotNull(model)

        // Predict for a fresh meal at 8:00 with 6.0 units-of-carbs-worth of features.
        val features = samples.first().features().copyOf()
        features[0] = 180.0 // glucose
        features[1] = 8.0   // carbs
        features[2] = 0.0   // bolus, swept
        features[3] = 0.0   // basis

        val selection = DoseSweep.run(features, model!!::predict4h)

        assertTrue("selection must land in band", selection.inRange)
        assertTrue(
            "recommended dose should be inside the sweep range, was ${selection.units}",
            selection.units in SWEEP_MIN_UNITS..SWEEP_MAX_UNITS
        )
        assertTrue(
            "predicted 4 h glucose must be in band, was ${selection.predictedGlucoseMgDl}",
            selection.predictedGlucoseMgDl in TARGET_LOW_MG_DL..TARGET_HIGH_MG_DL
        )
        // More insulin lowers the prediction, so the sweep must be monotone - if this fails the
        // selection logic is picking from a nonsense curve.
        val curve = selection.curve
        assertTrue(
            "prediction should fall as the dose rises",
            curve.last().predictedGlucoseMgDl < curve.first().predictedGlucoseMgDl
        )
    }

    @Test
    fun entriesWithoutCgmCoverageStayPending() {
        val entries = (4..6).map { bolus ->
            entry(id = bolus.toLong(), wallClock = wallClock(2026, 6, 1, 8, 0) + bolus * hourMs, bolus = bolus.toDouble())
        }
        // CGM data stops well before any entry's 4 h horizon.
        val readings = entries.map { reading(TrainingSamples.toWallClockMillis(it.timestamp, zone), 150.0) }

        val samples = TrainingSamples.build(entries, CgmSeries.from(readings, zone), zone)

        assertEquals(3, samples.size)
        assertTrue("nothing may be labelled without coverage", samples.none { it.hasPrimaryLabel })
        assertEquals(0, TrainingSamples.labelled(samples).size)
    }

    @Test
    fun aSweepThatCannotReachTheBandIsReportedAsSuch() {
        // Constant prediction of 300 mg/dL no matter the dose.
        val selection = DoseSweep.run(DoubleArray(6)) { 300.0 }

        assertEquals(false, selection.inRange)
        assertEquals(300.0, selection.predictedGlucoseMgDl, 1e-9)
        assertEquals((SWEEP_MAX_UNITS - SWEEP_MIN_UNITS) / SWEEP_STEP_UNITS + 1, selection.curve.size.toDouble(), 1.0)
    }

    private fun entry(id: Long, wallClock: Long, bolus: Double): DiaryEntity {
        // Diary timestamps are real instants: convert the wall clock back through the zone.
        val instant = LocalDateTime.ofEpochSecond(wallClock / 1000, 0, ZoneOffset.UTC)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return DiaryEntity(
            id = id,
            timestamp = instant,
            glucose = 180,
            carbs = 8f,
            bolus = bolus,
            basis = 0
        )
    }

    private fun reading(wallClockMs: Long, glucose: Double) = CgmReadingEntity(
        timestampMs = wallClockMs,
        glucose = glucose
    )

    private fun wallClock(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
}
