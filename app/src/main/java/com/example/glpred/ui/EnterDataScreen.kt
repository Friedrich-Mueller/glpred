package com.example.glpred.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterDataScreen(
    viewModel: DiaryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToConfirm: () -> Unit
) {
    val context = LocalContext.current

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedTime by remember { mutableStateOf(LocalTime.now()) }

    var glucoseStr by remember { mutableStateOf(viewModel.tempGlucose) }
    var carbsStr by remember { mutableStateOf(viewModel.tempCarbs) }
    var basisStr by remember { mutableStateOf(viewModel.tempBasis) }

    val formattedDate = remember(selectedDate) {
        selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
    val formattedTime = remember(selectedTime) {
        selectedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enter Data") },
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
            // Date Picker Field
            OutlinedTextField(
                value = formattedDate,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Date") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                            },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth
                        ).show()
                    }
            )

            // Time Picker Field (24h format)
            OutlinedTextField(
                value = formattedTime,
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text("Time (24h)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                selectedTime = LocalTime.of(hourOfDay, minute)
                            },
                            selectedTime.hour,
                            selectedTime.minute,
                            true // 24-hour format
                        ).show()
                    }
            )

            OutlinedTextField(
                value = glucoseStr,
                onValueChange = { glucoseStr = it },
                label = { Text("Blood Glucose (mg/dL)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = carbsStr,
                onValueChange = { carbsStr = it },
                label = { Text("Carbohydrates (units)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = basisStr,
                onValueChange = { basisStr = it },
                label = { Text("Basis (Long-Acting Insulin)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val localDateTime = LocalDateTime.of(selectedDate, selectedTime)
                    val epochMillis = localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                    // Runs the advisor, then navigates - the review screen shows its result at once.
                    viewModel.validateAndStartReview(
                        date = formattedDate,
                        time = formattedTime,
                        glucoseStr = glucoseStr,
                        carbsStr = carbsStr,
                        basisStr = basisStr,
                        timestamp = epochMillis,
                        onReady = {
                            onNavigateToConfirm()
                        },
                        onError = { errorMsg ->
                            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Review Entry")
            }
        }
    }
}
