package dev.playday3008.llmhealthbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

import java.time.Instant

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.healthconnect.writePermissions
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.Nutrients
import dev.playday3008.llmhealthbridge.nutrition.plausibilityWarnings
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.ReplyReview
import dev.playday3008.llmhealthbridge.review.isDenied
import dev.playday3008.llmhealthbridge.review.isEmptied
import dev.playday3008.llmhealthbridge.review.remove
import dev.playday3008.llmhealthbridge.review.toggle
import dev.playday3008.llmhealthbridge.review.update
import dev.playday3008.llmhealthbridge.review.withTime

/**
 * One meal of [review]: header, meal type, totals, items and notes. [problems] maps item keys to
 * record build problems.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealBlock(
    state: MealReview,
    review: ReplyReview,
    problems: Map<String, String>,
    now: Instant,
    actions: ReviewActions
) {
    val onUpdate: ((MealReview) -> MealReview) -> Unit = { transform ->
        actions.onUpdate { r -> r.update(state.id) { transform(it as MealReview) } }
    }
    val denied = writePermissions(state.id.type).any { review.isDenied(it) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EntryHeader(
            label = stringResource(R.string.entry_meal),
            time = state.meal.time,
            timeIssue = state.meal.issues(now).find { it.field == "time" },
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
        if (denied) {
            DeniedNote(listOf(stringResource(R.string.nutrition_label)), actions.onOpenSettings)
        }
        MealFields(state, now, onUpdate)
        if (state.meal.items.isEmpty()) Text(stringResource(R.string.no_items))
        state.meal.items.forEach { item ->
            key(item.key) {
                ItemRow(
                    item = item,
                    ticked = item.key !in state.unticked,
                    problem = problems[item.key],
                    onToggle = { onUpdate { it.toggle(item.key) } },
                    onEdit = { editingKey = item.key }
                )
            }
        }
        if (state.meal.warnings.isNotEmpty()) WarningsSection(state.meal.warnings)
    }

    val editing = state.meal.items.find { it.key == editingKey }
    if (editing != null) {
        ModalBottomSheet(onDismissRequest = { editingKey = null }) {
            ItemEditor(
                item = editing,
                onUpdate = onUpdate,
                onDelete = {
                    editingKey = null
                    onUpdate { it.remove(editing.key) }
                },
                onDone = { editingKey = null }
            )
        }
    }
}

@Composable
private fun ItemRow(
    item: MealItem,
    ticked: Boolean,
    problem: String?,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    val issues = if (ticked) item.issues().map { "${it.field}: ${it.message}" } else emptyList()
    val warnings = plausibilityWarnings(item)
    val name = item.name.ifBlank { "?" }
    val tickDescription = stringResource(R.string.item_tick_description, name)
    val expandDescription =
        stringResource(
            if (expanded) R.string.hide_all_description else R.string.show_all_description,
            name
        )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)
            ) {
                Checkbox(
                    checked = ticked,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.semantics { contentDescription = tickDescription }
                )
                Text(name, modifier = Modifier.weight(1f))
                val kcal = item.nutrients[Nutrients.ENERGY]
                Text(
                    if (kcal == null) {
                        stringResource(R.string.item_no_kcal)
                    } else {
                        stringResource(R.string.item_kcal, kcal.roundToInt())
                    }
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { contentDescription = expandDescription }
                ) {
                    Text(stringResource(if (expanded) R.string.hide_all else R.string.show_all))
                }
            }
            issues.forEach { Text(it, color = error, style = MaterialTheme.typography.bodySmall) }
            if (ticked) {
                problem?.let { Text(it, color = error, style = MaterialTheme.typography.bodySmall) }
            }
            warnings.forEach {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (expanded) NutrientList(item)
        }
    }
}

@Composable
private fun NutrientList(item: MealItem) {
    Column(Modifier.padding(start = 16.dp, bottom = 8.dp)) {
        Nutrients.all.forEach { nutrient ->
            item.nutrients[nutrient.key]?.let { value ->
                Text(
                    "${nutrient.label}: ${formatNumber(value)} ${nutrient.unit.suffix}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
