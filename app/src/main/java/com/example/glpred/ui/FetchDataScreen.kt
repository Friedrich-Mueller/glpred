package com.example.glpred.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Imports the raw glucose CSV exported from LibreView into [CgmDatabase].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchDataScreen(
    onNavigateBack: () -> Unit,
    diary: DiaryViewModel,
    viewModel: FetchDataViewModel = viewModel()
) {
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importCsv(uri)
        }
    }

    val context = LocalContext.current

    // A fresh import is the only thing that makes new labels exist, so the fit belongs to the same
    // step rather than to a button of its own. Keyed on a counter that is remembered, so a rotation
    // does not refit a second time, and a second import does.
    var trainedImport by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(viewModel.successfulImports) {
        if (viewModel.successfulImports > trainedImport) {
            trainedImport = viewModel.successfulImports
            diary.trainModel()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fetch Data & Train Model") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select your Continuous Glucose Monitor (CGM) export file downloaded from libreview.com (Downloads folder):",
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { csvPicker.launch(arrayOf("*/*")) },
                enabled = !viewModel.isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (viewModel.isImporting) "Processing CSV..." else "Select CGM CSV File")
            }

            if (viewModel.isImporting) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator()
                Text("Importing raw CGM telemetry into SQLite...", fontSize = 14.sp)
            }

            if (viewModel.importSuccess && !viewModel.isImporting) {
                Spacer(modifier = Modifier.height(8.dp))
                val seconds = String.format(Locale.US, "%.1f", viewModel.importDurationMs / 1000.0)
                Text(
                    text = "Successfully loaded ${viewModel.importedCount} raw readings in $seconds seconds.",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            viewModel.storedSpan?.let { span ->
                val first = span.firstMs
                val last = span.lastMs
                if (first != null && last != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(Space.loose),
                            verticalArrangement = Arrangement.spacedBy(Space.normal)
                        ) {
                            Text(
                                text = "Stored CGM data",
                                fontSize = Type.section,
                                fontWeight = FontWeight.Medium
                            )
                            KeyValueRow("From", formatWallClock(first))
                            KeyValueRow("To", formatWallClock(last))
                            KeyValueRow(
                                "Readings",
                                String.format(Locale.US, "%,d", span.readings)
                            )
                        }
                    }
                }
            }

            // Empty state: without this the screen is a heading, a sentence and a button, and looks
            // like it failed to load rather than like nothing has been imported yet.
            if (viewModel.storedSpan == null && !viewModel.isImporting) {
                Text(
                    text = "No CGM data stored yet. Meals stay untrainable until an export covering " +
                        "them is imported.",
                    fontSize = Type.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // The model's state belongs under the stored data, because it describes what that data
            // produced: an import retrains on its own, so the only action left is refitting after
            // the diary itself changed.
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(Space.loose),
                    verticalArrangement = Arrangement.spacedBy(Space.normal)
                ) {
                    Text(
                        text = "Model",
                        fontSize = Type.section,
                        fontWeight = FontWeight.Medium
                    )
                    when {
                        diary.isTraining ->
                            StatusDot(StateColors.waiting, "Training on the stored data...")
                        diary.isModelStale ->
                            StatusDot(StateColors.waiting, "Out of date - the diary changed after this fit")
                        diary.hasModel ->
                            StatusDot(StateColors.model, "Up to date with the stored data")
                    }
                    Text(
                        text = diary.trainingStatus,
                        fontSize = Type.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Always present, greyed while the fit is current. Removing it left the user
                    // wondering where it had gone; greying it says "already done" instead, and a
                    // press still answers the question it raises.
                    val canRetrain = diary.isModelStale && !diary.isTraining
                    Button(
                        onClick = {
                            when {
                                canRetrain -> diary.trainModel()
                                diary.isTraining -> Toast.makeText(
                                    context,
                                    "Training is already running",
                                    Toast.LENGTH_SHORT
                                ).show()
                                else -> Toast.makeText(
                                    context,
                                    "No need to retrain now",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = if (canRetrain) {
                            ButtonDefaults.buttonColors()
                        } else {
                            // Explicit colours rather than `enabled = false`: a disabled button
                            // swallows the tap, and the tap is what carries the explanation.
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Retrain on stored data")
                    }
                }
            }

            viewModel.errorMessage?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * The CGM table stores wall-clock time encoded as UTC millis, so it must be rendered in UTC.
 * Formatting with the device zone would shift the reported span by the current UTC offset.
 */
private fun formatWallClock(epochMs: Long): String = DateTimeFormatter
    .ofPattern("dd.MM.yyyy HH:mm", Locale.US)
    .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC))
