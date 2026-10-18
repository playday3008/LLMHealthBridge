package dev.playday3008.llmhealthbridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

import kotlin.math.roundToInt

import java.util.Locale

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.Section
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

/**
 * Home: the clipboard banner, LLM app chips, send and paste actions, and recent entries by
 * section, of which only [openSection] is expanded.
 */
@Composable
fun HomeScreen(
    ui: HomeUi,
    openSection: Section?,
    actions: HomeActions,
    contentPadding: PaddingValues
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ui.banner?.let { item { BannerCard(it, actions) } }
        item { SendCard(ui, actions) }
        item { ReturnCard(actions) }
        item {
            Text(
                stringResource(R.string.recent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        when (val recent = ui.recent) {
            RecentGroups.Loading -> item { Text(stringResource(R.string.recent_loading)) }
            is RecentGroups.Failed -> item {
                Column {
                    Text(recent.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.onRetryRecent) {
                        Text(stringResource(R.string.recent_retry))
                    }
                }
            }
            RecentGroups.NothingSaved ->
                item { Text(stringResource(R.string.recent_nothing_saved)) }
            is RecentGroups.Loaded -> groupsBySection(recent.groups).forEach { (section, groups) ->
                val isOpen = openSection == section
                item(key = "section/${section.name}") {
                    SectionHeader(section, groups, isOpen) { actions.onToggleSection(section) }
                }
                if (isOpen) {
                    if (groups.isEmpty()) {
                        item(key = "empty/${section.name}") {
                            Text(
                                stringResource(R.string.recent_empty),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                    items(groups, key = { recentKey(it.id) }) { GroupRow(it, actions) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: Section,
    groups: List<Group>,
    isOpen: Boolean,
    onToggle: () -> Unit
) {
    val newest = newestSummary(groups) ?: stringResource(R.string.recent_empty)
    ListItem(
        headlineContent = {
            Text(stringResource(sectionLabel(section)), style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = if (isOpen) null else ({ Text(newest) }),
        trailingContent = {
            Text(stringResource(if (isOpen) R.string.recent_collapse else R.string.recent_expand))
        },
        modifier = Modifier.clickable(onClick = onToggle)
    )
}

@Composable
private fun BannerCard(banner: Banner, actions: HomeActions) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.banner_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(banner.text)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = actions.onDismissBanner) {
                    Text(stringResource(R.string.banner_dismiss))
                }
                Button(onClick = actions.onOpenBanner) {
                    Text(stringResource(R.string.banner_review))
                }
            }
        }
    }
}

@Composable
private fun SendCard(ui: HomeUi, actions: HomeActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.card_send_title),
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.apps.forEach { app ->
                    FilterChip(
                        selected = ui.selected == app,
                        onClick = { actions.onSelectApp(app) },
                        label = { Text(app.label) }
                    )
                }
                FilterChip(
                    selected = ui.selected == null,
                    onClick = { actions.onSelectApp(null) },
                    label = { Text(stringResource(R.string.chip_other)) }
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.onTakePhoto, enabled = !ui.sending) {
                    Text(stringResource(R.string.take_photo))
                }
                FilledTonalButton(onClick = actions.onPickPhotos, enabled = !ui.sending) {
                    Text(stringResource(R.string.pick_photos))
                }
                FilledTonalButton(onClick = actions.onDescribe, enabled = !ui.sending) {
                    Text(stringResource(R.string.describe))
                }
            }
        }
    }
}

@Composable
private fun ReturnCard(actions: HomeActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.card_return_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(stringResource(R.string.return_guidance))
            OutlinedButton(onClick = actions.onPaste) { Text(stringResource(R.string.paste)) }
        }
    }
}

@Composable
private fun mealSummary(meal: Meal, time: String): String {
    val kcal = meal.items.sumOf { it.nutrients[Nutrients.ENERGY] ?: 0.0 }.roundToInt()
    val count = meal.items.size
    return pluralStringResource(R.plurals.recent_row_summary, count, time, count, kcal)
}

@Composable
private fun GroupRow(group: Group, actions: HomeActions) {
    val time = groupTime(group)
    val moreOptions = stringResource(R.string.recent_more, rowHeadline(group), time)
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(rowHeadline(group)) },
        supportingContent = {
            if (group is Meal) {
                Text(mealSummary(group, time))
            } else {
                Text(time)
            }
        },
        trailingContent = {
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(
                        "...",
                        modifier = Modifier.semantics {
                            contentDescription = moreOptions
                        }
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_entry)) },
                        onClick = {
                            menu = false
                            confirm = true
                        }
                    )
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { actions.onOpenGroup(group) },
            onLongClick = { menu = true }
        )
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.onDeleteGroup(group)
                }) { Text(stringResource(R.string.delete_entry)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.delete_cancel))
                }
            }
        )
    }
}

private fun rowHeadline(group: Group): String = if (group is Meal) {
    group.mealType.jsonName.replaceFirstChar { it.titlecase(Locale.ROOT) }
} else {
    groupSummary(group)
}
