@file:Suppress("TooManyFunctions")

package dev.playday3008.llmhealthbridge.review

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RequestIds
import dev.playday3008.llmhealthbridge.healthconnect.writePermissions
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.measurement.reject
import dev.playday3008.llmhealthbridge.nutrition.Issue

/** One entry of a reply under review. */
sealed interface ReviewEntry {
    val id: GroupId
}

/** A hydration, body or vitals [entry] under review. */
data class MeasurementReview(val entry: MeasurementEntry) : ReviewEntry {
    override val id: GroupId get() = entry.id
}

/** A saved [group] of the reply's request that the reply does not contain; [remove] deletes it on save. */
data class Leftover(val group: Group, val remove: Boolean)

/** State of the lookup of the groups already saved for the reply's request. */
enum class SavedLookup {
    /** Still running; saving waits, since it decides what saving replaces and removes. */
    PENDING,
    DONE,

    /** Failed; saving writes the entries but cannot remove what the reply left out. */
    FAILED
}

/**
 * Everything parsed from one reply. [entries] are ordered by section, reply order within a
 * section; build with [ReplyReview.of] to get that order. [denied] holds write permissions the
 * user refused; rows of those types are not written. [replaces] holds already saved groups with
 * the id of an entry, which saving that entry replaces. [leftovers] are the request's other saved
 * groups. [lookup] tells whether [replaces] and [leftovers] are known yet. [removed] holds the ids
 * of entries the user removed, whose saved groups are kept unless the user marks them.
 */
data class ReplyReview(
    val entries: List<ReviewEntry>,
    val warnings: List<String>,
    val reopened: Boolean,
    val denied: Set<String> = emptySet(),
    val replaces: Map<GroupId, Group> = emptyMap(),
    val leftovers: List<Leftover> = emptyList(),
    val lookup: SavedLookup = SavedLookup.DONE,
    val removed: Set<GroupId> = emptySet()
) {
    companion object {
        /** A review with [entries] stably sorted by section. */
        fun of(
            entries: List<ReviewEntry>,
            warnings: List<String>,
            reopened: Boolean,
            denied: Set<String> = emptySet(),
            replaces: Map<GroupId, Group> = emptyMap()
        ): ReplyReview = ReplyReview(
            entries.sortedBy {
                sectionOf(it.id.type)
            },
            warnings,
            reopened,
            denied,
            replaces
        )

        private fun sectionOf(type: EntryType) = type.section.ordinal
    }
}

/** Applies [transform], which keeps the entry's id, to the entry with [id]. */
fun ReplyReview.update(id: GroupId, transform: (ReviewEntry) -> ReviewEntry): ReplyReview =
    copy(entries = entries.map { if (it.id == id) transform(it) else it })

/**
 * Drops the entry with [id]. A saved group it would have replaced stays saved and is listed with
 * the leftovers, unmarked, so it can still be deleted.
 */
fun ReplyReview.remove(id: GroupId): ReplyReview {
    val kept = listOfNotNull(replaces[id]).map { Leftover(it, remove = false) }
    return copy(
        entries = entries.filter { it.id != id },
        replaces = replaces - id,
        leftovers = newestFirst(leftovers + kept),
        removed = removed + id
    )
}

private fun MeasurementValue.copyField(field: String, value: Double?): MeasurementValue {
    val def = type.fields.firstOrNull { it.name == field } ?: return this
    val reason = def.reject(value)
    return if (reason == null && value != null) {
        copy(values = values + (field to value), invalid = invalid - field)
    } else {
        copy(values = values - field, invalid = invalid + (field to checkNotNull(reason)))
    }
}

/**
 * Sets [field] of the measurement [typeKey]. A finite value >= 0 (whole where the field requires
 * it) is stored; anything else marks the field invalid with the parser's message.
 */
fun MeasurementReview.withValue(typeKey: String, field: String, value: Double?): MeasurementReview =
    copy(
        entry = entry.copy(
            values = entry.values.map {
                if (it.type.key ==
                    typeKey
                ) {
                    it.copyField(field, value)
                } else {
                    it
                }
            }
        )
    )

/** Drops the measurement [typeKey] from the entry. */
fun MeasurementReview.removeValue(typeKey: String): MeasurementReview =
    copy(entry = entry.copy(values = entry.values.filter { it.type.key != typeKey }))

/** Replaces the entry time. */
fun MeasurementReview.withTime(time: OffsetDateTime): MeasurementReview =
    copy(entry = entry.copy(time = time))

/** True when nothing of this entry is left to write: every item unticked, or every value deleted. */
fun ReviewEntry.isEmptied(): Boolean = when (this) {
    is MealReview -> selectedItems().isEmpty()
    is MeasurementReview -> entry.values.isEmpty()
}

private fun MeasurementReview.writtenValues(denied: Set<String>): List<MeasurementValue> =
    entry.values.filter { it.type.writePermission !in denied }

private fun ReplyReview.isWritten(e: ReviewEntry): Boolean = when (e) {
    is MealReview -> !e.isEmptied() && !isDenied(NUTRITION_PERMISSION)
    is MeasurementReview -> e.writtenValues(denied).isNotEmpty()
}

private val NUTRITION_PERMISSION: String = writePermissions(EntryType.NUTRITION).single()

/**
 * Write permissions of every record type that would be written, and of the record types the
 * leftovers to remove hold, ignoring [ReplyReview.denied].
 */
fun ReplyReview.requiredPermissions(): Set<String> = entries.filterNot { it.isEmptied() }
    .flatMap { e ->
        when (e) {
            is MealReview -> listOf(NUTRITION_PERMISSION)
            is MeasurementReview -> e.entry.values.map { it.type.writePermission }
        }
    }
    .toSet() + removals().flatMap { heldPermissions(it) }

private fun heldPermissions(group: Group): List<String> = when (group) {
    is MeasurementEntry -> group.values.map { it.type.writePermission }
    else -> listOf(NUTRITION_PERMISSION)
}

/** Required write permissions that are neither in [granted] nor refused yet; these must be requested. */
fun ReplyReview.missingPermissions(granted: Set<String>): Set<String> =
    requiredPermissions() - granted - denied

/** Marks [permissions] as refused, in addition to those already denied. */
fun ReplyReview.withDenied(permissions: Set<String>): ReplyReview = copy(
    denied =
    denied + permissions
)

/**
 * Takes in [saved], the groups already saved for the reply's request, and ends the lookup. Those
 * with the id of an entry are replaced by saving it; the others become leftovers. Leftovers are
 * marked for removal when the reply repeats an entry id the LLM gave a saved group: then it is a
 * resend of the full list, as the instructions ask, so a missing entry was removed on purpose. A
 * reply with only new ids is an addition, and its leftovers are kept unless the user marks them.
 * Leftovers already listed keep the user's marks, and groups of entries the user removed are never
 * marked, so a lookup that ends late or runs again does not undo the user's choices.
 */
fun ReplyReview.withSaved(saved: List<Group>): ReplyReview {
    val ids = entries.map { it.id }.toSet()
    val (replaced, others) = saved.partition { it.id in ids }
    val entryIds = ids.map { it.entry }.filterNot(RequestIds::isFallbackEntryId).toSet()
    val resend = saved.any { it.id.entry in entryIds }
    val marks = leftovers.associate { it.group.id to it.remove }
    return copy(
        replaces = replaced.associateBy { it.id },
        leftovers = newestFirst(
            others.map { Leftover(it, remove = marks[it.id] ?: (resend && it.id !in removed)) }
        ),
        lookup = SavedLookup.DONE
    )
}

private fun newestFirst(leftovers: List<Leftover>) = leftovers.sortedByDescending {
    it.group.time.toInstant()
}

/** Flips whether the leftover with [id] is removed on save. */
fun ReplyReview.toggleLeftover(id: GroupId): ReplyReview = copy(
    leftovers = leftovers.map { if (it.group.id == id) it.copy(remove = !it.remove) else it }
)

/**
 * Saved groups to delete when saving: the leftovers marked for removal, and the saved groups of
 * entries emptied in a fresh review, which emptying replaces with nothing, as unticking one item
 * deletes that item's record.
 */
fun ReplyReview.removals(): List<Group> {
    val emptied = if (reopened) {
        emptyList()
    } else {
        entries.filter {
            it.isEmptied()
        }.mapNotNull { replaces[it.id] }
    }
    return leftovers.filter { it.remove }.map { it.group } + emptied
}

/** The group a review entry would save, as edited. */
val ReviewEntry.group: Group
    get() = when (this) {
        is MealReview -> meal
        is MeasurementReview -> entry
    }

/**
 * Records that the entries with [ids] were written, so they now replace what they saved: the
 * ticked items of a meal, the values of measurement types not denied.
 */
fun ReplyReview.withWritten(ids: Collection<GroupId>): ReplyReview = copy(
    replaces =
    replaces + entries.filter { it.id in ids }.associate { it.id to writtenGroup(it) }
)

private fun ReplyReview.writtenGroup(e: ReviewEntry): Group = when (e) {
    is MealReview -> e.meal.copy(items = e.selectedItems())
    is MeasurementReview -> e.entry.copy(values = e.writtenValues(denied))
}

/**
 * The [removals] with at least one record type not refused, so deleting them can go ahead through
 * those types.
 */
fun ReplyReview.allowedRemovals(): List<Group> =
    removals().filter { group -> heldPermissions(group).any { it !in denied } }

/** Forgets the [deleted] groups, as leftovers and as groups that emptied entries replace. */
fun ReplyReview.withoutLeftovers(deleted: Collection<Group>): ReplyReview = copy(
    leftovers = leftovers.filter { it.group !in deleted },
    replaces = replaces.filterValues { it !in deleted }
)

/** Removes [granted] from the denied permissions. */
fun ReplyReview.withGranted(granted: Set<String>): ReplyReview = copy(denied = denied - granted)

/** True when [permission] was refused. */
fun ReplyReview.isDenied(permission: String): Boolean = permission in denied

/** Records that would be written: ticked meal items plus measurement values, excluding denied types. */
fun ReplyReview.recordCount(): Int = entries.sumOf { e ->
    when (e) {
        is MealReview -> if (isDenied(NUTRITION_PERMISSION)) 0 else e.selectedItems().size
        is MeasurementReview -> e.writtenValues(denied).size
    }
}

/** Issues of entries that would be written; measurement value issues are prefixed with the type label. */
fun ReplyReview.blockingIssues(now: Instant): List<Issue> =
    entries.filter { isWritten(it) }.flatMap { e ->
        when (e) {
            is MealReview -> e.blockingIssues(now)
            is MeasurementReview -> e.entry.issues(now).filter { it.field == "time" } +
                e.writtenValues(denied).flatMap { v ->
                    v.issues().map { it.copy(field = "${v.type.label}: ${it.field}") }
                }
        }
    }

/**
 * True when something would be written or deleted, nothing blocks and the saved groups are looked
 * up; an emptied entry blocks a reopened review.
 */
fun ReplyReview.canSave(now: Instant): Boolean = lookup != SavedLookup.PENDING &&
    (recordCount() > 0 || allowedRemovals().isNotEmpty()) &&
    blockingIssues(now).isEmpty() &&
    !(reopened && entries.any { it.isEmptied() })
