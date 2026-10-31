package com.example.glpred.preprocessing

/**
 * Turns a diary entry into regression labels by looking up the CGM series N hours later.
 *
 * Two horizons, both stored on every sample:
 *
 *  - [PRIMARY_HORIZON_HOURS] (4 h) is what v1 trains and optimises on. Fast-acting insulin is
 *    largely spent by then, so it is the "did the dose actually work" view and the one that
 *    protects against a late drop that a 3 h view hides.
 *  - [SECONDARY_HORIZON_HOURS] (3 h) is kept alongside because smaller doses are finished earlier;
 *    it is stored and displayed even though the dose choice does not use it yet.
 *
 * A label is null when the horizon is not covered by imported CGM data yet - the entry is simply
 * pending, not broken.
 */
object LabelGenerator {

    const val PRIMARY_HORIZON_HOURS = 4
    const val SECONDARY_HORIZON_HOURS = 3

    private const val MILLIS_PER_HOUR = 3_600_000L

    data class Labels(
        val after3h: Double?,
        val after4h: Double?
    ) {
        /** Whether the entry can be used for training at the primary horizon. */
        val hasPrimaryLabel: Boolean get() = after4h != null
    }

    fun labelsFor(entryTimestampMs: Long, series: CgmSeries): Labels = Labels(
        after3h = series.valueAt(entryTimestampMs + SECONDARY_HORIZON_HOURS * MILLIS_PER_HOUR),
        after4h = series.valueAt(entryTimestampMs + PRIMARY_HORIZON_HOURS * MILLIS_PER_HOUR)
    )
}
