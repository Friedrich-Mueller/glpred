package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Was the model's dose closer to the target than yours? Answered as a probability, per meal.
 *
 * The structure comes from standard mealtime dosing. With f = units per carb unit and ISF = mg/dL per
 * unit, the 4 h outcome after injecting B is
 *
 *      G4 = G0 + ISF * f * carbs - ISF * B
 *
 * so changing only the dose moves the outcome by ISF * delta and the carb side cancels. That makes ISF
 * - not the carb ratio - the only constant a counterfactual needs, and it is also a number the user
 * already knows about their own body.
 *
 * Where the ISF comes from, in order of authority:
 *  1. The fitted model's sensitivity, estimated for *each meal* by refitting on every other meal and
 *     reading d(prediction)/d(bolus) at that meal. Time of day comes for free through the interaction
 *     columns, and no meal's own outcome is used to judge that meal.
 *  2. A prior of 50 mg/dL per unit, from the user's own rule of thumb. It is a starting belief, not a
 *     fact: its weight falls as the folds agree, and it dominates only while they are too few or too
 *     scattered to say anything.
 *
 * Two things this rejects. A single assumed value is a guess with no error bar, so it cannot improve.
 * And reading the coefficient off the model that *produced* the suggestion would let the model mark
 * its own work: its suggestion is defined as the dose whose prediction is closest to the target, so
 * the projection would return "lands exactly on target" however wrong the model is.
 */
object CounterfactualScore {

    const val TARGET_MG_DL = 90.0
    const val BAND_LOW_MG_DL = 90.0
    const val BAND_HIGH_MG_DL = 140.0

    /** Below this the model proposed the same decision as the user, so there is nothing to compare. */
    const val SAME_DOSE_TOLERANCE_UNITS = 0.5

    /**
     * How much worse landing under the target is than landing over it by the same amount. A plain
     * absolute error would score a projection of 0 mg/dL - a severe hypo - as far from the target as
     * 180, and so let a dangerous overdose win a meal.
     */
    const val HYPO_WEIGHT = 3.0

    /** A win has to be probable, not merely more likely than not, to extend a streak. */
    const val CONFIDENT_PROBABILITY = 0.8

    /**
     * Used only when a single fold came back, where there is no spread to measure yet. It is a
     * relative width - "this estimate could be off by half" - not a clinical constant, so the app
     * still learns the sensitivity from the records rather than from a rule of thumb.
     */
    const val LONELY_FOLD_RELATIVE_SPREAD = 0.5

    /** No estimate may claim to be sharper than this, however tidy its folds look. */
    const val MIN_ISF_SPREAD = 5.0

    const val WINDOW_SIZE = 20

    const val STREAK_TARGET = 10

    /** A projection below this is a hypo, and cannot be the better dose however the numbers add up. */
    const val HYPO_FLOOR_MG_DL = 70.0

    /**
     * Loss applied the moment a projection falls under the hypo floor.
     *
     * Without it the scoring is arithmetically tidy and medically wrong: a suggested dose projecting
     * to 0 mg/dL is 90 points from the target, tripled is 24300, which is less than the 25600 of
     * landing at 250 - so an overdose would have beaten a hyperglycaemic meal. Risk is not a
     * quadratic in distance, it has a cliff.
     */
    const val HYPO_FLOOR_PENALTY = 50_000.0

    /** Squared distance from the target, tripled on the low side, with a floor below 70. */
    fun errorOf(glucoseMgDl: Double): Double {
        val underTarget = TARGET_MG_DL - glucoseMgDl
        return when {
            glucoseMgDl < HYPO_FLOOR_MG_DL -> {
                val underFloor = HYPO_FLOOR_MG_DL - glucoseMgDl
                HYPO_FLOOR_PENALTY + underFloor * underFloor * HYPO_WEIGHT
            }
            glucoseMgDl < TARGET_MG_DL -> underTarget * underTarget * HYPO_WEIGHT
            else -> underTarget * underTarget
        }
    }

    /**
     * P(the model's dose would have landed closer to the target), integrated over the belief about
     * the sensitivity rather than evaluated at one value. A wide belief returns about one half, which
     * is the honest answer to "we do not know yet".
     */
    fun probabilityModelBetter(
        actualMgDl: Double,
        injectedUnits: Double,
        suggestedUnits: Double,
        isfMean: Double,
        isfSd: Double
    ): Double {
        val delta = suggestedUnits - injectedUnits
        if (abs(delta) < SAME_DOSE_TOLERANCE_UNITS) return 0.5

        val humanLoss = errorOf(actualMgDl)
        var modelWeight = 0.0
        var total = 0.0
        for (step in -25..25) {
            val offset = isfSd * step / 10.0
            val isf = (isfMean + offset).coerceAtLeast(1.0)
            val weight = exp(-0.5 * (offset / isfSd) * (offset / isfSd))
            val projected = actualMgDl - isf * delta
            if (errorOf(projected) < humanLoss) modelWeight += weight
            total += weight
        }
        return if (total <= 0.0) 0.5 else modelWeight / total
    }

    /** What the data currently says about the user's sensitivity, and how firmly. */
    data class Estimate(val meanMgDlPerUnit: Double, val sdMgDlPerUnit: Double, val meals: Int) {
        /** Nothing has been estimated yet: no fold cleared the model's minimum sample floor. */
        val isEmpty: Boolean get() = meals == 0

        /**
         * An estimate is only a usable yardstick once it is bigger than its own uncertainty. Below
         * that, comparing two doses is comparing noise: the projected outcomes land on top of each
         * other and every verdict is an accident of rounding.
         *
         * The criterion is relative, so it needs no clinical constant - no "sensitivity is normally
         * so many mg/dL" assumption is smuggled in to decide when the app may speak.
         */
        val isUsable: Boolean get() = meals > 0 && meanMgDlPerUnit > sdMgDlPerUnit
    }

    data class Meal(
        val mealAt: Long,
        val actualMgDl: Double,
        val injectedUnits: Double,
        val suggestedUnits: Double,
        val isfMgDlPerUnit: Double,
        /** P(the model's dose was closer), in [0, 1]. One half means undecided. */
        val probability: Double
    ) {
        val modelConfident: Boolean get() = probability >= CONFIDENT_PROBABILITY

        val humanConfident: Boolean get() = probability <= 1.0 - CONFIDENT_PROBABILITY
    }

    data class Result(
        val meals: List<Meal>,
        val window: List<Meal>,
        val estimate: Estimate,
        val modelWins: Int,
        val humanWins: Int,
        val undecided: Int,
        /** Consecutive confident model wins, counted back from the most recent meal. */
        val currentStreak: Int,
        val bestStreak: Int,
        val streakTarget: Int = STREAK_TARGET
    ) {
        val hasEvidence: Boolean get() = meals.isNotEmpty()

        val streakReached: Boolean get() = currentStreak >= streakTarget
    }

    val EMPTY = Result(
        meals = emptyList(),
        window = emptyList(),
        estimate = Estimate(0.0, 0.0, 0),
        modelWins = 0,
        humanWins = 0,
        undecided = 0,
        currentStreak = 0,
        bestStreak = 0
    )

    fun build(
        entries: List<DiaryEntity>,
        samples: List<TrainingSample>,
        windowSize: Int = WINDOW_SIZE
    ): Result {
        val byEntry = entries.associateBy { it.id }
        val judged = samples
            .filter { it.after4h != null }
            .sortedBy { it.timestampMs }
            .mapNotNull { sample ->
                val entry = byEntry[sample.entryId] ?: return@mapNotNull null
                val suggested = entry.suggestedBolus ?: return@mapNotNull null
                Triple(sample, entry.bolus, suggested)
            }

        if (judged.isEmpty()) return EMPTY

        val labelled = samples.filter { it.after4h != null }
        val sensitivities = judged.map { (sample, _) -> leaveOneOutSensitivity(labelled, sample) }
        val estimate = estimateOf(sensitivities.filterNotNull())

        val meals = judged.mapIndexedNotNull { index, paired ->
            val (sample, injected, suggested) = paired
            val actual = sample.after4h ?: return@mapIndexedNotNull null
            val isf = sensitivities[index] ?: estimate.meanMgDlPerUnit
            Meal(
                mealAt = sample.timestampMs,
                actualMgDl = actual,
                injectedUnits = injected,
                suggestedUnits = suggested,
                isfMgDlPerUnit = isf,
                // With no usable estimate there is no counterfactual worth computing. Inventing one
                // from a textbook figure for an average person would be the rule of thumb this app
                // exists to replace, so the meal counts as undecided instead.
                probability = if (estimate.isUsable) {
                    probabilityModelBetter(
                        actualMgDl = actual,
                        injectedUnits = injected,
                        suggestedUnits = suggested,
                        isfMean = isf,
                        isfSd = estimate.sdMgDlPerUnit
                    )
                } else {
                    0.5
                }
            )
        }

        val window = meals.takeLast(windowSize)
        return Result(
            meals = meals,
            window = window,
            estimate = estimate,
            modelWins = window.count { it.modelConfident },
            humanWins = window.count { it.humanConfident },
            undecided = window.count { !it.modelConfident && !it.humanConfident },
            currentStreak = currentStreak(meals),
            bestStreak = bestStreak(meals)
        )
    }

    /**
     * What the folds say, and nothing else. There is no external prior: the whole point is to learn
     * this number from the diary and the CGM records, so a textbook figure for an average patient
     * would be the very thing being replaced.
     *
     * The spread of the folds is the error bar. It answers "how sure is this?" with the only evidence
     * available - how much the estimate moves when a single meal is removed - and it narrows as the
     * folds converge. A single fold has no spread to measure, so it is given a deliberately wide
     * proportional error and shows as though it barely knows anything.
     */
    private fun estimateOf(foldSensitivities: List<Double>): Estimate {
        if (foldSensitivities.isEmpty()) return Estimate(0.0, 0.0, 0)

        val mean = foldSensitivities.average()
        val spread = if (foldSensitivities.size < 2) {
            mean * LONELY_FOLD_RELATIVE_SPREAD
        } else {
            val variance = foldSensitivities.sumOf { (it - mean) * (it - mean) } /
                (foldSensitivities.size - 1)
            sqrt(variance)
        }

        return Estimate(
            meanMgDlPerUnit = mean,
            sdMgDlPerUnit = spread.coerceAtLeast(MIN_ISF_SPREAD),
            meals = foldSensitivities.size
        )
    }

    /**
     * How much one extra unit moves this meal's predicted 4 h glucose, according to a model fitted on
     * the other meals. Read by finite difference rather than from a coefficient, so the time-of-day
     * interactions are included without special handling.
     */
    private fun leaveOneOutSensitivity(
        labelled: List<TrainingSample>,
        sample: TrainingSample
    ): Double? {
        if (labelled.size < RidgeModel.MIN_SAMPLES + 1) return null
        val training = labelled.filter { it.entryId != sample.entryId }
        if (training.size < RidgeModel.MIN_SAMPLES) return null
        val model = RidgeModel.fit(training, RidgeModel.DEFAULT_LAMBDA) ?: return null

        val base = sample.features()
        val more = base.copyOf()
        more[TrainingSample.BOLUS_INDEX] = base[TrainingSample.BOLUS_INDEX] + 1.0

        val drop = model.predict4h(base) - model.predict4h(more)
        return if (drop > 1.0) drop else null
    }

    private fun currentStreak(meals: List<Meal>): Int {
        var streak = 0
        for (meal in meals.asReversed()) {
            if (!meal.modelConfident) break
            streak++
        }
        return streak
    }

    private fun bestStreak(meals: List<Meal>): Int {
        var best = 0
        var run = 0
        meals.forEach { meal ->
            if (meal.modelConfident) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }
}
