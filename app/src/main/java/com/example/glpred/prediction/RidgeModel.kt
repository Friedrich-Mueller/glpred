package com.example.glpred.prediction

import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.abs
import kotlin.math.sqrt

/** One regression target, fitted on standardised features. */
data class TargetModel(
    val coefficients: DoubleArray,
    val intercept: Double,
    val rmse: Double,
    val sampleCount: Int
) {
    fun predict(standardised: DoubleArray): Double {
        var sum = intercept
        for (i in coefficients.indices) sum += coefficients[i] * standardised[i]
        return sum
    }
}

/**
 * Ridge regression over the meal vector, fitted in closed form.
 *
 * Two targets are supported because the app wants both horizons: [model4h] is primary (the dose
 * sweep optimises it) and [model3h] is displayed for smaller doses that are finished earlier.
 *
 * At this sample size a normal-equations solve is the honest choice: one 6x6 factorisation is
 * cheaper than loading a runtime, the result is inspectable, and there is nothing to tune beyond
 * [lambda]. Coefficients live in standardised space, so [means]/[scales] must be persisted with
 * them.
 */
class RidgeModel(
    private val means: DoubleArray,
    private val scales: DoubleArray,
    val model4h: TargetModel,
    val model3h: TargetModel?
) {

    val featureCount: Int get() = means.size

    /** Standardisation stats, persisted with the coefficients so the model can be reapplied. */
    val featureMeans: DoubleArray get() = means.copyOf()

    val featureScales: DoubleArray get() = scales.copyOf()

    fun predict4h(features: DoubleArray): Double = model4h.predict(standardise(features))

    /** Null when the 3 h target had too few samples to fit. */
    fun predict3h(features: DoubleArray): Double? = model3h?.predict(standardise(features))

    private fun standardise(features: DoubleArray): DoubleArray =
        DoubleArray(features.size) { i ->
            val scale = scales[i]
            if (scale == 0.0) 0.0 else (features[i] - means[i]) / scale
        }

    companion object {
        /** Small L2 penalty: enough to keep the solve stable when features are collinear. */
        const val DEFAULT_LAMBDA = 0.1

        /** Minimum rows before a fit is attempted at all. */
        const val MIN_SAMPLES = 3

        /**
         * Fits both horizons from [samples]. Returns null when there are too few labelled rows -
         * two points always have a perfect line, and three is already optimistic.
         *
         * RMSE is in-sample and therefore optimistic; with a handful of entries there is no honest
         * holdout. The UI must present it as such.
         */
        fun fit(samples: List<TrainingSample>, lambda: Double = DEFAULT_LAMBDA): RidgeModel? {
            val usable = samples.filter { it.hasPrimaryLabel }
            if (usable.size < MIN_SAMPLES) return null

            val raw = Array(usable.size) { usable[it].features() }
            val dim = raw[0].size
            val means = DoubleArray(dim)
            val scales = DoubleArray(dim)
            for (j in 0 until dim) {
                var sum = 0.0
                for (row in raw) sum += row[j]
                val mean = sum / raw.size
                var varianceSum = 0.0
                for (row in raw) {
                    val delta = row[j] - mean
                    varianceSum += delta * delta
                }
                val sd = sqrt(varianceSum / raw.size)
                means[j] = mean
                scales[j] = if (sd < 1e-9) 1.0 else sd
            }

            fun standardise(row: DoubleArray) = DoubleArray(dim) { j -> (row[j] - means[j]) / scales[j] }

            val x4 = Array(usable.size) { standardise(raw[it]) }
            val y4 = DoubleArray(usable.size) { usable[it].after4h!! }
            val model4h = RidgeMath.fitTarget(x4, y4, lambda) ?: return null

            // The 3 h target only sees entries whose 3 h horizon is covered as well.
            val threeHourRows = usable.withIndex().filter { it.value.after3h != null }
            val model3h = if (threeHourRows.size >= MIN_SAMPLES) {
                val x3 = Array(threeHourRows.size) { standardise(raw[threeHourRows[it].index]) }
                val y3 = DoubleArray(threeHourRows.size) { threeHourRows[it].value.after3h!! }
                RidgeMath.fitTarget(x3, y3, lambda)
            } else {
                null
            }

            return RidgeModel(means, scales, model4h, model3h)
        }
    }
}

/** The numeric core, kept separate so it can be unit-tested without building samples. */
internal object RidgeMath {

    /**
     * Solves `min ||y - Xw - b||^2 + lambda * ||w||^2` by centring both X and y, forming
     * `(X'X + lambda*I) w = X'(y - mean(y))` and eliminating. Centring happens here so no intercept
     * column is needed in the solve and callers cannot get it wrong; the returned coefficients apply
     * to *centred* features, with `b = mean(y)`.
     */
    fun fitTarget(x: Array<DoubleArray>, y: DoubleArray, lambda: Double): TargetModel? {
        if (x.isEmpty() || x.size != y.size) return null
        val n = x.size
        val dim = x[0].size

        val meanY = y.average()
        val centredY = DoubleArray(n) { y[it] - meanY }
        val columnMeans = DoubleArray(dim) { j ->
            var sum = 0.0
            for (row in 0 until n) sum += x[row][j]
            sum / n
        }

        val a = Array(dim) { DoubleArray(dim) }
        val b = DoubleArray(dim)
        for (i in 0 until dim) {
            for (j in 0 until dim) {
                var sum = 0.0
                for (row in 0 until n) {
                    sum += (x[row][i] - columnMeans[i]) * (x[row][j] - columnMeans[j])
                }
                a[i][j] = sum
            }
            a[i][i] += lambda
            var sum = 0.0
            for (row in 0 until n) sum += (x[row][i] - columnMeans[i]) * centredY[row]
            b[i] = sum
        }

        val weights = solveLinearSystem(a, b) ?: return null

        var squaredError = 0.0
        for (row in 0 until n) {
            var predicted = meanY
            for (j in 0 until dim) predicted += weights[j] * (x[row][j] - columnMeans[j])
            val residual = y[row] - predicted
            squaredError += residual * residual
        }

        return TargetModel(
            coefficients = weights,
            intercept = meanY,
            rmse = sqrt(squaredError / n),
            sampleCount = n
        )
    }

    /** Gaussian elimination with partial pivoting; null when the matrix is singular. */
    fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()

        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) {
                if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
            }
            if (abs(a[pivot][column]) < 1e-12) return null
            if (pivot != column) {
                val tmpRow = a[pivot]; a[pivot] = a[column]; a[column] = tmpRow
                val tmpB = b[pivot]; b[pivot] = b[column]; b[column] = tmpB
            }
            for (row in column + 1 until n) {
                val factor = a[row][column] / a[column][column]
                if (factor == 0.0) continue
                for (k in column until n) a[row][k] -= factor * a[column][k]
                b[row] -= factor * b[column]
            }
        }

        val solution = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = b[row]
            for (k in row + 1 until n) sum -= a[row][k] * solution[k]
            solution[row] = sum / a[row][row]
        }
        return solution
    }
}
