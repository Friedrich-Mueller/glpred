package com.example.glpred.prediction

import com.example.glpred.data.CgmDao
import com.example.glpred.data.CgmReadingEntity
import com.example.glpred.data.CgmSpan
import com.example.glpred.data.DiaryDao
import com.example.glpred.data.DiaryEntity
import com.example.glpred.data.TrainedModelDao
import com.example.glpred.data.TrainedModelEntity
import com.example.glpred.preprocessing.TrainingSamples
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage must describe the rows the fit actually saw.
 *
 * `IDEA.md:15` asks for safety anchors around meal times the model has never observed, and the
 * coverage guard is that anchor. It used to be fed every diary entry, so a meal whose 4 h label had
 * not resolved still marked its hour as supported - a warning that never fires where it is needed
 * (AUDIT.md M2).
 *
 * The runner is exercised end to end through fake DAOs on purpose: the defect was the *argument*
 * passed to `hoursFrom`, so a unit test of the guard alone would keep passing with the bug present.
 */
class TrainingRunnerCoverageTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val fourHoursMs = 4L * 60L * 60L * 1000L

    @Test
    fun anUnlabelledMealsHourIsNotReportedAsCoverage() {
        val midday = listOf(
            entry(1, hour = 12, minute = 0),
            entry(2, hour = 12, minute = 30),
            entry(3, hour = 13, minute = 15)
        )
        // 03:15, with no import covering its 4 h horizon: never a training row.
        val night = entry(4, hour = 3, minute = 15)

        val trained = trainedOrFail(run(midday + night, readingsFor(midday)))

        assertEquals("only the three labelled meals are training rows", 3, trained.samples4h)
        assertEquals(
            "03:15 has no label, so its hour must not be reported as covered",
            setOf(12, 13),
            trained.coveredHours
        )
    }

    @Test
    fun aLabelledMealAtNightDoesReportItsHour() {
        val midday = listOf(entry(1, hour = 12, minute = 0), entry(2, hour = 12, minute = 30))
        val night = entry(3, hour = 3, minute = 15)

        val trained = trainedOrFail(run(midday + night, readingsFor(midday + night)))

        assertEquals(3, trained.samples4h)
        assertEquals(
            "with a resolved label the night meal is a training row and counts",
            setOf(3, 12),
            trained.coveredHours
        )
    }

    // ---------------------------------------------------------------- harness

    private fun run(entries: List<DiaryEntity>, readings: List<CgmReadingEntity>): TrainingResult =
        runBlocking {
            TrainingRunner(
                diaryDao = FakeDiaryDao(entries),
                cgmDao = FakeCgmDao(readings),
                modelDao = FakeTrainedModelDao()
            ).train()
        }

    private fun trainedOrFail(result: TrainingResult): TrainingResult.Trained {
        assertTrue("expected a fitted model, got $result", result is TrainingResult.Trained)
        return result as TrainingResult.Trained
    }

    /** A reading at each entry's 4 h horizon, in the CGM store's wall-clock encoding. */
    private fun readingsFor(entries: List<DiaryEntity>): List<CgmReadingEntity> = entries.flatMap { entry ->
        val label = TrainingSamples.toWallClockMillis(entry.timestamp, zone) + fourHoursMs
        listOf(
            CgmReadingEntity(timestampMs = label, glucose = 110.0 + entry.id),
            CgmReadingEntity(timestampMs = label + 60_000L, glucose = 111.0 + entry.id)
        )
    }

    private fun entry(id: Long, hour: Int, minute: Int) = DiaryEntity(
        id = id,
        timestamp = LocalDateTime.of(2026, 6, id.toInt(), hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli(),
        glucose = 120,
        carbs = 4f,
        bolus = 6.0,
        basis = 10
    )

    private class FakeDiaryDao(private val entries: List<DiaryEntity>) : DiaryDao {
        override suspend fun insertEntry(entry: DiaryEntity) = Unit

        override fun getAllEntries(): Flow<List<DiaryEntity>> =
            flowOf(entries.sortedByDescending { it.timestamp })

        override suspend fun getAllEntriesList(): List<DiaryEntity> = entries.sortedBy { it.timestamp }

        override suspend fun updateEntry(entry: DiaryEntity) = Unit

        override suspend fun deleteEntry(id: Long) = Unit
    }

    private class FakeCgmDao(private val readings: List<CgmReadingEntity>) : CgmDao {
        override suspend fun clearAll() = Unit

        override suspend fun getAllReadings(): List<CgmReadingEntity> = readings

        override suspend fun getReadingsBetween(fromMs: Long, toMs: Long): List<CgmReadingEntity> =
            readings.filter { it.timestampMs in fromMs..toMs }

        override suspend fun storedSpan(): CgmSpan = CgmSpan(
            firstMs = readings.minOfOrNull { it.timestampMs },
            lastMs = readings.maxOfOrNull { it.timestampMs },
            readings = readings.size
        )
    }

    private class FakeTrainedModelDao : TrainedModelDao {
        private var stored: TrainedModelEntity? = null

        override suspend fun get(): TrainedModelEntity? = stored

        override suspend fun save(model: TrainedModelEntity) {
            stored = model
        }

        override suspend fun clear() {
            stored = null
        }
    }
}
