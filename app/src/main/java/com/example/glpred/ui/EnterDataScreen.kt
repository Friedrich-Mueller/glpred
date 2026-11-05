package com.example.glpred.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

    // Seeded from the draft in the ViewModel, not from the clock: the review screen already shows
    // this meal's own date and time, and coming back to edit it used to replace both with "now".
    var selectedDate by remember {
        mutableStateOf(viewModel.tempDate.toDraftDateOrNull() ?: LocalDate.now())
    }
    var selectedTime by remember {
        mutableStateOf(viewModel.tempTime.toDraftTimeOrNull() ?: LocalTime.now())
    }

    var glucoseStr by remember { mutableStateOf(viewModel.tempGlucose) }
    var carbsStr by remember { mutableStateOf(viewModel.tempCarbs) }
    var basisStr by remember { mutableStateOf(viewModel.tempBasis) }

    val formattedDate = remember(selectedDate) { selectedDate.format(DRAFT_DATE) }
    val formattedTime = remember(selectedTime) { selectedTime.format(DRAFT_TIME) }

    // Leaving without saving must not pre-fill the next meal: the three text fields are seeded from
    // the draft, so an abandoned entry used to leak its values into the next one. System back is
    // routed through the same call, or only the toolbar arrow would clear it.
    val discardAndLeave = {
        viewModel.discardDraft()
        onNavigateBack()
    }
    BackHandler(onBack = discardAndLeave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enter Data") },
                navigationIcon = {
                    IconButton(onClick = discardAndLeave) {
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
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                                selectedDate = picked
                                // Written back on pick, so a rotation keeps the choice too.
                                viewModel.setDraftDate(picked.format(DRAFT_DATE))
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
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                val picked = LocalTime.of(hourOfDay, minute)
                                selectedTime = picked
                                viewModel.setDraftTime(picked.format(DRAFT_TIME))
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
                supportingText = { Text("${EntryLimits.GLUCOSE.start}-${EntryLimits.GLUCOSE.endInclusive} mg/dL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = carbsStr,
                onValueChange = { carbsStr = it },
                label = { Text("Carbohydrates (units)") },
                supportingText = { Text("${formatUnits(EntryLimits.CARBS.start)}-${formatUnits(EntryLimits.CARBS.endInclusive)} CU") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = basisStr,
                onValueChange = { basisStr = it },
                label = { Text("Basis (Long-Acting Insulin)") },
                supportingText = { Text("${EntryLimits.BASIS.start}-${EntryLimits.BASIS.endInclusive} u") },
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

/** The draft's formats: `DiaryViewModel` stores these strings and the review screen shows them. */
private val DRAFT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DRAFT_TIME = DateTimeFormatter.ofPattern("HH:mm")

/** The stored draft value, or null when the field is still blank (a fresh entry) or unreadable. */
private fun String.toDraftDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(this, DRAFT_DATE) }.getOrNull()

private fun String.toDraftTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this, DRAFT_TIME) }.getOrNull()
