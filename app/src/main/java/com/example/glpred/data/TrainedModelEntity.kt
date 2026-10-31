package com.example.glpred.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The trained regression model, stored as a single row.
 *
 * Arrays are kept as comma-separated strings rather than adding Room TypeConverters for a table
 * that only ever holds one row of six numbers. Coefficients live in standardised space, so
 * [featureMeans] and [featureScales] must travel with them.
 *
 * [rmse4h] / [rmse3h] are in-sample and therefore optimistic; see [com.example.glpred.prediction.RidgeModel].
 */
@Entity(tableName = "model_table")
data class TrainedModelEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val trainedAt: Long,
    val lambda: Double,
    val featureMeans: String,
    val featureScales: String,
    val coefficients4h: String,
    val intercept4h: Double,
    val rmse4h: Double,
    val samples4h: Int,
    val coefficients3h: String? = null,
    val intercept3h: Double? = null,
    val rmse3h: Double? = null,
    val samples3h: Int? = null
) {
    companion object {
        /** Only one model is ever current. */
        const val SINGLETON_ID = 1

        fun encode(values: DoubleArray): String =
            values.joinToString(separator = ",") { it.toString() }

        fun decode(encoded: String?): DoubleArray? {
            if (encoded.isNullOrBlank()) return null
            return encoded.split(',').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
        }
    }
}
