package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.abs

/**
 * Scores the model's advice against what actually happened, without ever pretending to know the
 * outcome of a dose that was not injected.
 *
 * The one assumption is monotonicity: more insulin lowers the 4 h glucose. That is true
 * physiologically and true by construction in the linear model. From it, a single meal yields a
 * verdict on direction only:
 *
 *  - you ended above 90 and the model wanted more insulin than you took  -> RIGHT
 *  - you ended above 90 and the model wanted less                         -> WRONG
 *  - you ended in band                                                    -> NO_CLAIM
 *  - you were off target and the model suggested your own dose            -> NO_REMEDY
 *
 * Direction needs nothing else. The *size* of a correction needs the insulin sensitivity, which is
 * the very thing the model is still estimating - so using the model's factor to judge the model's
 * dose would be circular. [Bracket] is the honest middle ground: it asks whether the suggested dose
 * would have landed in band for *some* plausible sensitivity, and says so as a range rather than a
 * point.
 */
object DirectionalScore {

    const val TARGET_MG_DL = 90.0
    const val BAND_LOW_MG_DL = 90.0
    const val BAND_HIGH_MG_DL = 140.0

    /** Below this the advisor and the user effectively agreed, so no correction was implied. */
    const val SAME_DOSE_TOLERANCE_UNITS = 0.5

    /**
     * Insulin sensitivity in mg/dL per unit: the reciprocal of a carb factor, so a factor of 1-2 u/CU
     * is 50-100 mg/dL per unit. The bracket is evaluated across this range.
     */
    const val SENSITIVITY_LOW = 50.0
    const val SENSITIVITY_HIGH = 100.0

    const val WINDOW_SIZE = 20

    enum class Verdict { RIGHT, WRONG, NO_REMEDY, NO_CLAIM }

    /** Whether the suggested dose would have reached the band for a plausible sensitivity. */
    enum class Bracket { ROBUST, PLAUSIBLE, IMPLAUSIBLE, NONE }

    data class Meal(
        val mealAt: Long,
        val actualMgDl: Double,
        val injectedUnits: Double,
        val suggestedUnits: Double,
        val verdict: Verdict,
        val bracket: Bracket
    )

    /** The last [size] judged meals, counted. */
    data class Window(
        val size: Int,
        val right: Int,
        val wrong: Int,
        val noRemedy: Int,
        val highMeals: Int,
        val lowMeals: Int,
        val youInBand: Int
    ) {
        /** Meals that carried a directional claim: in-band meals carry none. */
        val scored: Int get() = right + wrong + noRemedy

        /** The model's hit rate, over the meals where a correction was actually called for. */
        val modelShare: Double? get() = if (scored == 0) null else right.toDouble() / scored

        /** Your hit rate, over every judged meal - including the ones you got right outright. */
        val youShare: Double? get() = if (size == 0) null else youInBand.toDouble() / size
    }

    data class Result(
        val meals: List<Meal>,
        val window: Window,
        val windowSize: Int,
        val bracketCounts: Map<Bracket, Int>
    ) {
        /** True once at least one meal could be judged at all. */
        val hasEvidence: Boolean get() = meals.isNotEmpty()
    }

    val EMPTY = Result(
        meals = emptyList(),
        window = Window(0, 0, 0, 0, 0, 0, 0),
        windowSize = WINDOW_SIZE,
        bracketCounts = emptyMap()
    )

    /**
     * Only meals that both carry a stored suggestion and resolved a 4 h value can be judged. Before a
     * model existed there is no suggestion, and inside the CGM coverage there is no label - which is
     * why this count is shown on screen rather than left implicit.
     */
    fun build(
        entries: List<DiaryEntity>,
        samples: List<TrainingSample>,
        windowSize: Int = WINDOW_SIZE
    ): Result {
        val byEntry = entries.associateBy { it.id }
        val meals = samples
            .filter { it.after4h != null }
            .sortedBy { it.timestampMs }
            .mapNotNull { sample ->
                val entry = byEntry[sample.entryId] ?: return@mapNotNull null
                val suggested = entry.suggestedBolus ?: return@mapNotNull null
                val actual = sample.after4h ?: return@mapNotNull null
                judge(sample.timestampMs, actual, entry.bolus, suggested)
            }

        val recent = meals.takeLast(windowSize)
        val window = Window(
            size = recent.size,
            right = recent.count { it.verdict == Verdict.RIGHT },
            wrong = recent.count { it.verdict == Verdict.WRONG },
            noRemedy = recent.count { it.verdict == Verdict.NO_REMEDY },
            highMeals = recent.count { it.actualMgDl > BAND_HIGH_MG_DL },
            lowMeals = recent.count { it.actualMgDl < BAND_LOW_MG_DL },
            youInBand = recent.count { it.actualMgDl in BAND_LOW_MG_DL..BAND_HIGH_MG_DL }
        )

        return Result(
            meals = meals,
            window = window,
            windowSize = windowSize,
            bracketCounts = meals.groupingBy { it.bracket }.eachCount()
        )
    }

    private fun judge(
        mealAt: Long,
        actual: Double,
        injected: Double,
        suggested: Double
    ): Meal {
        val delta = suggested - injected
        val offTarget = actual - TARGET_MG_DL
        val inBand = actual in BAND_LOW_MG_DL..BAND_HIGH_MG_DL
        val verdict = when {
            inBand -> Verdict.NO_CLAIM
            abs(delta) < SAME_DOSE_TOLERANCE_UNITS -> Verdict.NO_REMEDY
            // Same sign: the direction the model pointed reduces the miss.
            offTarget * delta > 0.0 -> Verdict.RIGHT
            else -> Verdict.WRONG
        }
        return Meal(
            mealAt = mealAt,
            actualMgDl = actual,
            injectedUnits = injected,
            suggestedUnits = suggested,
            verdict = verdict,
            bracket = bracket(actual, injected, suggested)
        )
    }

    /**
     * The outcome the suggested dose would have produced, for sensitivities across the plausible
     * range: `actual - sensitivity * (suggested - injected)`. A point estimate would need the factor;
     * a range does not.
     */
    private fun bracket(actual: Double, injected: Double, suggested: Double): Bracket {
        val delta = suggested - injected
        if (abs(delta) < SAME_DOSE_TOLERANCE_UNITS) return Bracket.NONE

        // Higher sensitivity pulls the outcome further, so SENSITIVITY_HIGH gives the low end.
        val atLowSensitivity = actual - SENSITIVITY_LOW * delta
        val atHighSensitivity = actual - SENSITIVITY_HIGH * delta
        val lower = minOf(atLowSensitivity, atHighSensitivity)
        val upper = maxOf(atLowSensitivity, atHighSensitivity)

        return when {
            lower >= BAND_LOW_MG_DL && upper <= BAND_HIGH_MG_DL -> Bracket.ROBUST
            upper >= BAND_LOW_MG_DL && lower <= BAND_HIGH_MG_DL -> Bracket.PLAUSIBLE
            else -> Bracket.IMPLAUSIBLE
        }
    }
}
