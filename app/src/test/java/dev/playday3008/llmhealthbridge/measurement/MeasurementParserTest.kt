package dev.playday3008.llmhealthbridge.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryParse
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.EntryType.BODY
import dev.playday3008.llmhealthbridge.entry.EntryType.HYDRATION
import dev.playday3008.llmhealthbridge.entry.EntryType.VITALS
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.nutrition.Issue

private val NOW = Instant.parse("2026-10-18T12:00:00Z")
private val ZONE = ZoneId.of("Europe/Warsaw")
private const val REQUEST = "mvdrjvpc-7f2a"

private fun parse(json: String, type: EntryType): MeasurementEntry = (
    parseMeasurementEntry(
        Json.parseToJsonElement(json).jsonObject,
        GroupId(type, REQUEST, "e1"),
        NOW,
        ZONE
    )
        as EntryParse.Valid
    ).group as MeasurementEntry

class MeasurementParserTest {
    @Test fun scaleReading() {
        val e =
            parse(
                """{"time":"2026-10-18T07:12:00+02:00","weight_kg":82.4,"body_fat_percent":18.6}""",
                BODY
            )
        assertEquals(listOf("weight", "body_fat"), e.values.map { it.type.key })
        assertEquals("Weight 82.4 kg + 1 more", e.summary())
    }

    @Test fun poundsAreConverted() = assertEquals(
        82.4,
        parse("""{"weight_lb":181.66}""", BODY).values[0].values.getValue("weight"),
        0.01
    )

    @Test fun twoUnitsIsFixable() = assertEquals(
        listOf(Issue("weight", "given in two units")),
        parse("""{"weight_kg":82.4,"weight_lb":181.66}""", BODY).values[0].issues()
    )

    @Test fun combinedBloodPressureStringIsFixable() {
        val v = parse("""{"systolic_mmhg":"128/82","diastolic_mmhg":82}""", VITALS).values[0]
        assertEquals(listOf(Issue("systolic", "must be a number")), v.issues())
    }

    @Test fun commaDecimalStringIsFixable() = assertEquals(
        listOf(Issue("weight", "must be a number")),
        parse("""{"weight_kg":"82,4"}""", BODY).values[0].issues()
    )

    @Test fun lonelySystolicIsFixable() = assertEquals(
        listOf(Issue("blood_pressure", "systolic and diastolic go together")),
        parse("""{"systolic_mmhg":128}""", VITALS).values[0].issues()
    )

    @Test fun completingBloodPressureClearsPairIssue() {
        val v = parse("""{"systolic_mmhg":128}""", VITALS).values[0]
        val fixed = v.copy(values = v.values + ("diastolic" to 82.0))
        assertEquals(emptyList(), fixed.issues())
        assertEquals("Blood pressure 128/82 mmHg", fixed.summary())
    }

    @Test fun glucoseMgDlIsConverted() = assertEquals(
        5.55,
        parse("""{"blood_glucose_mg_dl":100}""", VITALS).values[0].values.getValue("blood_glucose"),
        0.01
    )

    @Test fun fahrenheitIsConverted() = assertEquals(
        37.0,
        parse(
            """{"body_temperature_f":98.6}""",
            VITALS
        ).values[0].values.getValue("body_temperature"),
        0.01
    )

    @Test fun convertedValueIsRoundedToFourDecimals() {
        val f = parse("""{"body_temperature_f":98.6}""", VITALS).values[0]
        assertEquals(37.0, f.values.getValue("body_temperature"))
        val lb = parse("""{"weight_lb":100}""", BODY).values[0]
        assertEquals(45.3592, lb.values.getValue("weight"))
    }

    @Test fun valueWarningsAreNotCopiedToTheEntry() =
        assertEquals(emptyList(), parse("""{"body_fat_percent":158.1}""", BODY).warnings)

    @Test fun fractionalHeartRateIsFixable() = assertEquals(
        listOf(Issue("heart_rate", "must be a whole number")),
        parse("""{"heart_rate_bpm":71.5}""", VITALS).values[0].issues()
    )

    @Test fun implausibleWarns() = assertTrue(
        parse("""{"body_fat_percent":158.1}""", BODY).values[0].warnings().single()
            .startsWith("Body fat looks wrong")
    )

    @Test fun keyOfAnotherEntryTypeSaysWhereItBelongs() = assertTrue(
        "weight_kg belongs in a body entry, not saved" in
            parse("""{"weight_kg":82,"heart_rate_bpm":70}""", VITALS).warnings
    )

    @Test fun nutrientInAMeasurementEntrySaysWhereItBelongs() = assertTrue(
        "energy_kcal belongs in an item of a nutrition entry, not saved" in
            parse("""{"volume_ml":330,"energy_kcal":140}""", HYDRATION).warnings
    )

    @Test fun unknownKeyIsUnknown() = assertTrue(
        "Ignored unknown key: mood" in parse("""{"mood":3,"heart_rate_bpm":70}""", VITALS).warnings
    )

    @Test fun nullValuesCountAsNotGiven() {
        val e = parse("""{"time":null,"weight_kg":82,"body_fat_percent":null}""", BODY)
        assertEquals(listOf("weight"), e.values.map { it.type.key })
        assertEquals(emptyList(), e.warnings)
        assertEquals(emptyList(), e.issues(NOW))
    }

    @Test fun conversionOverflowIsInvalidNotACrash() {
        val e = parse("""{"weight_lb":1e306}""", BODY)
        assertEquals(mapOf("weight" to "must be a number"), e.values.single().invalid)
    }

    @Test fun timeOutsideTheSupportedYearsFallsBackToNow() {
        val e = parse("""{"time":"+999999999-12-31T23:59:59Z","volume_ml":250}""", HYDRATION)
        assertEquals(NOW, e.time.toInstant())
        assertEquals(listOf("time: unreadable, using now"), e.warnings)
    }

    @Test fun noValuesIsInvalid() = assertEquals(
        EntryParse.Invalid(listOf("no measurement values")),
        parseMeasurementEntry(
            Json.parseToJsonElement("""{"time":"2026-10-18T07:12:00+02:00"}""").jsonObject,
            GroupId(BODY, REQUEST, "e1"),
            NOW,
            ZONE
        )
    )
}
