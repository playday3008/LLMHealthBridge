package dev.playday3008.llmhealthbridge.measurement

import androidx.health.connect.client.records.metadata.Metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

import java.time.Instant
import java.time.ZoneOffset

class MeasurementTypesTest {
    private val start = Instant.parse("2026-10-18T07:12:00Z")
    private val offset = ZoneOffset.ofHours(2)

    @Test fun sixteenTypesWithUniqueKeysAndJsonKeys() {
        assertEquals(16, MeasurementTypes.all.size)
        assertEquals(16, MeasurementTypes.all.map { it.key }.toSet().size)
        val json = MeasurementTypes.all.flatMap { t ->
            t.fields.flatMap {
                listOf(it.jsonKey) +
                    it.altKeys.keys
            }
        }
        assertEquals(json.size, json.toSet().size)
    }

    @Test fun everyTypeRoundTripsThroughItsRecord() {
        MeasurementTypes.all.forEach { t ->
            val values = t.fields.associate { it.name to it.plausible.start + 1.0 }
            val record = t.build(values, start, offset, Metadata.manualEntry("x", 1))
            assertEquals(t.recordClass, record::class)
            t.fields.forEach { f ->
                assertEquals(values.getValue(f.name), t.read(record).getValue(f.name), 1e-6)
            }
            assertEquals(start, recordStart(record).first)
        }
    }

    @Test fun poundsConvertToKilograms() = assertEquals(
        82.4,
        MeasurementTypes.byJsonKey("weight_lb")!!.second.altKeys.getValue("weight_lb")(181.66),
        0.01
    )

    @Test fun fahrenheitConvertsToCelsius() = assertEquals(
        37.0,
        MeasurementTypes.byJsonKey(
            "body_temperature_f"
        )!!.second.altKeys.getValue("body_temperature_f")(98.6),
        0.01
    )

    @Test fun outOfHealthConnectRangeThrows() {
        val bp = MeasurementTypes.byKey("blood_pressure")!!
        assertFailsWith<IllegalArgumentException> {
            bp.build(
                mapOf("systolic" to 900.0, "diastolic" to 80.0),
                start,
                offset,
                Metadata.manualEntry("x", 1)
            )
        }
    }
}
