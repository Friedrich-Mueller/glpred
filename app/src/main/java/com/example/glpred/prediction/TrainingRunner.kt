package com.example.glpred.prediction

import com.example.glpred.data.CgmDao
import com.example.glpred.data.DiaryDao
import com.example.glpred.data.TrainedModelDao
import com.example.glpred.data.TrainedModelEntity
import com.example.glpred.preprocessing.CgmSeries
import com.example.glpred.preprocessing.TrainingSamples

/**
 * Outcome of a training attempt, worded so the UI can tell the user what to do next.
 *
 * "Re-import your CGM export" and "record more meals" are different instructions, and conflating
 * them is how a working app looks broken.
 */
sealed interface TrainingResult {

    /** No diary entries at all. */
    data object NoEntries : TrainingResult

    /**
     * Entries exist, but none has a primary (4 h) label yet. The usual cause is a missing CGM
     * import that covers the entry plus the prediction horizon.
     */
    data class NoLabelledSamples(val entries: Int) : TrainingResult

    /** Some labels exist but not enough to fit a model at all. */
    data class TooFewSamples(
        val entries: Int,
        val labelled: Int,
        val required: Int
    ) : TrainingResult

    data class Trained(
        val entries: Int,
        val samples4h: Int,
        val samples3h: Int?,
        val rmse4h: Double,
        val rmse3h: Double?,
        val trainedAt: Long
    ) : TrainingResult

    data class Failed(val reason: String) : TrainingResult
}

/**
 * Builds training samples from both databases, fits the model and stores it.
 *
 * Two gates before a model exists: entries must be recorded, and their 4 h labels must be resolvable
 * from imported CGM data. The labels only mature after a CSV import that post-dates the entry.
 */
class TrainingRunner(
    private val diaryDao: DiaryDao,
    private val cgmDao: CgmDao,
    private val modelDao: TrainedModelDao
) {

    suspend fun train(lambda: Double = RidgeModel.DEFAULT_LAMBDA): TrainingResult {
        val entries = diaryDao.getAllEntriesList()
        if (entries.isEmpty()) return TrainingResult.NoEntries

        val series = CgmSeries.from(cgmDao.getAllReadings())
        val samples = TrainingSamples.build(entries, series)
        val labelled = TrainingSamples.labelled(samples)
        if (labelled.isEmpty()) return TrainingResult.NoLabelledSamples(entries.size)
        if (labelled.size < RidgeModel.MIN_SAMPLES) {
            return TrainingResult.TooFewSamples(
                entries = entries.size,
                labelled = labelled.size,
                required = RidgeModel.MIN_SAMPLES
            )
        }

        val model = RidgeModel.fit(samples, lambda)
            ?: return TrainingResult.Failed("The regression could not be solved - try importing more data.")
        val trainedAt = System.currentTimeMillis()

        modelDao.save(
            TrainedModelEntity(
                trainedAt = trainedAt,
                lambda = lambda,
                featureMeans = TrainedModelEntity.encode(model.featureMeans),
                featureScales = TrainedModelEntity.encode(model.featureScales),
                coefficients4h = TrainedModelEntity.encode(model.model4h.coefficients),
                intercept4h = model.model4h.intercept,
                rmse4h = model.model4h.rmse,
                samples4h = model.model4h.sampleCount,
                coefficients3h = model.model3h?.let { TrainedModelEntity.encode(it.coefficients) },
                intercept3h = model.model3h?.intercept,
                rmse3h = model.model3h?.rmse,
                samples3h = model.model3h?.sampleCount
            )
        )

        return TrainingResult.Trained(
            entries = entries.size,
            samples4h = model.model4h.sampleCount,
            samples3h = model.model3h?.sampleCount,
            rmse4h = model.model4h.rmse,
            rmse3h = model.model3h?.rmse,
            trainedAt = trainedAt
        )
    }
}
