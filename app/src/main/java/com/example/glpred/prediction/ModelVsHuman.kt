package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.abs

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
    val outcome: OutcomeStats,
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
    /** Model against rule of thumb, meal by meal, in time order. */
    val convergence: Convergence = Convergence(emptyList()),
    /** Direction-only verdicts, which need no sensitivity, plus the bracket over a plausible range. */
    val directional: DirectionalScore.Result = DirectionalScore.EMPTY
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

    /**
     * Meals that have both a stored suggestion and a resolved 4 h value, split by whether the
     * injected dose matched the suggestion.
     *
     * This is a correlation and not an experiment: you choose when to follow the model, and you
     * probably follow it on meals you feel confident about. The screen has to say so.
     */
    data class OutcomeStats(val followed: Bucket, val notFollowed: Bucket) {
        val total: Int get() = followed.meals + notFollowed.meals
        val hasEvidence: Boolean get() = total > 0
    }

    /**
     * Chronological head-to-head, one entry per labelled meal: how wrong the model was about the
     * glucose that followed, against how wrong a rule of thumb was.
     *
     * The baseline is "a meal dosed by habit leaves you where you started", i.e. it forecasts the
     * starting glucose. That is what the conventional approach implicitly assumes, and it is scored
     * against the same observed outcome as the model - so the two are comparable, and neither gets to
     * grade its own homework.
     */
    data class Convergence(val points: List<Point>) {

        data class Point(
            val mealAt: Long,
            val modelErrorMgDl: Double,
            val baselineErrorMgDl: Double
        )

        val meals: Int get() = points.size

        val modelMeanError: Double?
            get() = if (points.isEmpty()) null else points.map { it.modelErrorMgDl }.average()

        val baselineMeanError: Double?
            get() = if (points.isEmpty()) null else points.map { it.baselineErrorMgDl }.average()

        /** Meals on which the model was closer to the truth than the rule of thumb. */
        val modelWins: Int get() = points.count { it.modelErrorMgDl < it.baselineErrorMgDl }

        /** Running mean error after each meal, so a trend can be drawn rather than a single number. */
        fun runningModelError(): List<Double> = running { it.modelErrorMgDl }

        fun runningBaselineError(): List<Double> = running { it.baselineErrorMgDl }

        private fun running(selector: (Point) -> Double): List<Double> {
            var total = 0.0
            return points.mapIndexed { index, point ->
                total += selector(point)
                total / (index + 1)
            }
        }
    }

    data class Bucket(val meals: Int, val inBand: Int) {
        /** Null rather than a fake 0 % when nothing is behind it. */
        val inBandShare: Double? get() = if (meals == 0) null else inBand.toDouble() / meals
    }

    companion object {

        /** A dose this close to the suggestion counts as following it (pens do half units). */
        const val FOLLOW_TOLERANCE_UNITS = 0.5

        fun build(
            entries: List<DiaryEntity>,
            samples: List<TrainingSample>,
            validationErrorMgDl: Double? = null,
            modelCurve: List<Double?> = emptyList(),
            /**
             * Out-of-sample prediction per entry id (see [LeaveOneOut.predict4h]). Deliberately not
             * the fitted model: scoring it on its own training rows would show a perfect model.
             */
            looPredictions: Map<Long, Double> = emptyMap()
        ): ModelVsHuman {
            val labelsByEntry = samples.associateBy { it.entryId }

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

            // Only meals that had a suggestion *and* whose 4 h value resolved can judge anything.
            val judgeable = entries.mapNotNull { entry ->
                val suggested = entry.suggestedBolus ?: return@mapNotNull null
                val after4h = labelsByEntry[entry.id]?.after4h ?: return@mapNotNull null
                Triple(entry, suggested, after4h)
            }

            fun bucket(predicate: (Triple<DiaryEntity, Double, Double>) -> Boolean): Bucket {
                val selected = judgeable.filter(predicate)
                return Bucket(
                    meals = selected.size,
                    inBand = selected.count { it.third in TARGET_LOW_MG_DL..TARGET_HIGH_MG_DL }
                )
            }

            return ModelVsHuman(
                ratioByHour = ratioByHour,
                outcome = OutcomeStats(
                    followed = bucket { abs(it.first.bolus - it.second) <= FOLLOW_TOLERANCE_UNITS },
                    notFollowed = bucket { abs(it.first.bolus - it.second) > FOLLOW_TOLERANCE_UNITS }
                ),
                convergence = Convergence(
                    samples
                        .filter { it.after4h != null }
                        .sortedBy { it.timestampMs }
                        .mapNotNull { sample ->
                            val predicted = looPredictions[sample.entryId] ?: return@mapNotNull null
                            val actual = sample.after4h ?: return@mapNotNull null
                            Convergence.Point(
                                mealAt = sample.timestampMs,
                                modelErrorMgDl = abs(predicted - actual),
                                baselineErrorMgDl = abs(sample.glucose - actual)
                            )
                        }
                ),
                directional = DirectionalScore.build(entries, samples),
                mealsRecorded = entries.size,
                mealsWithSuggestion = entries.count { it.suggestedBolus != null },
                labelledMeals = samples.count { it.after4h != null },
                validationErrorMgDl = validationErrorMgDl,
                modelCurve = modelCurve
            )
        }
    }
}
