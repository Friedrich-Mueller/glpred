package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.sqrt

/**
 * Leave-one-out cross-validation: the honest error estimate for a tiny dataset.
 *
 * In-sample RMSE cannot adjudicate between two models, because adding parameters always lowers it.
 * LOO removes that bias: each row is predicted by a model fitted on all the *other* rows, so no
 * prediction has seen its own answer.
 *
 * Cost is one fit per row - trivial at a d x d solve with a handful of features - and it is
 * deterministic, unlike a train/test split whose result depends on which few rows land in the test
 * set.
 *
 * Two honest caveats:
 *  - the folds overlap almost completely, so LOO reads slightly better than true out-of-sample
 *    performance. It is reliable for *comparing* models on the same rows, not as an absolute claim.
 *  - at very few rows a single outlier swings it. Require a clear margin (>= ~10%) before treating
 *    a difference as real.
 */
object LeaveOneOut {

    data class Result(val folds: Int, val rmse: Double)

    /** LOO error for the primary 4 h target. Null when there are too few labelled rows. */
    fun rmse4h(
        samples: List<TrainingSample>,
        lambda: Double = RidgeModel.DEFAULT_LAMBDA
    ): Result? = validate(samples, lambda, { it.after4h }, { model, features -> model.predict4h(features) })

    /** LOO error for the secondary 3 h target. Null when there are too few 3 h labels. */
    fun rmse3h(
        samples: List<TrainingSample>,
        lambda: Double = RidgeModel.DEFAULT_LAMBDA
    ): Result? = validate(samples, lambda, { it.after3h }, { model, features -> model.predict3h(features) })

    /**
     * One out-of-sample prediction per row, keyed by entry id, for the 4 h target.
     *
     * Exposed separately from [rmse4h] because the model-vs-habit comparison scores each meal
     * individually. Using the fitted model's own predictions there would let it grade answers it was
     * trained on, which is the one thing this comparison exists to avoid.
     */
    fun predict4h(
        samples: List<TrainingSample>,
        lambda: Double = RidgeModel.DEFAULT_LAMBDA
    ): Map<Long, Double> {
        val usable = samples.filter { it.after4h != null }
        if (usable.size < RidgeModel.MIN_SAMPLES + 1) return emptyMap()

        val predictions = LinkedHashMap<Long, Double>()
        for (heldOut in usable.indices) {
            val trainingRows = usable.filterIndexed { index, _ -> index != heldOut }
            val model = RidgeModel.fit(trainingRows, lambda) ?: continue
            predictions[usable[heldOut].entryId] = model.predict4h(usable[heldOut].features())
        }
        return predictions
    }

    private fun validate(
        samples: List<TrainingSample>,
        lambda: Double,
        target: (TrainingSample) -> Double?,
        predict: (RidgeModel, DoubleArray) -> Double?
    ): Result? {
        val usable = samples.filter { target(it) != null }
        // Each fold fits on usable.size - 1 rows, which must still clear the model's own floor.
        if (usable.size < RidgeModel.MIN_SAMPLES + 1) return null

        var squaredError = 0.0
        var folds = 0
        for (heldOut in usable.indices) {
            val trainingRows = usable.filterIndexed { index, _ -> index != heldOut }
            val model = RidgeModel.fit(trainingRows, lambda) ?: continue
            val predicted = predict(model, usable[heldOut].features()) ?: continue
            val actual = target(usable[heldOut]) ?: continue
            val residual = actual - predicted
            squaredError += residual * residual
            folds++
        }

        if (folds == 0) return null
        return Result(folds = folds, rmse = sqrt(squaredError / folds))
    }
}
