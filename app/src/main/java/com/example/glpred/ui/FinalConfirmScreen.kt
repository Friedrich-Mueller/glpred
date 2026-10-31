package com.example.glpred.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Check the entry before it is written to the diary:",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryRow(label = "Date:", value = viewModel.tempDate)
                    SummaryRow(label = "Time:", value = viewModel.tempTime)
                    SummaryRow(label = "Blood Glucose:", value = "${viewModel.tempGlucose} mg/dL")
                    SummaryRow(label = "Carbohydrates:", value = "${viewModel.tempCarbs} units")
                    SummaryRow(label = "Basis (Long-Acting):", value = "${viewModel.tempBasis} units")
                    SummaryRow(
                        label = "Bolus injected (Short-Acting):",
                        value = "${viewModel.actualBolusStr} units"
                    )
                }
            }

            if (suggestion != null) {
                Text(
                    text = "The app suggested ${formatUnits(suggestion.suggestedUnits)} units " +
                        "(predicted ${suggestion.predictedGlucoseMgDl.toInt()} mg/dL).",
                    fontSize = 13.sp,
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
