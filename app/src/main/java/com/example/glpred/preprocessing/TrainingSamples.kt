package com.example.glpred.preprocessing

import com.example.glpred.data.DiaryEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One training row: the recorded meal vector plus the glucose it led to.
 *
 * Both labels are kept even though only [after4h] is trained on in v1 - the 3 h value costs one
 * extra lookup and lets a future version train a second target without a re-import.
 */
data class TrainingSample(
    val entryId: Long,
    val timestampMs: Long,
    val glucose: Double,
    val carbs: Double,
    val bolus: Double,
    val basis: Double,
    val after3h: Double?,
    val after4h: Double?
) {
    /** Usable for training at the primary horizon. */
    val hasPrimaryLabel: Boolean get() = after4h != null

    /** Feature order: glucose, carbs, bolus, basis, time_sin, time_cos. */
    fun features(): DoubleArray {
        val minuteOfDay = minuteOfDay(timestampMs)
        val angle = 2.0 * PI * minuteOfDay / MINUTES_PER_DAY
        return doubleArrayOf(glucose, carbs, bolus, basis, sin(angle), cos(angle))
    }

    companion object {
        const val MINUTES_PER_DAY = 1440.0

        /** Minute of the local wall clock, matching the notebook's cyclical time features. */
        fun minuteOfDay(epochMs: Long): Int {
            val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            return local.hour * 60 + local.minute
        }

        val FEATURE_NAMES = listOf("glucose", "carbs", "bolus", "basis", "time_sin", "time_cos")
    }
}

/**
 * Joins diary entries with the CGM series to produce labelled training rows.
 *
 * Note the encoding boundary: [DiaryEntity.timestamp] is a real epoch millis (written with the
 * device zone), while [CgmSeries] works in wall-clock millis. [toWallClockMillis] converts one to
 * the other exactly once, here, so no other code has to think about it.
 */
object TrainingSamples {

    fun build(
        entries: List<DiaryEntity>,
        series: CgmSeries,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<TrainingSample> = entries.map { entry ->
        val wallClock = toWallClockMillis(entry.timestamp, zone)
        val labels = LabelGenerator.labelsFor(wallClock, series)
        TrainingSample(
            entryId = entry.id,
            timestampMs = entry.timestamp,
            glucose = entry.glucose.toDouble(),
            carbs = entry.carbs.toDouble(),
            bolus = entry.bolus,
            basis = entry.basis.toDouble(),
            after3h = labels.after3h,
            after4h = labels.after4h
        )
    }

    /** Rows that can train the primary (4 h) model. */
    fun labelled(samples: List<TrainingSample>): List<TrainingSample> = samples.filter { it.hasPrimaryLabel }

    /**
     * Re-encodes an epoch instant as wall-clock millis: the local wall clock in [zone], read as if
     * it were UTC. This is the same encoding [CgmSeries] uses and the only place it is created.
     */
    fun toWallClockMillis(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        return local.toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
