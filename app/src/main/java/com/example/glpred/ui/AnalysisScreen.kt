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
import kotlin.math.abs
import kotlin.math.ceil
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import com.example.glpred.prediction.DoseBenchmark
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
                    BenchmarkCard(current)
                    RecentCard(current)
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
            Line(countsLine(analysis))
        }
    }
}

/**
 * One line, because four said the same thing: a meal with a suggestion almost always has a 4 h value,
 * and the validation error is already an error - "on meals the model was not trained on" is what
 * validation means. The 4 h count is only shown when it genuinely differs, which is the case worth
 * noticing.
 */
private fun countsLine(analysis: ModelVsHuman): String = buildString {
    append("${analysis.mealsRecorded} meals recorded")
    append(" · ${analysis.mealsWithSuggestion} processed")
    if (analysis.labelledMeals != analysis.mealsWithSuggestion) {
        append(" · ${analysis.labelledMeals} with a 4 h value")
    }
    analysis.validationErrorMgDl?.let { append(" · average miss ${it.roundToInt()} mg/dL") }
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
private fun BenchmarkCard(analysis: ModelVsHuman) {
    val metric = analysis.benchmark
    val nothing = !metric.hasEvidence && metric.invertedCurve == 0 &&
        metric.noSlope == 0 && metric.sameDose == 0

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Benchmarking Model vs User")

            if (nothing) {
                Line(
                    "No meal yet has both a suggestion from the model and a verified 4 h value.",
                    muted = true
                )
                return@Column
            }

            if (metric.scored == 0) {
                Text(
                    text = if (metric.invertedCurve > 0) {
                        "Not scoreable yet: the model's curve runs the wrong way"
                    } else {
                        "No meal could be scored yet"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Line(comparisonLine(analysis), muted = true)
                if (metric.invertedCurve > 0) {
                    Line(
                        "Nothing is projected from an inverted curve. The sign of the model's own " +
                            "dose response is the first thing to get right.",
                        muted = true
                    )
                }
                return@Column
            }

            // One number per side, per meal, in the same units: distance from the 90 mg/dL every dose on
            // both sides is aiming at. A dose is a forecast of 90, so the model's own prediction for its
            // suggested dose and what the user's dose actually achieved are directly comparable.
            val pairs = missPairs(metric)

            if (pairs.size < 2) {
                Text(
                    text = "Only ${metric.scored} meal could be scored so far",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Line(comparisonLine(analysis), muted = true)
                return@Column
            }

            val modelMiss = runningAverage(pairs.map { it.first })
            val userMiss = runningAverage(pairs.map { it.second })

            Text(
                text = "Model ${modelMiss.last().roundToInt()} mg/dL from 90, " +
                    "you ${userMiss.last().roundToInt()} mg/dL",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Line(comparisonLine(analysis), muted = true)

            Figure("Average distance from 90 mg/dL, meal by meal") {
                MissPlot(model = modelMiss, user = userMiss)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 34.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Caption("first meal")
                    Caption("meal ${pairs.size}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DotLegend(Color(0xFF2E7D32), "model's dose")
                    DotLegend(MaterialTheme.colorScheme.primary, "your dose")
                }
            }
        }
    }
}

/**
 * The trailing window on its own, as its own card: the axis rescales to those twenty meals, so recent
 * movement is visible instead of flattened by the early history in the card above.
 */
@Composable
private fun RecentCard(analysis: ModelVsHuman) {
    val recent = missPairs(analysis.benchmark).takeLast(DoseBenchmark.WINDOW_SIZE)

    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Last ${recent.size}")
            if (recent.size < 2) {
                Line("Not enough scored meals in the recent window yet.", muted = true)
                return@Column
            }
            MissPlot(
                model = runningAverage(recent.map { it.first }),
                user = runningAverage(recent.map { it.second })
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DotLegend(Color(0xFF2E7D32), "model's dose")
                DotLegend(MaterialTheme.colorScheme.primary, "your dose")
            }
        }
    }
}

/** Both sides' distance from the target the dose was aiming at, per scored meal, in input order. */
private fun missPairs(metric: DoseBenchmark.Metric): List<Pair<Double, Double>> =
    metric.scores.filter { it.isScored }.mapNotNull { score ->
        score.projectedModelGlucose?.let { projected ->
            abs(projected - TARGET_MG_DL) to abs(score.meal.actual4h - TARGET_MG_DL)
        }
    }

/**
 * How much of the diary the comparison actually covers. The reasons a meal cannot be compared are
 * still counted by [DoseBenchmark.Metric] - same dose, no usable dose response, no 4 h value - but they
 * are not printed here: "scored", "inverted curve" and "no slope captured" are the vocabulary of the
 * implementation, not of the person reading the screen.
 */
private fun comparisonLine(analysis: ModelVsHuman): String {
    val metric = analysis.benchmark
    val skipped = analysis.mealsRecorded - metric.scored
    val base = "${metric.scored} of ${analysis.mealsRecorded} meals compared"
    return if (skipped > 0) "$base · $skipped skipped" else base
}

/**
 * A titled frame around one illustration, so its caption, plot and legend are visibly a single thing
 * and cannot be confused with the one above or below.
 */
@Composable
private fun Figure(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(text = text, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The glucose both sides dose towards: a dose is a forecast of this value. */
private const val TARGET_MG_DL = 90.0

/** Cumulative mean, so the last value is the overall average and the curve is its own history. */
private fun runningAverage(values: List<Double>): List<Double> {
    var sum = 0.0
    return values.mapIndexed { index, value ->
        sum += value
        sum / (index + 1)
    }
}

/**
 * Two averages over the meals, in mg/dL from target. Where the model's line sits below the user's and
 * stays there, the model's dose has overtaken the user's own dosing; a crossing is the whole result.
 *
 * No per-meal verdict is drawn: a verdict discards how far each side was, which is the only quantity
 * worth comparing, and it degenerates into ties once the user starts following the model.
 */
@Composable
private fun MissPlot(model: List<Double>, user: List<Double>) {
    val green = Color(0xFF2E7D32)
    val you = MaterialTheme.colorScheme.primary
    val ceiling = maxOf(model.maxOrNull() ?: 1.0, user.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val top = ceil(ceiling / 20.0) * 20.0

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .height(72.dp)
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Caption(top.roundToInt().toString())
            Caption((top / 2).roundToInt().toString())
            Caption("0")
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(72.dp)
        ) {
            val count = maxOf(model.size, user.size)
            if (count < 2) return@Canvas

            fun yOf(value: Double): Float =
                (size.height * (1.0 - (value / top).coerceIn(0.0, 1.0))).toFloat()

            fun xOf(index: Int): Float = size.width * index / (count - 1).toFloat()

            listOf(model to green, user to you).forEach { (values, color) ->
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    val point = Offset(xOf(index), yOf(value))
                    previous?.let { drawLine(color, it, point, strokeWidth = 2.4f) }
                    previous = point
                }
            }
        }
    }
}

@Composable
private fun DotLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Text(
            text = label,
            fontSize = 10.sp,
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
