package com.example.glpred.prediction

import android.content.Context
import android.content.SharedPreferences
import com.example.glpred.preprocessing.TrainingSample

/**
 * Which hours of the day the current model actually has training support for.
 *
 * The model is fitted on the hours the user eats, and its time interactions are linear in sin/cos,
 * so a prediction for 03:00 extrapolates a curve that was never observed there and returns a
 * confident-looking number regardless. This records the observed hours so the advisor can warn.
 *
 * Deliberately stored in preferences rather than the model table: the model table is a Room entity
 * inside `diary.db`, and adding a column there would bump the schema and destroy the hand-entered
 * diary via the configured destructive migration. Preferences cannot take the diary down with them.
 */
object PredictionCoverage {

    private const val PREFS_NAME = "prediction_coverage"
    private const val KEY_HOURS = "trained_hours"
    private const val KEY_TRAINED_AT = "trained_at"

    /** An entry is covered when some recorded meal falls within this many minutes of its hour. */
    private const val TOLERANCE_MINUTES = 60

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Hours of the day (0..23) that appear in the training rows. */
    fun hoursFrom(samples: List<TrainingSample>): Set<Int> =
        samples.mapTo(HashSet()) { TrainingSample.minuteOfDay(it.timestampMs) / 60 }

    fun save(context: Context, hours: Set<Int>) {
        prefs(context).edit()
            .putString(KEY_HOURS, hours.sorted().joinToString(","))
            .putLong(KEY_TRAINED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Null when no training run has recorded coverage yet. */
    fun load(context: Context): Set<Int>? {
        val encoded = prefs(context).getString(KEY_HOURS, null) ?: return null
        return encoded.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..23 }
            .toSet()
    }

    /** True when [minuteOfDay] is within [TOLERANCE_MINUTES] of a covered hour, across midnight. */
    fun isCovered(hours: Set<Int>, minuteOfDay: Int): Boolean =
        hours.any { hour ->
            val centre = hour * 60
            var distance = kotlin.math.abs(minuteOfDay - centre)
            if (distance > TWELVE_HOURS_MINUTES) distance = MINUTES_PER_DAY - distance
            distance <= TOLERANCE_MINUTES
        }

    private const val MINUTES_PER_DAY = 24 * 60
    private const val TWELVE_HOURS_MINUTES = 12 * 60
}
