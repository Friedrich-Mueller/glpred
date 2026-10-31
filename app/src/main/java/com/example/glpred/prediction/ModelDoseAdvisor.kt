package com.example.glpred.prediction

import com.example.glpred.data.TrainedModelDao
import com.example.glpred.data.TrainedModelEntity
import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The real advisor: loads the stored ridge model and sweeps bolus doses against it.
 *
 * Selection is on the **4 h** prediction (the primary horizon). Among doses whose predicted 4 h
 * glucose lands in the target band, the one closest to [TARGET_IDEAL_MG_DL] wins - which, because
 * more insulin lowers the prediction, is also the lowest qualifying dose. When nothing lands in
 * band the closest miss is reported with `inRange = false`; the UI must not present it as advice.
 *
 * No sample-count gate: if a model exists, it answers. The stored RMSE and sample count are what
 * tell the user how much the answer deserves to be trusted.
 */
class ModelDoseAdvisor(
    private val modelDao: TrainedModelDao
) : DoseAdvisor {

    override suspend fun recommend(entry: DiaryEntryInput): DoseRecommendation {
        val entity = modelDao.get()
            ?: return DoseRecommendation.Unavailable(
                "No trained model yet. Record meals, re-import your CGM export so the labels " +
                    "mature, then train the model."
            )
        val model = entity.toRidgeModel()
            ?: return DoseRecommendation.Unavailable(
                "The stored model could not be read - train it again."
            )

        val minuteOfDay = TrainingSample.minuteOfDay(entry.timestampMs)
        val angle = 2.0 * PI * minuteOfDay / TrainingSample.MINUTES_PER_DAY
        val features = doubleArrayOf(
            entry.glucose.toDouble(),
            entry.carbs.toDouble(),
            0.0, // bolus - replaced per swept dose
            entry.basis.toDouble(),
            sin(angle),
            cos(angle)
        )

        val selection = DoseSweep.run(features, model::predict4h)

        return DoseRecommendation.Available(
            suggestedUnits = selection.units,
            predictedGlucoseMgDl = selection.predictedGlucoseMgDl,
            inRange = selection.inRange,
            curve = selection.curve
        )
    }
}

/** Sweep and selection, kept pure so it can be tested without a database. */
internal object DoseSweep {

    data class Selection(
        val units: Double,
        val predictedGlucoseMgDl: Double,
        val inRange: Boolean,
        val curve: List<DosePoint>
    )

    /**
     * Scores every dose in [SWEEP_MIN_UNITS]..[SWEEP_MAX_UNITS] at [SWEEP_STEP_UNITS] and picks one.
     *
     * Integer stepping avoids the drift of repeated `dose += step` on binary fractions.
     */
    fun run(features: DoubleArray, predict: (DoubleArray) -> Double): Selection {
        val steps = ((SWEEP_MAX_UNITS - SWEEP_MIN_UNITS) / SWEEP_STEP_UNITS).toInt()
        val curve = ArrayList<DosePoint>(steps + 1)
        val working = features.copyOf()

        for (step in 0..steps) {
            val dose = SWEEP_MIN_UNITS + step * SWEEP_STEP_UNITS
            working[BOLUS_FEATURE_INDEX] = dose
            curve.add(DosePoint(bolusUnits = dose, predictedGlucoseMgDl = predict(working)))
        }

        val inBand = curve.filter {
            it.predictedGlucoseMgDl in TARGET_LOW_MG_DL..TARGET_HIGH_MG_DL
        }
        val best = if (inBand.isNotEmpty()) {
            inBand.minByOrNull { kotlin.math.abs(it.predictedGlucoseMgDl - TARGET_IDEAL_MG_DL) }!!
        } else {
            curve.minByOrNull { doseToBand(it.predictedGlucoseMgDl) }!!
        }

        return Selection(
            units = best.bolusUnits,
            predictedGlucoseMgDl = best.predictedGlucoseMgDl,
            inRange = inBand.isNotEmpty(),
            curve = curve
        )
    }

    private const val BOLUS_FEATURE_INDEX = 2

    private fun doseToBand(glucose: Double): Double = when {
        glucose < TARGET_LOW_MG_DL -> TARGET_LOW_MG_DL - glucose
        glucose > TARGET_HIGH_MG_DL -> glucose - TARGET_HIGH_MG_DL
        else -> 0.0
    }
}

/** Rebuilds a [RidgeModel] from its stored row. Null when the row is incomplete. */
internal fun TrainedModelEntity.toRidgeModel(): RidgeModel? {
    val means = TrainedModelEntity.decode(featureMeans) ?: return null
    val scales = TrainedModelEntity.decode(featureScales) ?: return null
    val coefficients4hValues = TrainedModelEntity.decode(coefficients4h) ?: return null

    val primary = TargetModel(
        coefficients = coefficients4hValues,
        intercept = intercept4h,
        rmse = rmse4h,
        sampleCount = samples4h
    )
    val secondary = TrainedModelEntity.decode(coefficients3h)?.let { coefficients ->
        TargetModel(
            coefficients = coefficients,
            intercept = intercept3h ?: return@let null,
            rmse = rmse3h ?: return@let null,
            sampleCount = samples3h ?: return@let null
        )
    }
    return RidgeModel(means, scales, primary, secondary)
}
