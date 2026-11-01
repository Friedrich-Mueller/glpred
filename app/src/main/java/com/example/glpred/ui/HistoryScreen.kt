package com.example.glpred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glpred.preprocessing.HistoryRow
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
                            HistoryCard(
                                row = row,
                                onSave = { date, time, glucose, carbs, bolus, basis ->
                                    viewModel.updateEntry(
                                        row.entry, date, time, glucose, carbs, bolus, basis
                                    )
                                },
                                onDelete = { viewModel.deleteEntry(row.entry.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    row: HistoryRow,
    /** Returns null when the edit was accepted, or the reason it was not. */
    onSave: (String, String, String, String, String, String) -> String?,
    onDelete: () -> Unit
) {
    val entry = row.entry
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var editDate by remember(entry.id) { mutableStateOf(entry.formattedDate) }
    var editTime by remember(entry.id) { mutableStateOf(entry.formattedTime) }
    var editGlucose by remember(entry.id) { mutableStateOf(entry.glucose.toString()) }
    var editCarbs by remember(entry.id) { mutableStateOf(formatUnits(entry.carbs.toDouble())) }
    var editBolus by remember(entry.id) { mutableStateOf(formatUnits(entry.bolus)) }
    var editBasis by remember(entry.id) { mutableStateOf(entry.basis.toString()) }
    var editError by remember(entry.id) { mutableStateOf<String?>(null) }

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
                    "${formatUnits(entry.carbs.toDouble())} carb unit/s  ·  basis ${entry.basis} u",
                fontSize = 13.sp
            )

            Text(
                text = "Injected ${formatUnits(entry.bolus)} u" +
                    (entry.suggestedBolus?.let { "  ·  model suggested ${formatUnits(it)} u" } ?: ""),
                fontSize = 13.sp
            )

            // The prediction is for the injected dose, so it and the actual value describe the same
            // meal and dose. No error figure: judging the model belongs on its own screen.
            val predicted = row.predictedForInjectedDoseMgDl
            val actual = row.actual4hMgDl
            when {
                predicted != null && actual != null -> Text(
                    text = "4 h: predicted ${predicted.roundToInt()}  →  " +
                        "actual ${actual.roundToInt()} mg/dL",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                actual != null -> Text(
                    text = "4 h actual: ${actual.roundToInt()} mg/dL",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                predicted != null -> Text(
                    text = "4 h: predicted ${predicted.roundToInt()} mg/dL, no CGM value yet",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Text(
                    text = "No model available for a prediction",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Plain clickable text rather than TextButtons: a TextButton carries a 48 dp minimum
            // height, which is most of what made each card so tall at the bottom.
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = "Edit",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { editing = true }
                )
                Text(
                    text = "Delete",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { confirmingDelete = true }
                )
            }
        }
    }

    // Correcting an entry: any of the six recorded values, not just the dose - a meal logged at the
    // wrong time, or with the carbs misread off the packet, is just as common as a wrong dose.
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit entry") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    EditField("Date (dd.MM.yyyy)", editDate) { editDate = it }
                    EditField("Time (HH:mm)", editTime) { editTime = it }
                    EditField("Blood glucose (mg/dL)", editGlucose, KeyboardType.Number) { editGlucose = it }
                    EditField("Carb unit/s", editCarbs, KeyboardType.Decimal) { editCarbs = it }
                    EditField("Injected bolus (unit/s)", editBolus, KeyboardType.Decimal) { editBolus = it }
                    EditField("Basis (unit/s)", editBasis, KeyboardType.Number) { editBasis = it }
                    editError?.let { message ->
                        Text(
                            text = message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "Changing the meal itself (date, time, glucose, carbs or basis) drops " +
                            "the stored suggestion, because it was made for the meal as it was.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val error = onSave(
                            editDate, editTime, editGlucose, editCarbs, editBolus, editBasis
                        )
                        if (error == null) {
                            editing = false
                        } else {
                            editError = error
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this meal?") },
            text = {
                Text(
                    "It disappears from the diary and from the training data. The model keeps the " +
                        "coefficients it was fitted with and is marked as out of date."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
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
