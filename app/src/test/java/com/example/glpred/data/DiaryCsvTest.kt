package com.example.glpred.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The export is a backup, so the two things that can break it silently are the column order and the
 * decimal separator - both pinned here.
 */
class DiaryCsvTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun entry(
        id: Long, local: java.time.LocalDateTime, carbs: Float, bolus: Double,
        suggested: Double? = null, predicted: Double? = null
    ) = DiaryEntity(
        id = id,
        timestamp = local.atZone(berlin).toInstant().toEpochMilli(),
        glucose = 128,
        carbs = carbs,
        bolus = bolus,
        basis = 0,
        suggestedBolus = suggested,
        predictedGlucose = predicted
    )

    @Test
    fun headerNamesEveryColumnInOrder() {
        val csv = DiaryCsv.render(emptyList(), berlin)

        assertEquals(
            "id,date,time,utc_offset,timestamp_ms,glucose_mg_dl,carbs_cu,bolus_u,basis_u," +
                "model_suggested_u,model_predicted_4h_mg_dl",
            csv.trimEnd('\n')
        )
    }

    @Test
    fun aRowCarriesItsWallClockAndItsOffset() {
        val summer = entry(1, java.time.LocalDateTime.of(2026, 10, 18, 8, 30), 5.0f, 5.5)
        val winter = entry(2, java.time.LocalDateTime.of(2026, 11, 1, 18, 23), 6.0f, 6.0)

        val lines = DiaryCsv.render(listOf(summer, winter), berlin).lines()

        assertEquals("1,18.10.2026,08:30,+02:00,${summer.timestamp},128,5,5.50,0,,", lines[1])
        assertEquals("2,01.11.2026,18:23,+01:00,${winter.timestamp},128,6,6,0,,", lines[2])
    }

    @Test
    fun aStoredSuggestionSurvivesTheRoundTrip() {
        val row = entry(3, java.time.LocalDateTime.of(2026, 11, 1, 13, 45), 4.5f, 5.0, 1.0, 160.5026)

        assertEquals("3,01.11.2026,13:45,+01:00,${row.timestamp},128,4.50,5,0,1,160.50",
            DiaryCsv.render(listOf(row), berlin).lines()[1])
    }

    @Test
    fun aGermanDeviceGetsPointsNotCommas() {
        val row = entry(4, java.time.LocalDateTime.of(2026, 11, 1, 8, 0), 4.5f, 6.5)
        val previous = Locale.getDefault()
        val csv = try {
            Locale.setDefault(Locale.GERMANY)
            DiaryCsv.render(listOf(row), berlin)
        } finally {
            Locale.setDefault(previous)
        }

        assertTrue("half units must stay parseable in any spreadsheet", csv.contains(",4.50,6.50,"))
        assertTrue("a decimal comma would shift every column", !csv.contains(",4,50,"))
    }
}
