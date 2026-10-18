package dev.playday3008.llmhealthbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

import java.util.Locale

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.Nutrient
import dev.playday3008.llmhealthbridge.nutrition.Nutrients
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.withName
import dev.playday3008.llmhealthbridge.review.withNutrient
import dev.playday3008.llmhealthbridge.review.withPortion

private const val PERCENT = 100.0

private data class Preset(val label: String, val factor: Double)

private val PRESETS = listOf(
    Preset("1/2", 0.5),
    Preset("3/4", 0.75),
    Preset("1x", 1.0),
    Preset("1 1/4", 1.25),
    Preset("1 1/2", 1.5)
)

/** Formats [value] with at most two decimals and no trailing zeros. */
internal fun formatNumber(value: Double): String {
    val text = String.format(Locale.ROOT, "%.2f", value)
    return text.trimEnd('0').trimEnd('.')
}

/**
 * Parses user text as a number, accepting a decimal comma; null when it is not a number or has
 * more than one '.' or ',' separator.
 */
internal fun parseNumber(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.count { it == '.' || it == ',' } > 1) return null
    return trimmed.replace(',', '.').toDoubleOrNull()
}

/** Bottom sheet content for editing [item]; every change goes through [onUpdate]. */
@Composable
fun ItemEditor(
    item: MealItem,
    onUpdate: ((MealReview) -> MealReview) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    var showNutrients by rememberSaveable(item.key) { mutableStateOf(false) }

    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(item.name, style = MaterialTheme.typography.titleLarge)
        PortionControls(item, onUpdate)
        Text(liveLine(item), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { showNutrients = !showNutrients }) {
            val label = if (showNutrients) {
                R.string.editor_hide_nutrients
            } else {
                R.string.editor_edit_nutrients
            }
            Text(stringResource(label))
        }
        if (showNutrients) NutrientFields(item, onUpdate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.editor_delete)) }
            Button(onClick = onDone) { Text(stringResource(R.string.editor_done)) }
        }
    }
}

@Composable
private fun PortionControls(item: MealItem, onUpdate: ((MealReview) -> MealReview) -> Unit) {
    var percentText by rememberSaveable(item.key) {
        mutableStateOf(formatNumber(item.portion * PERCENT))
    }
    var percentInvalid by rememberSaveable(item.key) { mutableStateOf(false) }

    Text(stringResource(R.string.editor_portion), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.forEach { preset ->
            FilterChip(
                selected = item.portion == preset.factor,
                onClick = {
                    percentText = formatNumber(preset.factor * PERCENT)
                    percentInvalid = false
                    onUpdate { it.withPortion(item.key, preset.factor) }
                },
                label = { Text(preset.label) }
            )
        }
    }
    OutlinedTextField(
        value = percentText,
        onValueChange = { text ->
            percentText = text
            val value = parseNumber(text)
            percentInvalid = value == null || !value.isFinite() || value <= 0.0
            if (!percentInvalid && value != null) {
                onUpdate { it.withPortion(item.key, value / PERCENT) }
            }
        },
        label = { Text(stringResource(R.string.editor_percent)) },
        isError = percentInvalid,
        supportingText = if (percentInvalid) {
            { Text(stringResource(R.string.editor_percent_invalid)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun liveLine(item: MealItem): String {
    fun grams(key: String) = formatNumber(item.nutrients[key] ?: 0.0)
    return stringResource(
        R.string.editor_live_line,
        (item.nutrients[Nutrients.ENERGY] ?: 0.0).roundToInt(),
        grams(Nutrients.PROTEIN),
        grams(Nutrients.CARBS),
        grams(Nutrients.FAT)
    )
}

@Composable
private fun NutrientFields(item: MealItem, onUpdate: ((MealReview) -> MealReview) -> Unit) {
    OutlinedTextField(
        value = item.name,
        onValueChange = { name -> onUpdate { it.withName(item.key, name) } },
        label = { Text(stringResource(R.string.editor_name)) },
        isError = item.name.isBlank(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Nutrients.all.forEach { nutrient -> NutrientField(item, nutrient, onUpdate) }
}

@Composable
private fun NutrientField(
    item: MealItem,
    nutrient: Nutrient,
    onUpdate: ((MealReview) -> MealReview) -> Unit
) {
    var text by rememberSaveable(item.key, nutrient.key, item.portion) {
        mutableStateOf(item.nutrients[nutrient.key]?.let(::formatNumber).orEmpty())
    }
    var unparseable by rememberSaveable(item.key, nutrient.key, item.portion) {
        mutableStateOf(false)
    }
    val reason = item.invalid[nutrient.key]
    val interpreted = if (',' in text) parseNumber(text) else null
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) {
                unparseable = false
                onUpdate { it.withNutrient(item.key, nutrient.key, null) }
            } else {
                val value = parseNumber(input)
                unparseable = value == null
                onUpdate { it.withNutrient(item.key, nutrient.key, value ?: Double.NaN) }
            }
        },
        label = { Text("${nutrient.label} (${nutrient.unit.suffix})") },
        isError = unparseable || reason != null,
        supportingText = when {
            unparseable -> {
                { Text(stringResource(R.string.editor_number_invalid)) }
            }
            reason != null -> {
                { Text(reason) }
            }
            interpreted != null -> {
                {
                    Text(
                        stringResource(
                            R.string.editor_read_as,
                            formatNumber(interpreted),
                            nutrient.unit.suffix
                        )
                    )
                }
            }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
