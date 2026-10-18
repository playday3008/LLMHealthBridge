package dev.playday3008.llmhealthbridge.measurement

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata

import kotlin.reflect.KClass

import java.time.Instant
import java.time.ZoneOffset

import dev.playday3008.llmhealthbridge.entry.EntryType

/**
 * One numeric value of a [MeasurementType].
 * [unit] is the display unit of the canonical value; [altKeys] map an alternative JSON key to a converter
 * from that key's unit into the canonical unit.
 */
class MeasurementField(
    val name: String,
    val unit: String,
    val jsonKey: String,
    val altKeys: Map<String, (Double) -> Double>,
    val plausible: ClosedFloatingPointRange<Double>,
    val whole: Boolean = false
)

/** A Health Connect record type this app can log, with the mapping between canonical values and the record. */
class MeasurementType(
    val key: String,
    val entryType: EntryType,
    val label: String,
    val fields: List<MeasurementField>,
    val recordClass: KClass<out Record>,
    val build: (
        values: Map<String, Double>,
        start: Instant,
        offset: ZoneOffset,
        metadata: Metadata
    ) -> Record,
    val read: (Record) -> Map<String, Double>,
    val startOf: (Record) -> Pair<Instant, ZoneOffset?>,
    val interval: Boolean = false
) {
    val writePermission: String = HealthPermission.getWritePermission(recordClass)
}

/** Start time and zone offset of a record of any registered type. */
fun recordStart(record: Record): Pair<Instant, ZoneOffset?> {
    val type =
        requireNotNull(MeasurementTypes.all.firstOrNull { it.recordClass == record::class }) {
            "Unregistered record ${record::class}"
        }
    return type.startOf(record)
}
