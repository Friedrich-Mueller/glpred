package com.example.glpred.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.glpred.prediction.DoseRecommendation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDataScreen(
    viewModel: DiaryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToFinal: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review & Prediction") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Edit / Back"
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
                text = "Please review your entry details:",
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
                }
            }

            // ---- prediction of the 3 h glucose and the matching bolus ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val recommendation = viewModel.recommendation) {
                        null -> Text(
                            text = if (viewModel.isPredicting) "Running prediction..." else "No prediction available.",
                            fontSize = 14.sp
                        )

                        is DoseRecommendation.Unavailable -> {
                            Text(
                                text = "No dose recommendation",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = recommendation.reason,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is DoseRecommendation.Available -> {
                            if (recommendation.inRange) {
                                SummaryRow(
                                    label = "Suggested bolus:",
                                    value = "${formatUnits(recommendation.suggestedUnits)} unit/s"
                                )
                                SummaryRow(
                                    label = "Predicted glucose after 4 h (primary):",
                                    value = "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL"
                                )
                                recommendation.predictedGlucose3hMgDl?.let { threeHour ->
                                    SummaryRow(
                                        label = "Predicted glucose after 3 h:",
                                        value = "${threeHour.toInt()} mg/dL"
                                    )
                                }
                            } else {
                                Text(
                                    text = "No dose brings you into 90-140 mg/dL",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Closest with ${formatUnits(recommendation.suggestedUnits)} unit/s: " +
                                        "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL predicted. " +
                                        "Do not treat this as a recommendation.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            // Phase-5 guard: an extrapolation has to say so, whatever it suggests.
                            recommendation.coverageWarning?.let { warning ->
                                Text(
                                    text = warning,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            // A dose without its error is the most misleading thing this screen can
                            // print: the sweep always finds a dose that fits, however wrong the
                            // model is.
                            viewModel.modelQuality?.let { quality ->
                                Text(
                                    text = quality,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Always visible, whatever the model claims.
                    Text(
                        text = "Not medical advice. This is an experiment on your own data, its " +
                            "accuracy is unproven - verify every dose yourself.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Now enter the insulin you actually injected:",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = viewModel.actualBolusStr,
                onValueChange = { viewModel.setActualBolus(it) },
                label = { Text("Bolus injected (units)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    viewModel.validateActualBolus(
                        onValid = { onNavigateToFinal() },
                        onError = { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }

            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit Entry")
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, fontSize = 12.sp, color = Color.Gray)
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
