package dev.playday3008.llmhealthbridge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

import java.time.Instant

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.Section
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.MeasurementReview
import dev.playday3008.llmhealthbridge.review.ReplyReview
import dev.playday3008.llmhealthbridge.review.SavedLookup
import dev.playday3008.llmhealthbridge.review.allowedRemovals
import dev.playday3008.llmhealthbridge.review.canSave
import dev.playday3008.llmhealthbridge.review.recordCount
import dev.playday3008.llmhealthbridge.review.toggleLeftover
import dev.playday3008.llmhealthbridge.review.writeProblems

/**
 * Review of every entry in [review], grouped by section, with the save button. [saveBlock]
 * explains a failed save.
 */
@Composable
fun ReviewScreen(
    review: ReplyReview,
    saving: Boolean,
    saveBlock: SaveBlock?,
    actions: ReviewActions,
    contentPadding: PaddingValues
) {
    val now = Instant.now()
    val problems = review.writeProblems(now)
    val canSave = review.canSave(now) && problems.isEmpty() && !saving

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (review.reopened) item("reopened") { ReopenedNote() }
            if (review.warnings.isNotEmpty()) {
                item("warnings") { WarningsSection(review.warnings) }
            }
            Section.entries.forEach { section ->
                val entries = review.entries.filter { it.id.type.section == section }
                if (entries.isNotEmpty()) {
                    item("section-${section.name}") { SectionTitle(sectionLabel(section)) }
                    items(entries, key = { entryKey(it.id) }) { entry ->
                        val entryProblems = problems[entry.id].orEmpty()
                        when (entry) {
                            is MealReview -> MealBlock(entry, review, entryProblems, now, actions)
                            is MeasurementReview ->
                                MeasurementBlock(entry, review, entryProblems, now, actions)
                        }
                    }
                }
            }
            if (review.leftovers.isNotEmpty()) {
                item("leftovers") { LeftoversSection(review, actions) }
            }
        }
        BottomBar(
            review.recordCount(),
            review.allowedRemovals().size,
            canSave,
            review.lookup,
            saveBlock,
            actions
        )
    }
}

private fun entryKey(id: GroupId) = "${id.type.jsonName}/${id.request}/${id.entry}"

/** String resource naming [section]. */
@StringRes
internal fun sectionLabel(section: Section): Int = when (section) {
    Section.NUTRITION -> R.string.section_nutrition
    Section.BODY -> R.string.section_body
    Section.VITALS -> R.string.section_vitals
}

@Composable
private fun ReopenedNote() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.edit_meal), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.review_reopened))
    }
}

@Composable
private fun SectionTitle(@StringRes label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * Saved entries of the reply's request that the reply leaves out, each with a checkbox that
 * deletes it on save.
 */
@Composable
private fun LeftoversSection(review: ReplyReview, actions: ReviewActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(R.string.leftovers_title)
        Text(stringResource(R.string.leftovers_hint), style = MaterialTheme.typography.bodySmall)
        review.leftovers.forEach { leftover ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = leftover.remove,
                    role = Role.Checkbox,
                    onValueChange = {
                        actions.onUpdate { r -> r.toggleLeftover(leftover.group.id) }
                    }
                )
            ) {
                Checkbox(
                    checked = leftover.remove,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    stringResource(
                        R.string.leftover_delete,
                        groupSummary(leftover.group),
                        groupTime(leftover.group)
                    )
                )
            }
        }
    }
}

/** Informational notes that never block saving. */
@Composable
internal fun WarningsSection(warnings: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.warnings), style = MaterialTheme.typography.titleSmall)
        warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Tells that saving the entry replaces the already saved [group] with the same identity, or, when
 * the entry is [emptied], deletes it.
 */
@Composable
internal fun ReplacesNote(group: Group, emptied: Boolean) {
    Text(
        stringResource(
            if (emptied) R.string.replaces_with_nothing else R.string.replaces_saved,
            groupSummary(group)
        ),
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * Names the types of an entry whose write permission was refused ([labels]), with where to allow
 * them and a button that opens Health Connect.
 */
@Composable
internal fun DeniedNote(labels: List<String>, onOpenSettings: () -> Unit) {
    val names = labels.joinToString(", ")
    Column {
        Text(
            stringResource(R.string.not_allowed, names),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.denied_where, names),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_hc_settings))
        }
    }
}

@Composable
private fun BottomBar(
    count: Int,
    deletions: Int,
    canSave: Boolean,
    lookup: SavedLookup,
    block: SaveBlock?,
    actions: ReviewActions
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (lookup) {
            SavedLookup.PENDING ->
                Text(
                    stringResource(R.string.lookup_pending),
                    style = MaterialTheme.typography.bodySmall
                )
            SavedLookup.FAILED ->
                Text(
                    stringResource(R.string.lookup_failed),
                    color = MaterialTheme.colorScheme.error
                )
            SavedLookup.DONE -> Unit
        }
        when (block) {
            SaveBlock.PermissionDenied ->
                Text(stringResource(R.string.save_denied), color = MaterialTheme.colorScheme.error)
            SaveBlock.NeedsInstall -> BlockMessage(
                R.string.save_needs_install,
                R.string.install_hc,
                actions.onInstall
            )
            SaveBlock.Unavailable ->
                Text(
                    stringResource(R.string.save_unavailable),
                    color = MaterialTheme.colorScheme.error
                )
            null -> Unit
        }
        Button(onClick = actions.onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (count == 0 && deletions > 0) {
                    pluralStringResource(R.plurals.delete_button, deletions, deletions)
                } else {
                    pluralStringResource(R.plurals.save_button, count, count)
                }
            )
        }
    }
}

@Composable
private fun BlockMessage(message: Int, button: Int, onClick: () -> Unit) {
    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = onClick) { Text(stringResource(button)) }
}
