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
                                    value = "${formatUnits(recommendation.suggestedUnits)} units"
                                )
                                SummaryRow(
                                    label = "Predicted glucose after 3 h:",
                                    value = "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL"
                                )
                            } else {
                                Text(
                                    text = "No dose brings you into 90-140 mg/dL",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Closest with ${formatUnits(recommendation.suggestedUnits)} units: " +
                                        "${recommendation.predictedGlucoseMgDl.toInt()} mg/dL predicted. " +
                                        "Do not treat this as a recommendation.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
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
