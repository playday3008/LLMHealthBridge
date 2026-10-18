package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record

import kotlin.reflect.KClass

import java.time.Instant

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes

/** Record classes an entry of [type] is written as. */
fun recordClasses(type: EntryType): Set<KClass<out Record>> = when (type) {
    EntryType.NUTRITION -> setOf(NutritionRecord::class)
    else -> MeasurementTypes.forEntryType(type).map { it.recordClass }.toSet()
}

/** Health Connect write permissions needed to save entries of [type]. */
fun writePermissions(type: EntryType): Set<String> =
    recordClasses(type).map { HealthPermission.getWritePermission(it) }.toSet()

/** Record classes of [type] whose write permission is in [granted]. */
fun grantedClasses(type: EntryType, granted: Set<String>): Set<KClass<out Record>> =
    recordClasses(type).filter { HealthPermission.getWritePermission(it) in granted }.toSet()

/** Every write permission this app can request. */
val ALL_WRITE_PERMISSIONS: Set<String> = EntryType.entries.flatMap { writePermissions(it) }.toSet()

/** A record version above every one in [existing] and not below the clock reading [now]. */
fun nextVersion(existing: List<Record>, now: Instant): Long {
    val maxVersion = existing.maxOfOrNull { it.metadata.clientRecordVersion } ?: Long.MIN_VALUE
    return maxOf(now.toEpochMilli(), maxVersion + 1)
}

/**
 * Client record IDs per record class of the records in [existing] that belong to group [id], are
 * of a class in [allowedClasses] and whose record key is not in [keep]. Classes without stale
 * records are absent; records with foreign or old IDs are never stale.
 */
fun staleClientIds(
    existing: List<Record>,
    id: GroupId,
    keep: Set<String>,
    allowedClasses: Set<KClass<out Record>>
): Map<KClass<out Record>, List<String>> = existing
    .filter { it::class in allowedClasses }
    .mapNotNull { r ->
        val clientId = r.metadata.clientRecordId ?: return@mapNotNull null
        val (group, key) = RecordIds.parse(clientId) ?: return@mapNotNull null
        if (group == id && key !in keep) r::class to clientId else null
    }
    .groupBy({ it.first }, { it.second })

/** Meals and measurement entries in [records], newest first. */
fun groupsFromRecords(records: List<Record>): List<Group> = (
    mealsFromRecords(records.filterIsInstance<NutritionRecord>()) +
        measurementEntriesFromRecords(records.filter { it !is NutritionRecord })
    )
    .sortedByDescending { it.time.toInstant() }

/** The groups in [records] logged for [request], newest first. */
fun requestGroups(request: String, records: List<Record>): List<Group> =
    groupsFromRecords(records).filter { it.id.request == request }
