package dev.playday3008.llmhealthbridge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import java.time.Instant
import java.util.Locale

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.measurement.MeasurementField
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.nutrition.Issue
import dev.playday3008.llmhealthbridge.review.MeasurementReview
import dev.playday3008.llmhealthbridge.review.ReplyReview
import dev.playday3008.llmhealthbridge.review.isDenied
import dev.playday3008.llmhealthbridge.review.isEmptied
import dev.playday3008.llmhealthbridge.review.remove
import dev.playday3008.llmhealthbridge.review.removeValue
import dev.playday3008.llmhealthbridge.review.update
import dev.playday3008.llmhealthbridge.review.withTime
import dev.playday3008.llmhealthbridge.review.withValue

private const val ROW_DECIMALS = 3

private typealias MeasurementUpdate = ((MeasurementReview) -> MeasurementReview) -> Unit

/**
 * One hydration, body or vitals entry of [review]: header and one row per measurement. Tapping a
 * row opens its inline editor. [problems] maps type keys to record build problems.
 */
@Composable
internal fun MeasurementBlock(
    state: MeasurementReview,
    review: ReplyReview,
    problems: Map<String, String>,
    now: Instant,
    actions: ReviewActions
) {
    val onUpdate: MeasurementUpdate = { transform ->
        actions.onUpdate { r -> r.update(state.id) { transform(it as MeasurementReview) } }
    }
    val entry = state.entry
    val deniedLabels = entry.values
        .filter { review.isDenied(it.type.writePermission) }
        .map { it.type.label }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EntryHeader(
            label = stringResource(entryLabel(state.id.type)),
            time = entry.time,
            timeIssue = entry.issues(now).find { it.field == "time" },
            onTime = { time -> onUpdate { it.withTime(time) } },
            onRemove = if (review.reopened) null else ({ actions.onUpdate { it.remove(state.id) } })
        )
        review.replaces[state.id]?.let {
            ReplacesNote(
                it,
                emptied =
                !review.reopened && state.isEmptied()
            )
        }
        if (deniedLabels.isNotEmpty()) DeniedNote(deniedLabels, actions.onOpenSettings)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (entry.values.isEmpty()) {
                    Text(stringResource(R.string.no_values), Modifier.padding(vertical = 8.dp))
                }
                entry.values.forEachIndexed { index, value ->
                    if (index > 0) HorizontalDivider()
                    key(value.type.key) {
                        MeasurementRow(
                            value = value,
                            problem = problems[value.type.key],
                            denied = review.isDenied(value.type.writePermission),
                            onUpdate = onUpdate
                        )
                    }
                }
            }
        }
        if (entry.warnings.isNotEmpty()) WarningsSection(entry.warnings)
    }
}

@StringRes
private fun entryLabel(type: EntryType): Int = when (type) {
    EntryType.NUTRITION -> R.string.entry_meal
    EntryType.HYDRATION -> R.string.entry_hydration
    EntryType.BODY -> R.string.entry_body
    EntryType.VITALS -> R.string.entry_vitals
}

private fun fieldLabel(value: MeasurementValue, field: MeasurementField): String =
    if (value.type.fields.size == 1) {
        value.type.label
    } else {
        field.name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/** The values in canonical units, "?" for a field without a valid value. */
private fun valueText(value: MeasurementValue): String {
    val numbers = value.type.fields.joinToString("/") { f ->
        value.values[f.name]?.let { plainNumber(it, ROW_DECIMALS) } ?: "?"
    }
    return "$numbers ${value.type.fields.first().unit}"
}

private fun issueText(value: MeasurementValue, issue: Issue): String {
    val field = value.type.fields.find { it.name == issue.field }
    return if (field == null || value.type.fields.size == 1) {
        issue.message
    } else {
        "${fieldLabel(value, field)}: ${issue.message}"
    }
}

@Composable
private fun MeasurementRow(
    value: MeasurementValue,
    problem: String?,
    denied: Boolean,
    onUpdate: MeasurementUpdate
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable { editing = !editing }
                .padding(vertical = 12.dp)
        ) {
            Text(value.type.label, modifier = Modifier.weight(1f))
            Text(valueText(value), style = MaterialTheme.typography.bodyLarge)
        }
        if (!denied) {
            value.issues().forEach {
                Text(
                    issueText(value, it),
                    color = error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            problem?.let { Text(it, color = error, style = MaterialTheme.typography.bodySmall) }
        }
        value.warnings().forEach {
            Text(
                it,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (editing) {
            ValueEditor(value, onUpdate, onDone = { editing = false })
        }
    }
}

@Composable
private fun ValueEditor(value: MeasurementValue, onUpdate: MeasurementUpdate, onDone: () -> Unit) {
    Column(
        Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        value.type.fields.forEach { field -> ValueField(value, field, onUpdate) }
        val deleteDescription = stringResource(R.string.value_delete_description, value.type.label)
        val doneDescription = stringResource(R.string.value_done_description, value.type.label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onUpdate { it.removeValue(value.type.key) } },
                modifier = Modifier.semantics { contentDescription = deleteDescription }
            ) {
                Text(stringResource(R.string.value_delete))
            }
            Button(
                onClick = onDone,
                modifier = Modifier.semantics { contentDescription = doneDescription }
            ) { Text(stringResource(R.string.editor_done)) }
        }
    }
}

@Composable
private fun ValueField(
    value: MeasurementValue,
    field: MeasurementField,
    onUpdate: MeasurementUpdate
) {
    var text by rememberSaveable(value.type.key, field.name) {
        mutableStateOf(value.values[field.name]?.let { plainNumber(it) }.orEmpty())
    }
    val reason = value.invalid[field.name]
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val number = if (input.isBlank()) null else parseNumber(input) ?: Double.NaN
            onUpdate { it.withValue(value.type.key, field.name, number) }
        },
        label = { Text("${fieldLabel(value, field)} (${field.unit})") },
        isError = reason != null,
        supportingText = reason?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
