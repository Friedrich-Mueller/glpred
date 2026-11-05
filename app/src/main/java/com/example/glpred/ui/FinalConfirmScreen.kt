package com.example.glpred.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Final step of the entry flow: the entry plus the dose actually injected, saved to the diary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalConfirmScreen(
    viewModel: DiaryViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val context = LocalContext.current
    val suggestion = viewModel.recommendation as? com.example.glpred.prediction.DoseRecommendation.Available

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Save Entry") },
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
            Text(
                text = "Saving this entry",
                fontSize = Type.section,
                fontWeight = FontWeight.Medium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(Space.loose),
                    verticalArrangement = Arrangement.spacedBy(Space.normal)
                ) {
                    SummaryRow(label = "Date", value = viewModel.tempDate)
                    SummaryRow(label = "Time", value = viewModel.tempTime)
                    SummaryRow(label = "Glucose", value = "${viewModel.tempGlucose} mg/dL")
                    SummaryRow(label = "Carbs", value = "${viewModel.tempCarbs} unit/s")
                    SummaryRow(label = "Basis", value = "${viewModel.tempBasis} unit/s")
                    SummaryRow(
                        label = "Injected",
                        value = "${viewModel.actualBolusStr} unit/s"
                    )
                }
            }

            if (suggestion != null) {
                Text(
                    text = "The app suggested ${formatUnits(suggestion.suggestedUnits)} unit/s " +
                        "(predicted ${suggestion.predictedGlucoseMgDl.toInt()} mg/dL).",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.confirmAndSaveEntry(
                        onSuccess = {
                            Toast.makeText(context, "Entry saved", Toast.LENGTH_SHORT).show()
                            onSaveSuccess()
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Confirm & Save Entry")
            }

            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to review")
            }
        }
    }
}

/** Half-unit aware formatting: 6.0 renders as "6", 6.5 stays "6.5". */
internal fun formatUnits(units: Double): String =
    if (units % 1.0 == 0.0) units.toInt().toString() else String.format(java.util.Locale.US, "%.1f", units)
