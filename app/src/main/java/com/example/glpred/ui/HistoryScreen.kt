package com.example.glpred.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.glpred.preprocessing.HistoryRow
import java.time.LocalDate
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
    val context = LocalContext.current

    // Export goes through SAF, like the CGM import in the other direction: no storage permission,
    // no FileProvider, and the file lands wherever the user chooses.
    val csvCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportDiary(uri) { result ->
                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(context, "Exported $count entries", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

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
                },
                actions = {
                    // Disabled on an empty diary: a header-only file is not a backup.
                    IconButton(
                        onClick = { csvCreator.launch("glpred-diary-${LocalDate.now()}.csv") },
                        enabled = rows.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Export diary as CSV"
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
                    fontSize = Type.body,
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
            modifier = Modifier.padding(Space.loose),
            verticalArrangement = Arrangement.spacedBy(Space.normal)
        ) {
            // Date, state and injected dose share one row: the dose is what the card is about, so it
            // belongs where the eye lands first rather than buried in a sentence below.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.tight)) {
                    Text(
                        text = "${entry.formattedDate}   ${entry.formattedTime}",
                        fontSize = Type.section,
                        fontWeight = FontWeight.Medium
                    )
                    StatusDot(stateColor(row.state), stateLabel(row.state))
                }
                // Same shape as the Carbs column: a small label over its value, right-aligned, so the
                // two numbers on the card are read the same way. An unlabelled "6 u" in the corner
                // had no anchor and floated.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Space.tight)
                ) {
                    Text(
                        text = "Injected",
                        fontSize = Type.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${formatUnits(entry.bolus)} u",
                        fontSize = Type.body
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Starting", fontSize = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${entry.glucose} mg/dL", fontSize = Type.body)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Carbs", fontSize = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${formatUnits(entry.carbs.toDouble())} CU",
                        fontSize = Type.body
                    )
                }
            }

            Text(
                text = "Basis ${entry.basis} u" +
                    (entry.suggestedBolus?.let {
                        "   ·   model suggested ${formatUnits(it)} u"
                    } ?: ""),
                fontSize = Type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // The prediction is for the injected dose, so it and the actual value describe the same
            // meal and dose. No error figure: judging the model belongs on its own screen.
            val predicted = row.predictedForInjectedDoseMgDl
            val actual = row.actual4hMgDl
            when {
                predicted != null && actual != null -> Text(
                    text = "4 h: predicted ${predicted.roundToInt()}  →  " +
                        "actual ${actual.roundToInt()} mg/dL",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                actual != null -> Text(
                    text = "4 h actual: ${actual.roundToInt()} mg/dL",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                predicted != null -> Text(
                    text = "4 h: predicted ${predicted.roundToInt()} mg/dL, no CGM value yet",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Text(
                    text = "No model available for a prediction",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Plain clickable text rather than TextButtons: a TextButton carries a 48 dp minimum
            // height, which is most of what made each card so tall at the bottom.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.loose)) {
                Text(
                    text = "Edit",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { editing = true }
                )
                Text(
                    text = "Delete",
                    fontSize = Type.body,
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
                    // The ranges are the same object the validators read, so a label cannot
                    // promise a value the editor rejects.
                    EditField(
                        "Blood glucose (mg/dL), ${EntryLimits.GLUCOSE.start}-${EntryLimits.GLUCOSE.endInclusive}",
                        editGlucose,
                        KeyboardType.Number
                    ) { editGlucose = it }
                    EditField(
                        "Carb unit/s, ${formatUnits(EntryLimits.CARBS.start)}-${formatUnits(EntryLimits.CARBS.endInclusive)}",
                        editCarbs,
                        KeyboardType.Decimal
                    ) { editCarbs = it }
                    EditField(
                        "Injected bolus, ${formatUnits(EntryLimits.BOLUS.start)}-${formatUnits(EntryLimits.BOLUS.endInclusive)} unit/s",
                        editBolus,
                        KeyboardType.Decimal
                    ) { editBolus = it }
                    EditField(
                        "Basis, ${EntryLimits.BASIS.start}-${EntryLimits.BASIS.endInclusive} unit/s",
                        editBasis,
                        KeyboardType.Number
                    ) { editBasis = it }
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
    // Green because this meal is contributing; amber because an import is an action the user can
    // take now; neutral because waiting needs nothing but time. Red is reserved for Delete.
    HistoryRow.State.TRAINABLE -> StateColors.model
    HistoryRow.State.TOO_EARLY -> MaterialTheme.colorScheme.onSurfaceVariant
    HistoryRow.State.NEEDS_IMPORT -> StateColors.waiting
}
