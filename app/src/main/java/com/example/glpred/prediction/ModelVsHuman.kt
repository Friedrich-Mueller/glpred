package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample

/**
 * What the model thinks your body does, against what your own doses imply, plus what actually
 * happened afterwards.
 *
 * Everything here is observable. The model's dose-per-carb for a meal is derived from the suggestion
 * that was stored with that entry - it *is* the model's answer for that meal, so no coefficient
 * arithmetic is needed - and yours comes from the dose you injected. No counterfactual is involved,
 * so the screen must never claim "the model's dose would have been better": only one dose happened.
 */
data class ModelVsHuman(
    val ratioByHour: List<RatioPoint>,
    val mealsRecorded: Int,
    val mealsWithSuggestion: Int,
    val labelledMeals: Int,
    /** Leave-one-out error over all labelled meals, or null when there are too few. */
    val validationErrorMgDl: Double?,
    /**
     * The model's own dose per carb unit at each hour of the day (index = hour, 0..23), from asking
     * it for a reference meal. Empty when no model exists or no meal has carb units recorded.
     */
    val modelCurve: List<Double?> = emptyList(),
    /** Whose dose was closer to the target: the per-meal metric, its counts and its trend. */
    val benchmark: DoseBenchmark.Metric = DoseBenchmark.EMPTY
) {

    /**
     * Dose per carb unit at one hour of the day. Averages are weighted by carbs, because a 10-unit
     * meal says more about the ratio than a 1-unit one.
     */
    data class RatioPoint(
        val hourOfDay: Int,
        val youUnitsPerCarb: Double,
        val modelUnitsPerCarb: Double?,
        val meals: Int
    )

    companion object {

        fun build(
            entries: List<DiaryEntity>,
            samples: List<TrainingSample>,
            validationErrorMgDl: Double? = null,
            modelCurve: List<Double?> = emptyList(),
            /**
             * The captured `dG4/dU` for an entry, keyed by its timestamp. Null means none was
             * captured, which the metric reports as a separate exclusion rather than as a loss.
             */
            slopeFor: (Long) -> Double? = { null }
        ): ModelVsHuman {
            val ratioByHour = entries
                .filter { it.carbs > 0f }
                .groupBy { it.hourOfDay }
                .toSortedMap()
                .map { (hour, mealsAtHour) ->
                    val carbsAtHour = mealsAtHour.sumOf { it.carbs.toDouble() }
                    val suggestedMeals = mealsAtHour.filter { it.suggestedBolus != null }
                    RatioPoint(
                        hourOfDay = hour,
                        youUnitsPerCarb = mealsAtHour.sumOf { it.bolus } / carbsAtHour,
                        modelUnitsPerCarb = if (suggestedMeals.isEmpty()) null else {
                            suggestedMeals.sumOf { it.suggestedBolus!! } /
                                suggestedMeals.sumOf { it.carbs.toDouble() }
                        },
                        meals = mealsAtHour.size
                    )
                }

            return ModelVsHuman(
                ratioByHour = ratioByHour,
                benchmark = DoseBenchmark.evaluate(benchmarkMeals(entries, samples, slopeFor)),
                mealsRecorded = entries.size,
                mealsWithSuggestion = entries.count { it.suggestedBolus != null },
                labelledMeals = samples.count { it.after4h != null },
                validationErrorMgDl = validationErrorMgDl,
                modelCurve = modelCurve
            )
        }

        /**
         * The join the metric is measured on: a meal is judged only when it has a suggestion and a 4 h
         * label. Everything else - a missing slope, a missing stored prediction, a dose that matched -
         * is passed through so [DoseBenchmark] can count it by reason.
         *
         * An earlier version dropped rows here when the stored prediction was null, which made the card
         * say "no meal has both a suggestion and a 4 h value" while the card above it reported five of
         * each. A silent drop is indistinguishable from having no data.
         */
        fun benchmarkMeals(
            entries: List<DiaryEntity>,
            samples: List<TrainingSample>,
            slopeFor: (Long) -> Double?
        ): List<DoseBenchmark.Meal> {
            val byId = entries.associateBy { it.id }
            return samples
                .filter { it.after4h != null }
                .sortedBy { it.timestampMs }
                .mapNotNull { sample ->
                    val entry = byId[sample.entryId] ?: return@mapNotNull null
                    val suggested = entry.suggestedBolus ?: return@mapNotNull null
                    DoseBenchmark.Meal(
                        entryId = entry.id,
                        hourOfDay = TrainingSample.minuteOfDay(entry.timestamp) / 60,
                        startGlucose = entry.glucose.toDouble(),
                        carbs = entry.carbs.toDouble(),
                        injectedUnits = entry.bolus,
                        suggestedUnits = suggested,
                        predictedForSuggestion = entry.predictedGlucose,
                        actual4h = sample.after4h!!,
                        slopeMgDlPerUnit = slopeFor(entry.timestamp)
                    )
                }
        }
    }
}
