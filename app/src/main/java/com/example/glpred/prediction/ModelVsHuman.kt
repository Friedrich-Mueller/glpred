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
    /** Whose dose would have landed closer to the target, with the streak built on it. */
    val counterfactual: CounterfactualScore.Result = CounterfactualScore.EMPTY
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
            modelCurve: List<Double?> = emptyList()
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
                counterfactual = CounterfactualScore.build(entries, samples),
                mealsRecorded = entries.size,
                mealsWithSuggestion = entries.count { it.suggestedBolus != null },
                labelledMeals = samples.count { it.after4h != null },
                validationErrorMgDl = validationErrorMgDl,
                modelCurve = modelCurve
            )
        }
    }
}
