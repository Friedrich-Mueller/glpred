package com.example.glpred.preprocessing

import com.example.glpred.data.CgmCsvLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Parity harness: runs the Kotlin pipeline over the prototype export and compares it against the
 * numbers `glpred_feasibility_prototype.ipynb` printed for the same file.
 *
 * The export is 30 MB and deliberately not in git, so the test skips (rather than fails) when it
 * is absent - it does real work on the machine that has the prototype folder and is a no-op
 * elsewhere.
 */
class PreprocessingParityTest {

    companion object {
        private val csv: File? = sequenceOf(
            "glpred_prototype/MaxMustermann_glucose_20-10-2026.csv",
            "../glpred_prototype/MaxMustermann_glucose_20-10-2026.csv"
        ).map(::File).firstOrNull { it.isFile }

        private var series: CgmSeries? = null

        @BeforeClass
        @JvmStatic
        fun loadSeries() {
            val file = csv ?: return
            val readings = file.inputStream().use { CgmCsvLoader.load(it) }.readings
            // Zone pinned so the DST-ambiguous minutes dropped are deterministic on any machine.
            series = CgmSeries.from(readings, ZoneId.of("Europe/Berlin"))
        }

        /** Wall clock as the export writes it, encoded the way [CgmSeries] stores minutes. */
        private fun wallClockMs(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
            LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    @Test
    fun loaderRowCountsMatchTheNotebook() {
        assumeTrue("prototype CSV not present", csv != null)
        val result = csv!!.inputStream().use { CgmCsvLoader.load(it) }

        // Notebook: Filtered shape (280142, 2), 312,981 data rows total.
        assertEquals(280_142, result.readings.size)
        // LibreView record type 6 carries no glucose.
        assertEquals(32_839, result.skippedRowCount)
        // Notebook: 245,391 distinct minute timestamps.
        assertEquals(245_391, result.readings.map { CgmSeries.floorToMinute(it.timestampMs) }.distinct().size)
    }

    @Test
    fun cleanedSeriesDropsOnlyTheAmbiguousAndDisagreeingMinutes() {
        assumeTrue("prototype CSV not present", csv != null)
        val cleaned = series ?: error("series not built")
        // 245,391 distinct minutes - 658 dropped for a spread above 15 mg/dL - 41 dropped as
        // DST-ambiguous (the fall-back hour on 26-10-2025). Zone is pinned, so this is exact.
        assertEquals(244_717, cleaned.size)
    }

    @Test
    fun labelsMatchTheNotebook() {
        assumeTrue("prototype CSV not present", csv != null)
        val cleaned = series ?: error("series not built")

        // Diary rows and their labels, straight from the notebook's cell 8 output.
        val expected = listOf(
            // y, m, d, h, min, label 3 h, label 4 h
            intArrayOf(2026, 10, 17, 12, 45) to (112.0 to 86.00),
            intArrayOf(2026, 10, 18, 13, 45) to (151.0 to 89.00),
            intArrayOf(2026, 10, 19, 7, 33) to (129.5 to 149.75),
            intArrayOf(2026, 10, 19, 12, 40) to (173.6 to 159.80),
            intArrayOf(2026, 10, 19, 18, 22) to (271.5 to 243.00)
        )

        for ((entry, labels) in expected) {
            val (y, mo, d, h, mi) = listOf(entry[0], entry[1], entry[2], entry[3], entry[4])
            val generated = LabelGenerator.labelsFor(wallClockMs(y, mo, d, h, mi), cleaned)
            assertNotNull("3h label missing for ${entry.toList()}", generated.after3h)
            assertNotNull("4h label missing for ${entry.toList()}", generated.after4h)
            assertEquals("3h label for ${entry.toList()}", labels.first, generated.after3h!!, 0.05)
            assertEquals("4h label for ${entry.toList()}", labels.second, generated.after4h!!, 0.05)
        }
    }

    @Test
    fun gapsBeyondTheBridgeLimitStayEmpty() {
        assumeTrue("prototype CSV not present", csv != null)
        val cleaned = series ?: error("series not built")
        // The export's longest outage is 456 minutes; nothing may be interpolated across it.
        val insideOutage = wallClockMs(2026, 7, 24, 11, 0)
        assertEquals(null, cleaned.valueAt(insideOutage))
    }
}
