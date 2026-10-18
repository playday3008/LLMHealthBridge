package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.measurement.INTERVAL_LENGTH
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.measurement.recordStart

private fun buildRecord(
    entry: MeasurementEntry,
    value: MeasurementValue,
    now: Instant,
    version: Long
): Record {
    val type = value.type
    val latest = if (type.interval) now.minus(INTERVAL_LENGTH) else now
    val start = minOf(entry.time.toInstant(), latest)
    val metadata = Metadata.manualEntry(
        clientRecordId = RecordIds.clientRecordId(entry.id, type.key),
        clientRecordVersion = version
    )
    return type.build(value.values, start, entry.time.offset, metadata)
}

/**
 * Builds one record per value whose type has its write permission in [allowed]. Interval types
 * start at most one minute before [now], the others at most at [now], so Health Connect never
 * sees a record in the future. Throws [IllegalArgumentException] if a value is out of range.
 */
fun measurementRecords(
    entry: MeasurementEntry,
    now: Instant,
    version: Long,
    allowed: Set<String>
): List<Record> = entry.values.filter { it.type.writePermission in allowed }
    .map { buildRecord(entry, it, now, version) }

/** Maps type key to the validation message for each value whose record cannot be built. */
fun measurementProblems(entry: MeasurementEntry, now: Instant): Map<String, String> = buildMap {
    entry.values.filter { v -> v.type.fields.all { it.name in v.values } }.forEach { value ->
        try {
            buildRecord(entry, value, now, 0)
        } catch (e: IllegalArgumentException) {
            put(value.type.key, e.message.orEmpty())
        }
    }
}

/**
 * Reassembles entries from records written by this app. Records with foreign client IDs are
 * ignored. Newest entry first.
 */
fun measurementEntriesFromRecords(records: List<Record>): List<MeasurementEntry> {
    val parsed = records.mapNotNull { r ->
        val (group, key) = r.metadata.clientRecordId?.let(RecordIds::parse)
            ?: return@mapNotNull null
        val type = MeasurementTypes.byKey(key)?.takeIf {
            it.entryType == group.type &&
                it.recordClass == r::class
        }
        type?.let { Triple(group, it, r) }
    }
    return parsed.groupBy { it.first }.map { (id, members) ->
        val ordered = members.sortedBy { MeasurementTypes.all.indexOf(it.second) }
        val (start, offset) = members.map { recordStart(it.third) }.minBy { it.first }
        MeasurementEntry(
            id = id,
            time = OffsetDateTime.ofInstant(start, offset ?: ZoneOffset.UTC),
            values = ordered.map { (_, type, r) ->
                MeasurementValue(type, type.read(r).mapValues { roundRead(it.value) }, emptyMap())
            },
            warnings = emptyList()
        )
    }.sortedByDescending { it.time.toInstant() }
}

/**
 * [value] read back from a record, rounded to [READ_DECIMALS] places: unit conversions such as
 * centimetres to metres and back leave float noise (51.3 becomes 51.300000000000004).
 */
private fun roundRead(value: Double): Double =
    BigDecimal.valueOf(value).setScale(READ_DECIMALS, RoundingMode.HALF_UP).toDouble()

private const val READ_DECIMALS = 6
