package com.example.glpred.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                text = "Your entry",
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
                    SummaryRow(label = "Carbs", value = "${viewModel.tempCarbs} units")
                    SummaryRow(label = "Basis", value = "${viewModel.tempBasis} units")
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
                                // The dose is the answer this screen exists to give, so it is the one
                                // thing set large; everything qualifying it stays body-sized.
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(Space.tight)
                                ) {
                                    Text(
                                        text = formatUnits(recommendation.suggestedUnits),
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${unitLabel(recommendation.suggestedUnits)} suggested",
                                        fontSize = Type.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = Space.tight)
                                    )
                                }
                                SummaryRow(
                                    label = "Predicted after 4 h",
                                    value = "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL"
                                )
                                recommendation.predictedGlucose3hMgDl?.let { threeHour ->
                                    SummaryRow(
                                        label = "Predicted after 3 h",
                                        value = "${threeHour.toInt()} mg/dL"
                                    )
                                }
                            } else {
                                StatusDot(
                                    color = StateColors.waiting,
                                    label = "No dose brings you into 90-140 mg/dL"
                                )
                                Text(
                                    text = "Closest with ${formatUnits(recommendation.suggestedUnits)} " +
                                        "${unitLabel(recommendation.suggestedUnits)}: " +
                                        "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL predicted. " +
                                        "Do not treat this as a recommendation.",
                                    fontSize = Type.body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Phase-5 guard: an extrapolation has to say so, whatever it suggests.
                            recommendation.coverageWarning?.let { warning ->
                                StatusDot(color = StateColors.waiting, label = warning)
                            }

                            // A dose without its error is the most misleading thing this screen can
                            // print: the sweep always finds a dose that fits, however wrong the
                            // model is.
                            viewModel.modelQuality?.let { quality ->
                                Text(
                                    text = quality,
                                    fontSize = Type.label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Always visible, whatever the model claims.
                    Text(
                        text = "Not medical advice. This is an experiment on your own data, its " +
                            "accuracy is unproven - verify every dose yourself.",
                        fontSize = Type.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "What you injected",
                fontSize = Type.section,
                fontWeight = FontWeight.Medium
            )

            OutlinedTextField(
                value = viewModel.actualBolusStr,
                onValueChange = { viewModel.setActualBolus(it) },
                label = { Text("Bolus injected (units)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            // Pushes the two buttons to the bottom: with the cards above them the column otherwise
            // ends in a void, which reads as a rendering fault rather than as breathing room.
            Spacer(modifier = Modifier.weight(1f))

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
    KeyValueRow(label = label, value = value)
}

/** "1 unit", not "1 units": the number comes from formatUnits, the noun follows it. */
internal fun unitLabel(units: Double): String = if (units == 1.0) "unit" else "units"
