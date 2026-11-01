package com.example.glpred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glpred.preprocessing.HistoryRow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Past meals, newest first: what was recorded, what the model suggested, and - once the CGM import
 * covers the horizon - what actually happened.
 *
 * The badge is the point of the screen. A meal is a training row only when its 4 h label resolved,
 * and the two ways of being pending need different actions from the user: wait, or re-import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiaryViewModel = viewModel()
) {
    val rows by viewModel.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                viewModel.isHistoryLoading -> Text(
                    text = "Loading meals...",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                rows.isEmpty() -> Text(
                    text = "No meals recorded yet. Each entry you save appears here with its " +
                        "prediction and, once the CGM import covers it, what actually happened.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    val trainable = rows.count { it.state == HistoryRow.State.TRAINABLE }
                    Text(
                        text = "$trainable of ${rows.size} meals are trainable " +
                            "(4 h value known)",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.entry.id }) { row ->
                            HistoryCard(row)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow) {
    val entry = row.entry
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${entry.formattedDate}   ${entry.formattedTime}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Text(text = stateLabel(row.state), fontSize = 13.sp, color = stateColor(row.state))

            Text(
                text = "${entry.glucose} mg/dL starting  ·  " +
                    "${formatUnits(entry.carbs.toDouble())} carb units  ·  basis ${entry.basis} u",
                fontSize = 13.sp
            )

            Text(
                text = "Injected ${formatUnits(entry.bolus)} u" +
                    (entry.suggestedBolus?.let { "  ·  model suggested ${formatUnits(it)} u" } ?: ""),
                fontSize = 13.sp
            )

            // Prediction against reality, the only column that can judge the model itself.
            val predicted = entry.predictedGlucose
            val actual = row.actual4hMgDl
            when {
                predicted != null && actual != null -> {
                    val error = row.predictionErrorMgDl?.roundToInt()
                    Text(
                        text = "4 h: predicted ${predicted.roundToInt()}  →  " +
                            "actual ${actual.roundToInt()} mg/dL" +
                            (error?.let { "   (off by ${abs(it)})" } ?: ""),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                actual != null -> Text(
                    text = "4 h actual: ${actual.roundToInt()} mg/dL",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                predicted != null -> Text(
                    text = "4 h: predicted ${predicted.roundToInt()} mg/dL - not verifiable yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Text(
                    text = "No prediction was stored for this meal",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun stateLabel(state: HistoryRow.State): String = when (state) {
    HistoryRow.State.TRAINABLE -> "Trainable"
    HistoryRow.State.TOO_EARLY -> "Pending - 4 h not elapsed yet"
    HistoryRow.State.NEEDS_IMPORT -> "Pending - re-import your CGM export"
}

@Composable
private fun stateColor(state: HistoryRow.State) = when (state) {
    HistoryRow.State.TRAINABLE -> MaterialTheme.colorScheme.primary
    HistoryRow.State.TOO_EARLY -> MaterialTheme.colorScheme.onSurfaceVariant
    HistoryRow.State.NEEDS_IMPORT -> MaterialTheme.colorScheme.error
}
