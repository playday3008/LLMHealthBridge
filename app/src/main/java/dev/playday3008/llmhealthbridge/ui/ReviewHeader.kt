package dev.playday3008.llmhealthbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.nutrition.Issue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealType
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.totals
import dev.playday3008.llmhealthbridge.review.withMealType

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Header of one entry: [label], a [Remove][onRemove] action when [onRemove] is not null, and the
 * entry [time] with date and time pickers that report the new time through [onTime].
 */
@Composable
internal fun EntryHeader(
    label: String,
    time: OffsetDateTime,
    timeIssue: Issue?,
    onTime: (OffsetDateTime) -> Unit,
    onRemove: (() -> Unit)?
) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val local = time.atZoneSameInstant(ZoneId.systemDefault())

    val removeDescription =
        stringResource(R.string.entry_remove_description, label, TIME_FORMAT.format(local))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            onRemove?.let {
                TextButton(
                    onClick = it,
                    modifier = Modifier.semantics { contentDescription = removeDescription }
                ) { Text(stringResource(R.string.entry_remove)) }
            }
        }
        TimeRow(label, local, timeIssue, onDate = { pickDate = true }, onTime = { pickTime = true })
    }
    if (pickDate) {
        DatePickDialog(local, onDismiss = { pickDate = false }) { date ->
            pickDate = false
            onTime(atLocal(date, local.toLocalTime()))
        }
    }
    if (pickTime) {
        TimePickDialog(local, onDismiss = { pickTime = false }) { picked ->
            pickTime = false
            onTime(atLocal(local.toLocalDate(), picked))
        }
    }
}

/** Meal type picker and totals of the meal [state]. */
@Composable
internal fun MealFields(
    state: MealReview,
    now: Instant,
    onUpdate: ((MealReview) -> MealReview) -> Unit
) {
    val typeIssue = state.meal.issues(now).find { it.field == "meal_type" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MealTypeDropdown(state.meal, typeIssue, onUpdate)
        TotalsRow(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealTypeDropdown(
    meal: Meal,
    issue: Issue?,
    onUpdate: ((MealReview) -> MealReview) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = meal.invalidMealType ?: meal.mealType.jsonName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.meal_type)) },
            isError = issue != null,
            supportingText = issue?.let { { Text(it.message) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MealType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.jsonName) },
                    onClick = {
                        open = false
                        onUpdate { it.withMealType(type) }
                    }
                )
            }
        }
    }
}

/** Time of the entry [label] as date and time buttons that open the pickers, with its [issue]. */
@Composable
internal fun TimeRow(
    label: String,
    local: ZonedDateTime,
    issue: Issue?,
    onDate: () -> Unit,
    onTime: () -> Unit
) {
    val date = DateTimeFormatter.ISO_LOCAL_DATE.format(local)
    val time = DateTimeFormatter.ofPattern("HH:mm").format(local)
    val dateDescription = stringResource(R.string.entry_date_description, label, date)
    val timeDescription = stringResource(R.string.entry_time_description, label, time)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.meal_time))
            OutlinedButton(
                onClick = onDate,
                modifier = Modifier.semantics { contentDescription = dateDescription }
            ) { Text(date) }
            OutlinedButton(
                onClick = onTime,
                modifier = Modifier.semantics { contentDescription = timeDescription }
            ) { Text(time) }
        }
        issue?.let {
            Text(
                it.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun atLocal(date: LocalDate, time: LocalTime): OffsetDateTime =
    ZonedDateTime.of(date, time, ZoneId.systemDefault()).toOffsetDateTime()

@Composable
private fun DatePickDialog(
    current: ZonedDateTime,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit
) {
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    ) { DatePicker(picker) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    current: ZonedDateTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit
) {
    val picker = rememberTimePickerState(current.hour, current.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(picker.hour, picker.minute)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = { TimePicker(picker) }
    )
}

@Composable
private fun TotalsRow(state: MealReview) {
    val totals = state.totals()
    Card(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                R.string.totals_line,
                totals.kcal.roundToInt(),
                formatNumber(totals.protein),
                formatNumber(totals.carbs),
                formatNumber(totals.fat)
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}
