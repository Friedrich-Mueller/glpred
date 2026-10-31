package com.example.glpred.prediction

/**
 * Contract between the entry flow and whatever model is currently in the app.
 *
 * The flow is: enter glucose/carbs/basis -> ask for a dose recommendation -> review it ->
 * record what was actually injected. Today no model exists yet (see PLAN.md phases 2-3), so the
 * only implementation is [UnavailableDoseAdvisor]; the UI is built against this interface so the
 * real implementation can drop in without touching the screens.
 */

/** Glucose band the app tries to land in. The ideal point is [TARGET_IDEAL_MG_DL]. */
const val TARGET_LOW_MG_DL = 90.0
const val TARGET_HIGH_MG_DL = 140.0
const val TARGET_IDEAL_MG_DL = 90.0

/**
 * Dose sweep: 1..25 units in 0.5-unit steps - 49 evaluations. 25 because nobody injects more per
 * meal than that; half units because the app stores fractional doses and 0.5-unit pens exist.
 */
const val SWEEP_MIN_UNITS = 1.0
const val SWEEP_MAX_UNITS = 25.0
const val SWEEP_STEP_UNITS = 0.5

/** The entry the advisor has to answer for. */
data class DiaryEntryInput(
    val timestampMs: Long,
    val glucose: Int,
    val carbs: Float,
    val basis: Int
)

/** One evaluated dose: what the model says glucose would be at that bolus. */
data class DosePoint(
    val bolusUnits: Double,
    val predictedGlucoseMgDl: Double
)

/**
 * Answer to "how much short-acting insulin should I inject for this meal?".
 *
 * [Available.inRange] is false when no swept dose lands inside
 * [TARGET_LOW_MG_DL]..[TARGET_HIGH_MG_DL]; [Available.suggestedUnits] is then the closest miss and
 * the UI must say so rather than presenting it as a safe recommendation.
 */
sealed interface DoseRecommendation {

    data class Available(
        val suggestedUnits: Double,
        val predictedGlucoseMgDl: Double,
        val inRange: Boolean,
        val curve: List<DosePoint>
    ) : DoseRecommendation

    /** No model, no training data, or too little to be trusted. */
    data class Unavailable(val reason: String) : DoseRecommendation
}

interface DoseAdvisor {
    suspend fun recommend(entry: DiaryEntryInput): DoseRecommendation
}

/**
 * Placeholder used until the training pipeline exists (PLAN.md phases 2-3).
 *
 * Deliberately always unavailable: with no model there is no number to report. Once a model exists
 * the real advisor answers on every call - however few samples it has and however wide its error -
 * because those predictions are exactly what the later model-vs-user comparison is measured on.
 */
object UnavailableDoseAdvisor : DoseAdvisor {
    override suspend fun recommend(entry: DiaryEntryInput): DoseRecommendation =
        DoseRecommendation.Unavailable(
            "No trained model yet. Record meals, re-import your CGM export so the labels mature, " +
                "and the predictor will unlock once there is enough training data."
        )
}
