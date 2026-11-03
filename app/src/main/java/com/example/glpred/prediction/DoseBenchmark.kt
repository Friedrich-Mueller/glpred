package com.example.glpred.prediction

/**
 * The human-vs-model comparison, as a pure function: meals in, two numbers per meal out.
 *
 * Nothing here refits, reads a database, touches Room or keeps state, so the whole thing is
 * exercisable from a plain JVM test. The one quantity it cannot derive for itself is the slope
 * `dG4/dU` the model had at the moment it made the suggestion, so that is an input (PLAN decision 10:
 * the slope is captured per suggestion, the comparison itself is derived on demand and never stored).
 *
 * For the linear model the projection collapses to one line:
 *
 *      projected(model dose) = actual + slope * (suggested - injected)
 *
 * The stored `predictedForSuggestion` cancels algebraically, which is worth knowing: the model's
 * claimed *level* never enters the comparison, only how much its prediction moves per unit of
 * insulin. A model therefore cannot win a meal by asserting that its own dose lands on target.
 *
 * **What this object deliberately does not do any more.** It does not judge who was closer, it does
 * not count wins, it does not run a CUSUM change detector, and it does not build a binomial interval.
 * Those steps collapsed the two distances into one bit per meal and discarded the magnitudes, which
 * are the only thing worth comparing - and the bit degenerates into ties as soon as the user starts
 * following the model. What remains is the projection itself plus the three reasons a meal cannot be
 * compared. The screen plots `|actual - 90|` against `|projected - 90|`; see `AnalysisScreen.MissPlot`.
 */
object DoseBenchmark {

    const val TARGET_MG_DL = 90.0

    /** Below this the two doses are the same decision and there is nothing to compare. */
    const val SAME_DOSE_TOLERANCE_UNITS = 0.5

    /** A slope shallower than this cannot be told apart from a flat or inverted one. */
    const val MIN_USABLE_SLOPE_MAGNITUDE = 1.0

    /** Meals in the recent window the second plot zooms into. */
    const val WINDOW_SIZE = 20

    /** Why a meal could not be compared. Every one is published as a count, never dropped silently. */
    enum class Exclusion { SAME_DOSE, INVERTED_CURVE, NO_SLOPE }

    /**
     * Everything the comparison may look at, and nothing else.
     *
     * [predictedForSuggestion] is kept because it is what the user was actually shown, even though the
     * projection cancels it; it is null when the stored row never carried one. [slopeMgDlPerUnit] is
     * null when the slope was not captured, which is a different situation from a captured but unusable
     * one, and both are counted rather than dropped.
     */
    data class Meal(
        val entryId: Long,
        val hourOfDay: Int,
        val startGlucose: Double,
        val carbs: Double,
        val injectedUnits: Double,
        val suggestedUnits: Double,
        val predictedForSuggestion: Double?,
        val actual4h: Double,
        val slopeMgDlPerUnit: Double?
    )

    data class Score(
        val meal: Meal,
        val deltaUnits: Double,
        /** The glucose the model's dose would have produced, projected from the observed outcome. */
        val projectedModelGlucose: Double?,
        val exclusion: Exclusion?
    ) {
        val isScored: Boolean get() = exclusion == null
    }

    data class Metric(
        val scores: List<Score>,
        val sameDose: Int,
        val invertedCurve: Int,
        val noSlope: Int
    ) {
        val scored: Int get() = scores.count { it.isScored }
        val hasEvidence: Boolean get() = scored > 0
    }

    val EMPTY = Metric(scores = emptyList(), sameDose = 0, invertedCurve = 0, noSlope = 0)

    fun evaluate(meals: List<Meal>): Metric {
        if (meals.isEmpty()) return EMPTY
        val scores = meals.map { scoreOf(it) }
        return Metric(
            scores = scores,
            sameDose = scores.count { it.exclusion == Exclusion.SAME_DOSE },
            invertedCurve = scores.count { it.exclusion == Exclusion.INVERTED_CURVE },
            noSlope = scores.count { it.exclusion == Exclusion.NO_SLOPE }
        )
    }

    private fun scoreOf(meal: Meal): Score {
        val delta = meal.suggestedUnits - meal.injectedUnits
        val slope = meal.slopeMgDlPerUnit

        fun excluded(exclusion: Exclusion) = Score(
            meal = meal,
            deltaUnits = delta,
            projectedModelGlucose = null,
            exclusion = exclusion
        )

        if (slope == null) return excluded(Exclusion.NO_SLOPE)
        if (slope > -MIN_USABLE_SLOPE_MAGNITUDE) return excluded(Exclusion.INVERTED_CURVE)
        if (absOf(delta) < SAME_DOSE_TOLERANCE_UNITS) return excluded(Exclusion.SAME_DOSE)

        return Score(
            meal = meal,
            deltaUnits = delta,
            projectedModelGlucose = meal.actual4h + slope * delta,
            exclusion = null
        )
    }

    private fun absOf(value: Double): Double = if (value < 0.0) -value else value
}
