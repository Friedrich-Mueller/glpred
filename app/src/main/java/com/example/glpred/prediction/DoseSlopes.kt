package com.example.glpred.prediction

import android.content.Context
import android.content.SharedPreferences

/**
 * The slope `dG4/dU` the model had at the moment it made each suggestion, keyed by the entry's
 * timestamp.
 *
 * This is the one quantity the human-vs-model metric cannot recover afterwards (PLAN decision 10).
 * The diary already keeps what was injected, what was suggested and what the model claimed; `cgm.db`
 * supplies what actually happened. The model that answered, however, is overwritten at the next
 * retrain - so without this number a past verdict is recomputed against today's model and the whole
 * history shifts underneath the chart. Capture the slope and every verdict is reproducible for ever;
 * the verdict itself is never stored.
 *
 * Preferences rather than a column on the diary: `diary.db` runs `fallbackToDestructiveMigration` with
 * `exportSchema = false`, so a schema bump would delete the hand-entered meals, which are the only
 * original data in the app. This is derived data and must never be able to take them down.
 *
 * Keyed by timestamp because [com.example.glpred.data.DiaryDao.insertEntry] does not return the
 * generated id. Two entries recorded in the same millisecond would share a key; accepted, since the
 * value is auxiliary and a collision means one meal is judged with its neighbour's slope rather than
 * anything being corrupted.
 *
 * Two known gaps, both deliberate for now: nothing removes a slope when its entry is deleted, so the
 * file grows by one float per deleted meal, and an edit that changes the meal drops the stored
 * suggestion but leaves the slope behind. A reader must therefore require a non-null `suggestedBolus`
 * before using a slope, which is what the metric's join does anyway.
 */
object DoseSlopes {

    private const val PREFS_NAME = "dose_slopes"
    private const val PREFIX = "slope_"

    fun save(context: Context, timestampMs: Long, slopeMgDlPerUnit: Double) {
        prefs(context).edit()
            .putFloat(PREFIX + timestampMs, slopeMgDlPerUnit.toFloat())
            .apply()
    }

    /** Null when no slope was captured for that suggestion - which is not the same as an unusable one. */
    fun load(context: Context, timestampMs: Long): Double? {
        val prefs = prefs(context)
        val key = PREFIX + timestampMs
        return if (prefs.contains(key)) prefs.getFloat(key, 0f).toDouble() else null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
