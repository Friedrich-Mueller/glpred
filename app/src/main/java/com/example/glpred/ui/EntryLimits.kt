package com.example.glpred.ui

/**
 * Bounds for the four hand-entered numeric values, in one place.
 *
 * They exist because a fat-fingered entry used to be accepted and stored: `PLAN.md` records
 * `120111 mg/dL`, a basis of 105 units and a 25-unit suggestion reaching the diary on 03-11-2026, and
 * the response then was a draft reset rather than validation (AUDIT.md M3). Both doors - the entry
 * form and the History editor - now read the same ranges, so one cannot accept what the other
 * rejects.
 *
 * The bolus ceiling also governs the dose sweep: [com.example.glpred.prediction.SWEEP_MAX_UNITS] is
 * held to [BOLUS] by `SweepCeilingTest`, because a suggestion the user is not allowed to record is
 * worse than no suggestion at all.
 */
internal object EntryLimits {

    /** mg/dL. */
    val GLUCOSE = 20..600

    /** Carb units per meal. */
    val CARBS = 0.0..20.0

    /** Long-acting units. */
    val BASIS = 0..30

    /** Short-acting units per meal - the ceiling the dose sweep must also honour. */
    val BOLUS = 0.0..40.0

    fun glucose(raw: String): Result<Int> =
        raw.trim().toIntOrNull()?.takeIf { it in GLUCOSE }?.let { Result.success(it) }
            ?: fail("Blood glucose must be a whole number from ${GLUCOSE.start} to ${GLUCOSE.endInclusive} mg/dL.")

    fun carbs(raw: String): Result<Double> =
        number(raw)?.takeIf { it in CARBS }?.let { Result.success(it) }
            ?: fail("Carbohydrates must be between ${plain(CARBS.start)} and ${plain(CARBS.endInclusive)} units.")

    fun basis(raw: String): Result<Int> =
        raw.trim().toIntOrNull()?.takeIf { it in BASIS }?.let { Result.success(it) }
            ?: fail("Basis must be a whole number from ${BASIS.start} to ${BASIS.endInclusive} units.")

    fun bolus(raw: String): Result<Double> =
        number(raw)?.takeIf { it in BOLUS }?.let { Result.success(it) }
            ?: fail("Injected units must be between ${plain(BOLUS.start)} and ${plain(BOLUS.endInclusive)} units.")

    /** Accepts the comma decimal separator a German keyboard produces. */
    private fun number(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()

    /** "20", not "20.0", in a message the user reads. */
    private fun plain(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun fail(message: String): Result<Nothing> =
        Result.failure(IllegalArgumentException(message))
}
