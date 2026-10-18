package dev.playday3008.llmhealthbridge.ui

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.Section

/** [groups] split by section in home order, newest first; every section is present. */
internal fun groupsBySection(groups: List<Group>): Map<Section, List<Group>> {
    val newestFirst = groups.sortedByDescending { it.time.toInstant() }
    return Section.entries.associateWith { section ->
        newestFirst.filter { it.id.type.section == section }
    }
}

/** Summary of the newest of [groups], or null when there are none. */
internal fun newestSummary(groups: List<Group>): String? =
    groups.maxByOrNull { it.time.toInstant() }?.let(::groupSummary)

/** List key for the group [id]; unique across entry types. */
internal fun recentKey(id: GroupId): String = "${id.type.jsonName}/${id.request}/${id.entry}"

/** The section open after [tapped] is tapped while [open] is open; tapping the open one closes it. */
internal fun toggleSection(open: Section?, tapped: Section): Section? =
    if (open == tapped) null else tapped
