package com.example.glpred.data

import java.io.InputStream

/**
 * Reads the glucose CSV exported from LibreView in a single pass over the raw file bytes.
 *
 * The export is read into one byte buffer and scanned in place, so no per-line [String],
 * `split()` or `substring()` objects are created for the ~313k rows of the file. Timestamps and
 * glucose values are parsed straight out of that buffer into primitives, so a data row allocates
 * nothing beyond the [CgmReadingEntity] it produces.
 *
 * Every row that carries a glucose value is kept: no date window, no deduplication — dedupe,
 * averaging and gap policy belong to preprocessing. Rows carrying neither a historic nor a scan
 * glucose value (LibreView's non-reading record types) are counted in
 * [CgmLoadResult.skippedRowCount].
 */
object CgmCsvLoader {

    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()
    private const val COMMA = ','.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val COLON = ':'.code.toByte()

    /** `dd-MM-yyyy HH:mm` is what LibreView writes into `Device Timestamp`. */
    private const val TIMESTAMP_LENGTH = 16
    private const val NO_TIMESTAMP = Long.MIN_VALUE
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2999

    /**
     * Parses the CSV into raw glucose readings, ready to be stored in [CgmDatabase].
     *
     * [CgmReadingEntity.timestampMs] is the export's wall clock encoded as milliseconds since the
     * epoch *as if that wall clock were UTC*; see that property for why the wall clock is not
     * resolved against the device time zone.
     */
    fun load(input: InputStream): CgmLoadResult {
        // One allocation for the whole export (~30 MB) instead of one String per line.
        val bytes = input.use { it.readBytes() }

        // Header line 1 describes the export ("Glucose Data,Generated on,...").
        val headerStart = skipLine(bytes, 0)
        require(headerStart < bytes.size) { "The selected file is empty." }
        val headerEnd = indexOfLineFeed(bytes, headerStart)

        var tsCol = -1
        var historicCol = -1
        var scanCol = -1
        var col = 0
        var fieldStart = headerStart
        var k = headerStart
        while (k <= headerEnd) {
            if (k == headerEnd || bytes[k] == COMMA) {
                val h = String(bytes, fieldStart, k - fieldStart, Charsets.US_ASCII).lowercase()
                if (h.contains("device timestamp")) tsCol = col
                if (h.contains("historic glucose")) historicCol = col
                if (h.contains("scan glucose")) scanCol = col
                col++
                fieldStart = k + 1
            }
            k++
        }

        require(tsCol >= 0 && (historicCol >= 0 || scanCol >= 0)) {
            "The selected file is not a LibreView glucose export, header was: " +
                String(bytes, headerStart, headerEnd - headerStart, Charsets.US_ASCII)
        }

        val readings = ArrayList<CgmReadingEntity>(320_000)
        var skippedRowCount = 0

        var cursor = headerEnd
        while (cursor < bytes.size) {
            // Move past the previous line feed onto the first character of this line.
            while (cursor < bytes.size && bytes[cursor] != LF) cursor++
            cursor++
            if (cursor >= bytes.size) break

            val lineStart = cursor
            var lineEnd = indexOfLineFeed(bytes, lineStart)
            cursor = lineEnd

            // The trailing '\r' of CRLF line endings is not part of the last field.
            if (lineEnd > lineStart && bytes[lineEnd - 1] == CR) lineEnd--
            if (lineEnd == lineStart) continue

            var rowCol = 0
            var start = lineStart
            var timestampMs = NO_TIMESTAMP
            var scanValue = Double.NaN
            var historicValue = Double.NaN

            var p = lineStart
            while (p <= lineEnd) {
                if (p == lineEnd || bytes[p] == COMMA) {
                    val from = trimFieldStart(bytes, start, p)
                    val to = trimFieldEnd(bytes, from, p)
                    when (rowCol) {
                        tsCol -> timestampMs = parseTimestampMs(bytes, from, to)
                        scanCol -> scanValue = parseGlucose(bytes, from, to)
                        historicCol -> historicValue = parseGlucose(bytes, from, to)
                    }
                    rowCol++
                    start = p + 1
                }
                p++
            }

            val glucose = if (!scanValue.isNaN()) scanValue else historicValue
            if (glucose.isNaN() || timestampMs == NO_TIMESTAMP) {
                skippedRowCount++
            } else {
                readings.add(CgmReadingEntity(timestampMs = timestampMs, glucose = glucose))
            }
        }

        return CgmLoadResult(readings = readings, skippedRowCount = skippedRowCount)
    }

    /**
     * Parses the export's local wall-clock `dd-MM-yyyy HH:mm` field into milliseconds since the
     * epoch, treating that wall clock as if it were UTC (see [CgmReadingEntity.timestampMs]).
     *
     * Pure integer arithmetic: no [String], no `java.time` object and no allocation per row.
     * Returns [NO_TIMESTAMP] for a field that is not a valid wall-clock timestamp.
     */
    private fun parseTimestampMs(bytes: ByteArray, from: Int, to: Int): Long {
        if (to - from != TIMESTAMP_LENGTH) return NO_TIMESTAMP
        val day = twoDigits(bytes, from) ?: return NO_TIMESTAMP
        if (bytes[from + 2] != MINUS) return NO_TIMESTAMP
        val month = twoDigits(bytes, from + 3) ?: return NO_TIMESTAMP
        if (bytes[from + 5] != MINUS) return NO_TIMESTAMP
        val year = fourDigits(bytes, from + 6) ?: return NO_TIMESTAMP
        if (bytes[from + 10] != SPACE) return NO_TIMESTAMP
        val hour = twoDigits(bytes, from + 11) ?: return NO_TIMESTAMP
        if (bytes[from + 13] != COLON) return NO_TIMESTAMP
        val minute = twoDigits(bytes, from + 14) ?: return NO_TIMESTAMP

        if (day !in 1..31 || month !in 1..12 || hour > 23 || minute > 59) return NO_TIMESTAMP
        if (year !in MIN_YEAR..MAX_YEAR) return NO_TIMESTAMP
        // Rejects impossible dates such as 31-02-2025.
        if (day > daysInMonth(year, month)) return NO_TIMESTAMP

        val epochDay = epochDayFromCivil(year, month, day)
        return epochDay * MILLIS_PER_DAY + (hour * 60L + minute) * MILLIS_PER_MINUTE
    }

    /** Two ASCII digits at [at] as 0..99, or null if either byte is not a digit. */
    private fun twoDigits(bytes: ByteArray, at: Int): Int? {
        val tens = bytes[at].toInt() - '0'.code
        val ones = bytes[at + 1].toInt() - '0'.code
        if (tens !in 0..9 || ones !in 0..9) return null
        return tens * 10 + ones
    }

    /** Four ASCII digits at [at] as 0..9999, or null if any byte is not a digit. */
    private fun fourDigits(bytes: ByteArray, at: Int): Int? {
        val thousands = twoDigits(bytes, at) ?: return null
        val units = twoDigits(bytes, at + 2) ?: return null
        return thousands * 100 + units
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    }

    /**
     * Days from 1970-01-01 for a proleptic Gregorian date, after Howard Hinnant's
     * `days_from_civil`. Integer-only on purpose: [java.time.LocalDate.of] would allocate a
     * temporary object for each of the ~280k rows.
     */
    private fun epochDayFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    /** Index of the line feed that ends the line starting at [from], or the file end. */
    private fun indexOfLineFeed(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size && bytes[i] != LF) i++
        return i
    }

    /** Index just past the line feed of the line that starts at [from]. */
    private fun skipLine(bytes: ByteArray, from: Int): Int {
        val end = indexOfLineFeed(bytes, from)
        return if (end < bytes.size) end + 1 else end
    }

    private fun trimFieldStart(bytes: ByteArray, start: Int, end: Int): Int {
        var s = start
        while (s < end && (bytes[s] == SPACE || bytes[s] == QUOTE)) s++
        return s
    }

    private fun trimFieldEnd(bytes: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start && (bytes[e - 1] == SPACE || bytes[e - 1] == QUOTE || bytes[e - 1] == CR)) e--
        return e
    }

    /** Parses a plain decimal number in place; returns NaN for an empty or malformed field. */
    private fun parseGlucose(bytes: ByteArray, from: Int, to: Int): Double {
        if (from >= to) return Double.NaN

        var i = from
        var negative = false
        if (bytes[i] == MINUS) {
            negative = true
            i++
        }
        if (i >= to) return Double.NaN

        var value = 0.0
        while (i < to) {
            if (bytes[i] == DOT) {
                i++
                var scale = 0.1
                while (i < to) {
                    val d = bytes[i].toInt() - '0'.code
                    if (d !in 0..9) return Double.NaN
                    value += d * scale
                    scale /= 10.0
                    i++
                }
                break
            }
            val d = bytes[i].toInt() - '0'.code
            if (d !in 0..9) return Double.NaN
            value = value * 10 + d
            i++
        }
        return if (negative) -value else value
    }
}

/**
 * Outcome of [CgmCsvLoader.load]: raw telemetry [readings] grid plus skipped row count.
 */
data class CgmLoadResult(
    val readings: List<CgmReadingEntity>,
    val skippedRowCount: Int
)
