package com.example.glpred.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The diary as a spreadsheet - the one copy of the data that has no other source.
 *
 * CGM telemetry is disposable: any LibreView export re-imports it in under a second. Hand-entered
 * meals are not. They exist in one place, on one device, behind
 * `fallbackToDestructiveMigration()`, so a schema change drops them with no warning and no path
 * back. This is the way out.
 *
 * Both the local date/time and the raw epoch with its UTC offset are written. The date/time is what
 * the user reads; the offset is what makes the row unambiguous, because the same wall clock names
 * two different instants either side of a DST change - and the app converts between a real instant
 * (diary) and a wall clock read as UTC (CGM) using the zone in force *at conversion time*.
 */
object DiaryCsv {

    val HEADER: List<String> = listOf(
        "id", "date", "time", "utc_offset", "timestamp_ms",
        "glucose_mg_dl", "carbs_cu", "bolus_u", "basis_u",
        "model_suggested_u", "model_predicted_4h_mg_dl"
    )

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Oldest first, matching [DiaryDao.getAllEntriesList]. */
    fun render(entries: List<DiaryEntity>, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append(HEADER.joinToString(separator = ",")).append('\n')
            for (entry in entries) {
                val local = Instant.ofEpochMilli(entry.timestamp).atZone(zone)
                append(entry.id).append(',')
                append(local.format(DATE)).append(',')
                append(local.format(TIME)).append(',')
                append(local.offset.id).append(',')
                append(entry.timestamp).append(',')
                append(entry.glucose).append(',')
                append(number(entry.carbs.toDouble())).append(',')
                append(number(entry.bolus)).append(',')
                append(entry.basis).append(',')
                append(entry.suggestedBolus?.let(::number).orEmpty()).append(',')
                append(entry.predictedGlucose?.let(::number).orEmpty()).append('\n')
            }
        }

    /**
     * Locale.US on purpose: this app is used on a device set to German, where the default locale
     * would render 6.5 as "6,5" and silently shift every column to the right.
     */
    private fun number(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(Locale.US, "%.2f", value)
}
