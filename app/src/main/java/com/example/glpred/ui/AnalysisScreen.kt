package com.example.glpred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
                title = { Text("Model vs you") },
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
                    OutcomeCard(current)
                }
            }
        }
    }
}

@Composable
private fun CountsCard(analysis: ModelVsHuman) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("What this is built on")
            Line("${analysis.mealsRecorded} meals recorded")
            Line("${analysis.mealsWithSuggestion} of those were entered while a model existed, so only they carry a suggestion")
            Line("${analysis.labelledMeals} have a verified 4 h value")
            val validation = analysis.validationErrorMgDl
            Line(
                if (validation == null) {
                    "Validation error: not computable yet (needs four labelled meals)"
                } else {
                    "Validation error: typical miss ${validation.roundToInt()} mg/dL on meals the model did not see"
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
            Line(
                "Yours from the doses you injected; the model's from the dose it suggested for the " +
                    "same meals.",
                muted = true
            )
            val rows = analysis.ratioByHour
            if (rows.isEmpty()) {
                Line("No meals with carb units recorded yet.", muted = true)
            } else {
                RatioChart(
                    yourPoints = rows,
                    modelCurve = analysis.modelCurve,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Line(
                    "Your dots are meals you actually ate; the model's line is its answer for a " +
                        "typical meal of yours at that hour. Where the two separate, you and the " +
                        "model disagree about how much a carb unit needs.",
                    muted = true
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(analysis: ModelVsHuman) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Did following the model land you in 90-140?")
            val outcome = analysis.outcome
            if (!outcome.hasEvidence) {
                Line(
                    "Nothing to compare yet: this needs meals that carried a suggestion and whose " +
                        "4 h value has since been verified by an import.",
                    muted = true
                )
            } else {
                BucketBar("Followed the suggestion", outcome.followed)
                BucketBar("Went your own way", outcome.notFollowed)
                Line(
                    "Correlation, not proof: you decide when to follow the model, and probably " +
                        "follow it on meals you already feel confident about.",
                    muted = true
                )
            }
        }
    }
}

/**
 * One bucket of meals with the share that landed in band: the number, and a bar drawn from the same
 * share so the two cannot disagree. An empty bucket says "no meals" rather than drawing a 0 % bar,
 * which would look like evidence of failure instead of absence of evidence.
 */
@Composable
private fun BucketBar(label: String, bucket: ModelVsHuman.Bucket) {
    val share = bucket.inBandShare
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (share == null) {
                "$label: no meals"
            } else {
                "$label: ${bucket.inBand} of ${bucket.meals} in band " +
                    "(${(share * 100).roundToInt()} %)"
            },
            fontSize = 13.sp
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        ) {
            if (share != null && share > 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share.toFloat())
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
            }
        }
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
