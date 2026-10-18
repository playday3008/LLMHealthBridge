package dev.playday3008.llmhealthbridge.review

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record

import kotlin.reflect.KClass

import kotlinx.coroutines.CancellationException

import java.time.Instant

import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.healthconnect.GroupSave
import dev.playday3008.llmhealthbridge.healthconnect.buildRecords
import dev.playday3008.llmhealthbridge.healthconnect.measurementProblems
import dev.playday3008.llmhealthbridge.healthconnect.measurementRecords
import dev.playday3008.llmhealthbridge.healthconnect.recordClasses
import dev.playday3008.llmhealthbridge.healthconnect.recordProblems

/**
 * One group save: [allowedClasses] are the record classes of the group's type that may be
 * written and [build] makes the records for a version.
 */
class GroupWrite(
    val id: GroupId,
    val allowedClasses: Set<KClass<out Record>>,
    val build: (version: Long) -> List<Record>
)

/**
 * Result of saving several groups: [saved] records of the [written] groups were written before
 * [failure], if any, and removing replaced records failed for [cleanupFailures] of them.
 */
class SaveOutcome(
    val saved: Int,
    val failure: Exception?,
    val cleanupFailures: Int = 0,
    val written: List<GroupId> = emptyList()
)

/**
 * Runs [save] for each of [writes] in order, summing the records it reports, and stops at the
 * first failure. Groups saved before it stay saved; saving them again replaces them.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun saveAll(
    writes: List<GroupWrite>,
    save: suspend (GroupWrite) -> GroupSave
): SaveOutcome {
    var saved = 0
    var cleanupFailures = 0
    val written = mutableListOf<GroupId>()
    for (write in writes) {
        try {
            val result = save(write)
            saved += result.inserted
            written += write.id
            if (result.cleanupFailure != null) cleanupFailures++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveOutcome(saved, e, cleanupFailures, written)
        }
    }
    return SaveOutcome(saved, null, cleanupFailures, written)
}

/**
 * The group saves for every entry with at least one record whose write permission is in
 * [granted]. Entries with nothing writable are left out, so a denied meal is not touched.
 */
fun ReplyReview.writes(granted: Set<String>, now: Instant): List<GroupWrite> =
    entries.mapNotNull { e ->
        val allowed = recordClasses(e.id.type)
            .filter { HealthPermission.getWritePermission(it) in granted }
            .toSet()
        when (e) {
            is MealReview -> {
                val items = e.selectedItems()
                if (items.isEmpty() || allowed.isEmpty()) {
                    null
                } else {
                    GroupWrite(e.id, allowed) { version ->
                        buildRecords(e.meal, items, now, version)
                    }
                }
            }
            is MeasurementReview ->
                if (e.entry.values.none { it.type.writePermission in granted }) {
                    null
                } else {
                    GroupWrite(e.id, allowed) { version ->
                        measurementRecords(e.entry, now, version, granted)
                    }
                }
        }
    }

/**
 * Record build problems of rows that would be written (ticked items, values of types not
 * denied), by entry and then by item key or measurement type key. Entries without problems are
 * absent.
 */
fun ReplyReview.writeProblems(now: Instant): Map<GroupId, Map<String, String>> =
    entries.associate { e ->
        e.id to when (e) {
            is MealReview ->
                if (isDenied(writePermissionOf(e))) {
                    emptyMap()
                } else {
                    recordProblems(e.meal, e.selectedItems(), now)
                }
            is MeasurementReview -> measurementProblems(e.entry, now).filterKeys { key ->
                e.entry.values.any { it.type.key == key && !isDenied(it.type.writePermission) }
            }
        }
    }.filterValues { it.isNotEmpty() }

private fun writePermissionOf(meal: MealReview): String =
    HealthPermission.getWritePermission(recordClasses(meal.id.type).single())
