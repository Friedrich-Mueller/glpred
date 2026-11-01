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
import com.example.glpred.prediction.CounterfactualScore
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
            SectionTitle("Insulin-Carb-Ratio, throughout the day")
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
            SectionTitle("Model vs User performance?")
            val score = analysis.counterfactual
            if (!score.hasEvidence) {
                Line(
                    "No meal yet has both a suggestion from the model and a verified 4 h value.",
                    muted = true
                )
            } else {
                val streak = score.currentStreak
                val nothingDecided = score.modelWins == 0 && score.humanWins == 0
                Text(
                    text = when {
                        nothingDecided -> "Nothing decided yet"
                        streak == 0 -> "Not the model's meal on the latest one"
                        else -> "The model was closer on the last $streak " +
                            "meal${if (streak == 1) "" else "s"} in a row"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = when {
                        score.estimate.isEmpty ->
                            "Not enough meals yet to work out how far a unit takes you."
                        !score.estimate.isUsable ->
                            "Still too rough to act on: it estimates " +
                                "${score.estimate.meanMgDlPerUnit.roundToInt()} ± " +
                                "${score.estimate.sdMgDlPerUnit.roundToInt()} mg/dL per unit, " +
                                "which is mostly uncertainty."
                        else ->
                            "It puts 1 unit at ${score.estimate.meanMgDlPerUnit.roundToInt()} ± " +
                                "${score.estimate.sdMgDlPerUnit.roundToInt()} mg/dL, learned from " +
                                "${score.estimate.meals} " +
                                "meal${if (score.estimate.meals == 1) "" else "s"}."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VerdictStrip(score.window)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StripLegend(Color(0xFF2E7D32), "model closer")
                    StripLegend(MaterialTheme.colorScheme.primary, "you closer")
                    StripLegend(MaterialTheme.colorScheme.outline, "undecided")
                }
                Text(
                    text = "Model ${score.modelWins}, you ${score.humanWins}, undecided " +
                        "${score.undecided}, over the last ${score.window.size} " +
                        "meal${if (score.window.size == 1) "" else "s"}, oldest on the left.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Same two colours as the units-per-carb chart, so the screen reads consistently: green is the
 * model, purple is you, grey is a meal that says nothing either way.
 */
@Composable
private fun outcomeColor(meal: CounterfactualScore.Meal) = when {
    meal.modelConfident -> Color(0xFF2E7D32)
    meal.humanConfident -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

/**
 * One square per judged meal, oldest to newest. A percentage hides streaks, and a streak is the whole
 * point of this card: what matters is not the average, but whether the wins have started to run.
 */
@Composable
private fun VerdictStrip(meals: List<CounterfactualScore.Meal>) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        meals.forEach { meal ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(outcomeColor(meal), RoundedCornerShape(3.dp))
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
