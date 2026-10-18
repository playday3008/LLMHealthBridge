package dev.playday3008.llmhealthbridge.healthconnect

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.WeightRecord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryParse
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.measurement.parseMeasurementEntry

private val NOW = Instant.parse("2026-10-18T12:00:00Z")
private val ZONE = ZoneId.of("Europe/Warsaw")
private const val REQUEST = "mvdrjvpc-7f2a"

private fun entry(json: String, type: EntryType): MeasurementEntry = (
    parseMeasurementEntry(
        Json.parseToJsonElement(json).jsonObject,
        GroupId(type, REQUEST, "e1"),
        NOW,
        ZONE
    )
        as EntryParse.Valid
    ).group as MeasurementEntry

private val ALL_PERMISSIONS = MeasurementTypes.all.map { it.writePermission }.toSet()

class MeasurementRecordsTest {
    private val body = entry(
        """{"time":"2026-10-18T07:12:00+02:00","weight_kg":82.4,"body_fat_percent":18.6}""",
        EntryType.BODY
    )

    @Test fun oneRecordPerValueWithStableIds() {
        val ids = measurementRecords(body, NOW, 1, ALL_PERMISSIONS).map {
            it.metadata.clientRecordId
        }
        val group = "llmhb3/body/$REQUEST/e1"
        assertEquals(listOf("$group/weight", "$group/body_fat"), ids)
    }

    @Test fun deniedTypesAreNotBuilt() {
        val only = setOf(MeasurementTypes.byKey("weight")!!.writePermission)
        val records = measurementRecords(body, NOW, 1, only)
        assertEquals(1, records.size)
        assertTrue(records.single() is WeightRecord)
    }

    @Test fun intervalTypesEndInThePast() {
        val water =
            entry("""{"time":"2026-10-18T14:00:00+02:00","volume_ml":250}""", EntryType.HYDRATION)
        val record = measurementRecords(water, NOW, 1, ALL_PERMISSIONS).single() as HydrationRecord
        assertTrue(record.endTime <= NOW, "ends at ${record.endTime}")
    }

    @Test fun roundTripRebuildsTheEntry() {
        val back = measurementEntriesFromRecords(
            measurementRecords(body, NOW, 1, ALL_PERMISSIONS)
        ).single()
        assertEquals(body.id, back.id)
        assertEquals(body.time.toInstant(), back.time.toInstant())
        assertEquals(body.values.map { it.type.key }, back.values.map { it.type.key })
        body.values.zip(back.values).forEach { (a, b) ->
            a.values.forEach { (name, v) -> assertEquals(v, b.values.getValue(name), 1e-6) }
        }
    }

    // 51.3 cm is stored as 0.513 m, and 0.513 * 100 is 51.300000000000004 in doubles.
    @Test fun convertedValuesReadBackWithoutFloatNoise() {
        val height =
            entry("""{"time":"2026-10-18T07:12:00+02:00","height_cm":51.3}""", EntryType.BODY)
        val back =
            measurementEntriesFromRecords(measurementRecords(height, NOW, 1, ALL_PERMISSIONS))
        assertEquals(51.3, back.single().values.single().values.getValue("height"))
    }

    // A value the app treats as plausible must also be one Health Connect accepts.
    @Test fun plausibleRangesFitHealthConnect() {
        val rejected = MeasurementTypes.all.flatMap { type ->
            listOf(true, false).mapNotNull { low ->
                val values = type.fields.associate {
                    it.name to if (low) it.plausible.start else it.plausible.endInclusive
                }
                val value = MeasurementValue(type, values, emptyMap())
                val e =
                    MeasurementEntry(
                        GroupId(type.entryType, REQUEST, "e1"),
                        body.time,
                        listOf(value),
                        emptyList()
                    )
                measurementProblems(e, NOW)[type.key]?.let {
                    "${type.key} ${if (low) "low" else "high"}: $it"
                }
            }
        }
        assertEquals(emptyList(), rejected)
    }

    @Test fun outOfRangeReportedPerType() {
        val bp = entry("""{"systolic_mmhg":900,"diastolic_mmhg":82}""", EntryType.VITALS)
        val problems = measurementProblems(bp, NOW)
        assertEquals(setOf("blood_pressure"), problems.keys)
        assertTrue(problems.getValue("blood_pressure").isNotBlank())
    }
}
