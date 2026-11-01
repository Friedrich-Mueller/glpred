package com.example.glpred.data

import android.content.Context
import android.content.SharedPreferences

/**
 * A counter that says "the diary changed since the model was fitted".
 *
 * The stored model is a snapshot: its coefficients were fitted on the rows that existed at training
 * time. Editing or deleting one of those rows invalidates it, and nothing in Room notices - the
 * model row keeps being loaded and keeps predicting, with no sign that it now describes data that is
 * gone.
 *
 * Why preferences rather than a column: `model_table` lives in `diary.db`, which uses
 * `fallbackToDestructiveMigration`, so adding a column would wipe the hand-entered diary. A counter
 * costs one integer and cannot take the data down with it.
 *
 * Only edits and deletes bump the version. A new meal is additive - the model never saw it, but
 * nothing it learned has become false - and the status line already reports the growing sample count.
 */
object DataVersion {

    private const val PREFS_NAME = "diary_data_version"
    private const val KEY_VERSION = "version"
    private const val KEY_TRAINED_VERSION = "trained_version"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(context: Context): Int = prefs(context).getInt(KEY_VERSION, 0)

    /** Records that the diary was edited or a row removed. Returns the new version. */
    fun bump(context: Context): Int {
        val next = current(context) + 1
        prefs(context).edit().putInt(KEY_VERSION, next).apply()
        return next
    }

    /** Which version the current model was fitted on. */
    fun trainedVersion(context: Context): Int = prefs(context).getInt(KEY_TRAINED_VERSION, 0)

    fun markTrained(context: Context) {
        prefs(context).edit().putInt(KEY_TRAINED_VERSION, current(context)).apply()
    }
}
