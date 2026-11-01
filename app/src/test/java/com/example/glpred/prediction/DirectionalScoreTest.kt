package com.example.glpred.prediction

import com.example.glpred.data.DiaryEntity
import com.example.glpred.preprocessing.TrainingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The direction test rests on one assumption - more insulin lowers glucose - and nothing else. These
 * tests pin the four verdicts and the sensitivity bracket, because a scoring bug here would be
 * invisible on screen: a plausible-looking percentage is indistinguishable from a correct one.
 */
class DirectionalScoreTest {

    private fun entry(
        id: Long,
        bolus: Double,
        suggested: Double?,
        timestamp: Long = id * 1000L
    ) = DiaryEntity(
        id = id,
        timestamp = timestamp,
        glucose = 120,
        carbs = 5f,
        bolus = bolus,
        basis = 0,
        suggestedBolus = suggested
    )

    private fun sample(id: Long, after4h: Double?) = TrainingSample(
        entryId = id,
        timestampMs = id * 1000L,
        glucose = 120.0,
        carbs = 5.0,
        bolus = 0.0,
        basis = 0.0,
        after3h = null,
        after4h = after4h
    )

    @Test
    fun `ending high with the model wanting more insulin is a hit`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 6.0)),
            samples = listOf(sample(1, after4h = 250.0))
        )
        assertEquals(DirectionalScore.Verdict.RIGHT, result.meals.single().verdict)
    }

    @Test
    fun `ending high with the model wanting less insulin is a miss`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 2.0)),
            samples = listOf(sample(1, after4h = 250.0))
        )
        assertEquals(DirectionalScore.Verdict.WRONG, result.meals.single().verdict)
    }

    @Test
    fun `ending low with the model wanting less insulin is a hit`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 8.0, suggested = 5.0)),
            samples = listOf(sample(1, after4h = 55.0))
        )
        assertEquals(DirectionalScore.Verdict.RIGHT, result.meals.single().verdict)
    }

    @Test
    fun `a meal already in band carries no directional claim`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 9.0)),
            samples = listOf(sample(1, after4h = 115.0))
        )
        assertEquals(DirectionalScore.Verdict.NO_CLAIM, result.meals.single().verdict)
        assertEquals(0, result.window.scored)
        assertEquals(1, result.window.youInBand)
    }

    @Test
    fun `an out of band meal with no change suggested is no remedy`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 4.2)),
            samples = listOf(sample(1, after4h = 250.0))
        )
        assertEquals(DirectionalScore.Verdict.NO_REMEDY, result.meals.single().verdict)
        assertEquals(DirectionalScore.Bracket.NONE, result.meals.single().bracket)
    }

    @Test
    fun `a suggestion that only lands in band at some sensitivities is plausible`() {
        // 250 at 4 u; 6 u gives 130 at 60 mg/dL per unit and 210 at 20 - band only at the high end.
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 6.0)),
            samples = listOf(sample(1, after4h = 250.0))
        )
        assertEquals(DirectionalScore.Bracket.PLAUSIBLE, result.meals.single().bracket)
    }

    @Test
    fun `a suggestion that lands in band across the whole range is robust`() {
        // 150 at 4 u; half a unit more gives 140 at 20 per unit and 120 at 60 - inside band either way.
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 4.5)),
            samples = listOf(sample(1, after4h = 150.0))
        )
        assertEquals(DirectionalScore.Bracket.ROBUST, result.meals.single().bracket)
    }

    @Test
    fun `a suggestion that cannot reach band at any plausible sensitivity is implausible`() {
        // 200 at 4 u; dropping to 3 u pushes it further up, to 220-260.
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = 3.0)),
            samples = listOf(sample(1, after4h = 200.0))
        )
        assertEquals(DirectionalScore.Bracket.IMPLAUSIBLE, result.meals.single().bracket)
    }

    @Test
    fun `the window counts only the most recent meals`() {
        val entries = (1L..25L).map { entry(it, bolus = 4.0, suggested = 6.0) }
        val samples = (1L..25L).map { sample(it, after4h = 250.0) }

        val result = DirectionalScore.build(entries, samples)

        assertEquals(25, result.meals.size)
        assertEquals(DirectionalScore.WINDOW_SIZE, result.window.size)
        assertEquals(DirectionalScore.WINDOW_SIZE, result.window.right)
        assertNotNull(result.window.modelShare)
        assertEquals(1.0, result.window.modelShare!!, 1e-9)
    }

    @Test
    fun `meals without a suggestion are not judged`() {
        val result = DirectionalScore.build(
            entries = listOf(entry(1, bolus = 4.0, suggested = null)),
            samples = listOf(sample(1, after4h = 250.0))
        )
        assertEquals(0, result.meals.size)
        assertEquals(false, result.hasEvidence)
    }
}
