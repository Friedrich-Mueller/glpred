package com.example.glpred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.example.glpred.prediction.DirectionalScore
import com.example.glpred.prediction.ModelVsHuman
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The evidence screen: how the model's dosing compares with the doses actually taken, and whether
 * following it coincided with landing in range.
 *
 * Every number states its own sample size, and none of them claims causation. Judging the model is
 * a different job from showing it, so nothing here feeds back into the entry flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiaryViewModel = viewModel()
) {
    val analysis = viewModel.analysis

    // Recomputed on every visit: the point is to watch it change as meals accumulate.
    LaunchedEffect(Unit) { viewModel.refreshAnalysis() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val current = analysis
            when {
                viewModel.isAnalysisLoading && current == null -> Text(
                    text = "Reading your meals...",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                current == null || current.mealsRecorded == 0 -> Text(
                    text = "No meals recorded yet, so there is nothing to compare.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    CountsCard(current)
                    RatioCard(current)
                    ConvergenceCard(current)
                }
            }
        }
    }
}

@Composable
private fun CountsCard(analysis: ModelVsHuman) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Your data")
            Line("${analysis.mealsRecorded} meals recorded")
            Line("${analysis.mealsWithSuggestion} carry a suggestion from the model")
            Line("${analysis.labelledMeals} have a verified 4 h value")
            val validation = analysis.validationErrorMgDl
            Line(
                if (validation == null) {
                    "Average misprediction: not computable yet (needs four labelled meals)"
                } else {
                    "Average misprediction: ${validation.roundToInt()} mg/dL on meals the model " +
                        "was not trained on"
                }
            )
        }
    }
}

@Composable
private fun RatioCard(analysis: ModelVsHuman) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Units per carb unit, by hour")
            val rows = analysis.ratioByHour
            if (rows.isEmpty()) {
                Line("No meals with carb units recorded yet.", muted = true)
            } else {
                RatioChart(
                    yourPoints = rows,
                    modelCurve = analysis.modelCurve,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ConvergenceCard(analysis: ModelVsHuman) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Did the model point the right way?")
            val score = analysis.directional
            val window = score.window
            if (!score.hasEvidence) {
                Line(
                    "No meal yet has both a suggestion from the model and a verified 4 h value.",
                    muted = true
                )
            } else {
                Text(
                    text = "You ended high ${window.highMeals} times, low ${window.lowMeals}, " +
                        "in band ${window.youInBand}",
                    fontSize = 13.sp
                )
                val robust = score.bracketCounts[DirectionalScore.Bracket.ROBUST] ?: 0
                Text(
                    text = "The model pointed the right way ${window.right} of " +
                        "${window.scored} times it had something to say",
                    fontSize = 13.sp
                )
                VerdictStrip(score.meals, window.size)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StripLegend(verdictColor(DirectionalScore.Verdict.RIGHT), "right")
                    StripLegend(verdictColor(DirectionalScore.Verdict.WRONG), "wrong")
                    StripLegend(verdictColor(DirectionalScore.Verdict.NO_REMEDY), "its own dose")
                    StripLegend(verdictColor(DirectionalScore.Verdict.NO_CLAIM), "in band")
                }
                Text(
                    text = "One square per meal, oldest on the left. If 1 unit moves glucose by " +
                        "${DirectionalScore.SENSITIVITY_LOW.roundToInt()}-" +
                        "${DirectionalScore.SENSITIVITY_HIGH.roundToInt()} mg/dL, its dose would " +
                        "certainly have hit the band in $robust of them.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Green means the model's correction pointed the right way, red the opposite. */
@Composable
private fun verdictColor(verdict: DirectionalScore.Verdict) = when (verdict) {
    DirectionalScore.Verdict.RIGHT -> Color(0xFF2E7D32)
    DirectionalScore.Verdict.WRONG -> MaterialTheme.colorScheme.error
    DirectionalScore.Verdict.NO_REMEDY -> MaterialTheme.colorScheme.outline
    DirectionalScore.Verdict.NO_CLAIM -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * One square per judged meal, oldest to newest: the model's direction verdict. A percentage hides
 * streaks, and streaks are what the eye needs in order to judge a crossover.
 */
@Composable
private fun VerdictStrip(meals: List<DirectionalScore.Meal>, windowSize: Int) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        meals.takeLast(windowSize).forEach { meal ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(verdictColor(meal.verdict), RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun StripLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Line(text: String, muted: Boolean = false) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    )
}
