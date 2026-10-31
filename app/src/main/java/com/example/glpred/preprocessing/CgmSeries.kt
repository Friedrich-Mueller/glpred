package com.example.glpred.preprocessing

import com.example.glpred.data.CgmReadingEntity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The imported CGM readings reduced to one glucose value per usable minute, in wall-clock order.
 *
 * Cleaning applied here, and only here:
 *
 *  - minute buckets whose readings disagree by more than [MAX_SPREAD_MG_DL] are dropped. The export
 *    carries a historic and a scan reading for the same minute on ~11% of minutes; the median
 *    disagreement is 3 mg/dL and averaging is fine, but the tail reaches 90 mg/dL and those buckets
 *    would blend two genuinely different measurements.
 *  - the daylight-saving fall-back hour is dropped. There, one wall-clock hour occurs twice, so
 *    every minute in it carries readings from two instants an hour apart. `getValidOffsets` answers
 *    "does this wall clock exist twice in this zone", which self-adapts to future transitions.
 *
 * No 1-minute grid is materialised: pandas-style resampling would create ~560k samples to answer
 * a few dozen lookups, so [valueAt] interpolates on demand instead.
 */
class CgmSeries private constructor(
    /** Minute starts (wall-clock millis, as stored by [CgmReadingEntity.timestampMs]), ascending. */
    private val minutes: LongArray,
    private val values: DoubleArray
) {

    val size: Int get() = minutes.size

    /**
     * Value at the minute nearest to [targetMs] that has a real value, within [TOLERANCE_MIN].
     *
     * Mirrors the notebook's `merge_asof(direction='nearest', tolerance='10min')` on an
     * interpolated 1-minute grid: the returned value is evaluated at the nearest *available*
     * minute, not at [targetMs] itself. Returns null when the horizon is not covered by the
     * imported data yet.
     */
    fun valueAt(targetMs: Long): Double? {
        if (minutes.isEmpty()) return null
        val targetMinute = floorToMinute(targetMs)
        for (offsetMinutes in 0L..TOLERANCE_MIN) {
            for (sign in intArrayOf(1, -1)) {
                if (offsetMinutes == 0L && sign == -1) continue
                val minute = targetMinute + sign * offsetMinutes * MINUTE_MS
                val value = interpolateAt(minute) ?: continue
                return value
            }
        }
        return null
    }

    /** Interpolated value at [minuteMs] if that minute lies in or on a bridgeable anchor gap. */
    private fun interpolateAt(minuteMs: Long): Double? {
        if (minutes.isEmpty()) return null
        val idx = upperBound(minuteMs)
        // Exact anchor hit.
        if (idx > 0 && minutes[idx - 1] == minuteMs) return values[idx - 1]
        if (idx == 0 || idx >= minutes.size) return null // no extrapolation beyond the ends

        val after = minutes[idx]
        val before = minutes[idx - 1]
        if (after - before > MAX_BRIDGED_GAP_MIN * MINUTE_MS) return null

        val fraction = (minuteMs - before).toDouble() / (after - before).toDouble()
        return values[idx - 1] + fraction * (values[idx] - values[idx - 1])
    }

    /** First index whose minute is greater than [minuteMs]. */
    private fun upperBound(minuteMs: Long): Int {
        var low = 0
        var high = minutes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (minutes[mid] <= minuteMs) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val MAX_SPREAD_MG_DL = 15.0
        const val TOLERANCE_MIN = 10L

        /**
         * Largest anchor gap that still gets bridged. pandas' `interpolate(limit=14)` fills at most
         * 14 consecutive missing minutes, i.e. it bridges anchors up to 15 minutes apart.
         */
        const val MAX_BRIDGED_GAP_MIN = 15L

        fun from(
            readings: List<CgmReadingEntity>,
            zone: ZoneId = ZoneId.systemDefault()
        ): CgmSeries {
            if (readings.isEmpty()) return CgmSeries(LongArray(0), DoubleArray(0))

            // Bucket by wall-clock minute, tracking spread so ambiguous minutes can be dropped.
            val sorted = readings.sortedBy { it.timestampMs }
            val minutes = LongArray(sorted.size)
            val values = DoubleArray(sorted.size)
            var count = 0

            var i = 0
            while (i < sorted.size) {
                val minute = floorToMinute(sorted[i].timestampMs)
                var sum = 0.0
                var min = Double.MAX_VALUE
                var max = -Double.MAX_VALUE
                var n = 0
                while (i < sorted.size && floorToMinute(sorted[i].timestampMs) == minute) {
                    val glucose = sorted[i].glucose
                    sum += glucose
                    if (glucose < min) min = glucose
                    if (glucose > max) max = glucose
                    n++
                    i++
                }
                if (max - min > MAX_SPREAD_MG_DL) continue
                if (isAmbiguousWallClock(minute, zone)) continue

                minutes[count] = minute
                values[count] = sum / n
                count++
            }
            return CgmSeries(minutes.copyOf(count), values.copyOf(count))
        }

        fun floorToMinute(ms: Long): Long = ms - Math.floorMod(ms, MINUTE_MS)

        /**
         * True when this wall clock occurs twice in [zone] (DST fall-back) or never occurred
         * (spring-forward hole). Such minutes cannot be attributed to a single instant.
         */
        private fun isAmbiguousWallClock(minuteMs: Long, zone: ZoneId): Boolean {
            val wallClock = LocalDateTime.ofEpochSecond(
                Math.floorDiv(minuteMs, 1000L), 0, ZoneOffset.UTC
            )
            return zone.rules.getValidOffsets(wallClock).size != 1
        }
    }
}
