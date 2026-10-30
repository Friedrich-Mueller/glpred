package com.example.glpred.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/**
 * Imports the raw glucose CSV exported from LibreView into [CgmDatabase].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchDataScreen(
    onNavigateBack: () -> Unit,
    viewModel: FetchDataViewModel = viewModel()
) {
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importCsv(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fetch CGM Data") },
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
