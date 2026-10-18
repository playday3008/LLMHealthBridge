@file:Suppress("MagicNumber")

package dev.playday3008.llmhealthbridge.measurement

import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume

import kotlin.math.roundToLong

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.nutrition.Nutrients

/** Length of the records of [MeasurementType.interval] types. */
internal val INTERVAL_LENGTH: Duration = Duration.ofMinutes(1)

private fun field(
    name: String,
    unit: String,
    jsonKey: String,
    plausible: ClosedFloatingPointRange<Double>,
    whole: Boolean = false,
    altKeys: Map<String, (Double) -> Double> = emptyMap()
) = MeasurementField(name, unit, jsonKey, altKeys, plausible, whole)

/** A type with one field named after the type key, stored in an instantaneous record. */
private inline fun <reified R : Record> single(
    key: String,
    entryType: EntryType,
    label: String,
    field: MeasurementField,
    crossinline build: (Instant, ZoneOffset, Metadata, Double) -> R,
    crossinline read: (R) -> Double,
    crossinline startOf: (R) -> Pair<Instant, ZoneOffset?>
) = MeasurementType(
    key = key,
    entryType = entryType,
    label = label,
    fields = listOf(field),
    recordClass = R::class,
    build = { values, start, offset, metadata ->
        build(start, offset, metadata, values.getValue(field.name))
    },
    read = { record -> mapOf(field.name to read(record as R)) },
    startOf = { record -> startOf(record as R) }
)

private fun temperatureField(key: String, plausible: ClosedFloatingPointRange<Double>) = field(
    key,
    "deg C",
    "${key}_c",
    plausible,
    altKeys = mapOf("${key}_f" to { v -> Temperature.fahrenheit(v).inCelsius })
)

private fun weightField(key: String, plausible: ClosedFloatingPointRange<Double>) = field(
    key,
    "kg",
    "${key}_kg",
    plausible,
    altKeys = mapOf("${key}_lb" to { v -> Mass.pounds(v).inKilograms })
)

/** Registry of the record types that are logged besides meals, in the order shown to the user. */
object MeasurementTypes {
    /** All types in canonical order. */
    val all: List<MeasurementType> = listOf(
        hydration(),
        weight(),
        height(),
        single<BodyFatRecord>(
            "body_fat",
            EntryType.BODY,
            "Body fat",
            field("body_fat", "%", "body_fat_percent", 2.0..70.0),
            { t, o, m, v ->
                BodyFatRecord(time = t, zoneOffset = o, percentage = Percentage(v), metadata = m)
            },
            { it.percentage.value },
            { it.time to it.zoneOffset }
        ),
        single<LeanBodyMassRecord>(
            "lean_body_mass",
            EntryType.BODY,
            "Lean body mass",
            weightField("lean_body_mass", 10.0..150.0),
            { t, o, m, v ->
                LeanBodyMassRecord(time = t, zoneOffset = o, mass = Mass.kilograms(v), metadata = m)
            },
            { it.mass.inKilograms },
            { it.time to it.zoneOffset }
        ),
        single<BoneMassRecord>(
            "bone_mass",
            EntryType.BODY,
            "Bone mass",
            weightField("bone_mass", 0.5..10.0),
            { t, o, m, v ->
                BoneMassRecord(time = t, zoneOffset = o, mass = Mass.kilograms(v), metadata = m)
            },
            { it.mass.inKilograms },
            { it.time to it.zoneOffset }
        ),
        single<BodyWaterMassRecord>(
            "body_water_mass",
            EntryType.BODY,
            "Body water mass",
            weightField("body_water_mass", 10.0..100.0),
            { t, o, m, v ->
                BodyWaterMassRecord(
                    time = t,
                    zoneOffset = o,
                    mass = Mass.kilograms(v),
                    metadata = m
                )
            },
            { it.mass.inKilograms },
            { it.time to it.zoneOffset }
        ),
        single<BasalMetabolicRateRecord>(
            "basal_metabolic_rate",
            EntryType.BODY,
            "Basal metabolic rate",
            field(
                "basal_metabolic_rate",
                "kcal/day",
                "basal_metabolic_rate_kcal_per_day",
                500.0..5000.0
            ),
            { t, o, m, v ->
                BasalMetabolicRateRecord(
                    time = t,
                    zoneOffset = o,
                    basalMetabolicRate = Power.kilocaloriesPerDay(v),
                    metadata = m
                )
            },
            { it.basalMetabolicRate.inKilocaloriesPerDay },
            { it.time to it.zoneOffset }
        ),
        bloodPressure(),
        single<BloodGlucoseRecord>(
            "blood_glucose",
            EntryType.VITALS,
            "Blood glucose",
            field(
                "blood_glucose",
                "mmol/L",
                "blood_glucose_mmol_l",
                1.0..35.0,
                altKeys = mapOf(
                    "blood_glucose_mg_dl" to
                        { v -> BloodGlucose.milligramsPerDeciliter(v).inMillimolesPerLiter }
                )
            ),
            { t, o, m, v ->
                BloodGlucoseRecord(
                    time = t,
                    zoneOffset = o,
                    level = BloodGlucose.millimolesPerLiter(v),
                    metadata = m
                )
            },
            { it.level.inMillimolesPerLiter },
            { it.time to it.zoneOffset }
        ),
        single<BodyTemperatureRecord>(
            "body_temperature",
            EntryType.VITALS,
            "Body temperature",
            temperatureField("body_temperature", 30.0..45.0),
            { t, o, m, v ->
                BodyTemperatureRecord(
                    time = t,
                    zoneOffset = o,
                    temperature = Temperature.celsius(v),
                    metadata = m
                )
            },
            { it.temperature.inCelsius },
            { it.time to it.zoneOffset }
        ),
        single<BasalBodyTemperatureRecord>(
            "basal_body_temperature",
            EntryType.VITALS,
            "Basal body temperature",
            temperatureField("basal_body_temperature", 34.0..39.0),
            { t, o, m, v ->
                BasalBodyTemperatureRecord(
                    time = t,
                    zoneOffset = o,
                    temperature = Temperature.celsius(v),
                    metadata = m
                )
            },
            { it.temperature.inCelsius },
            { it.time to it.zoneOffset }
        ),
        single<RestingHeartRateRecord>(
            "resting_heart_rate",
            EntryType.VITALS,
            "Resting heart rate",
            field("resting_heart_rate", "bpm", "resting_heart_rate_bpm", 25.0..200.0, whole = true),
            { t, o, m, v ->
                RestingHeartRateRecord(
                    time = t,
                    zoneOffset = o,
                    beatsPerMinute = v.roundToLong(),
                    metadata = m
                )
            },
            { it.beatsPerMinute.toDouble() },
            { it.time to it.zoneOffset }
        ),
        heartRate(),
        single<OxygenSaturationRecord>(
            "oxygen_saturation",
            EntryType.VITALS,
            "Blood oxygen",
            field("oxygen_saturation", "%", "oxygen_saturation_percent", 70.0..100.0),
            { t, o, m, v ->
                OxygenSaturationRecord(
                    time = t,
                    zoneOffset = o,
                    percentage = Percentage(v),
                    metadata = m
                )
            },
            { it.percentage.value },
            { it.time to it.zoneOffset }
        ),
        single<RespiratoryRateRecord>(
            "respiratory_rate",
            EntryType.VITALS,
            "Respiratory rate",
            field("respiratory_rate", "/min", "respiratory_rate_per_min", 4.0..60.0),
            { t, o, m, v ->
                RespiratoryRateRecord(time = t, zoneOffset = o, rate = v, metadata = m)
            },
            { it.rate },
            { it.time to it.zoneOffset }
        )
    )

    private val byKey = all.associateBy { it.key }

    /** The type with the given registry [key], or null. */
    fun byKey(key: String): MeasurementType? = byKey[key]

    /** All types belonging to [type], in registry order. */
    fun forEntryType(type: EntryType): List<MeasurementType> = all.filter { it.entryType == type }

    /** Resolves a canonical or alternative JSON key to its type and field. */
    fun byJsonKey(jsonKey: String): Pair<MeasurementType, MeasurementField>? {
        for (type in all) {
            for (f in type.fields) {
                if (f.jsonKey == jsonKey || jsonKey in f.altKeys) return type to f
            }
        }
        return null
    }

    private fun hydration(): MeasurementType {
        val volume = field("volume", "ml", "volume_ml", 10.0..5000.0)
        return MeasurementType(
            key = "hydration",
            entryType = EntryType.HYDRATION,
            label = "Hydration",
            fields = listOf(volume),
            recordClass = HydrationRecord::class,
            build = { values, start, offset, metadata ->
                HydrationRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plus(INTERVAL_LENGTH),
                    endZoneOffset = offset,
                    volume = Volume.milliliters(values.getValue(volume.name)),
                    metadata = metadata
                )
            },
            read = { mapOf(volume.name to (it as HydrationRecord).volume.inMilliliters) },
            startOf = { (it as HydrationRecord).let { r -> r.startTime to r.startZoneOffset } },
            interval = true
        )
    }

    private fun weight(): MeasurementType = single<WeightRecord>(
        "weight",
        EntryType.BODY,
        "Weight",
        weightField("weight", 20.0..300.0),
        { t, o, m, v ->
            WeightRecord(time = t, zoneOffset = o, weight = Mass.kilograms(v), metadata = m)
        },
        { it.weight.inKilograms },
        { it.time to it.zoneOffset }
    )

    private fun height(): MeasurementType = single<HeightRecord>(
        "height",
        EntryType.BODY,
        "Height",
        field("height", "cm", "height_cm", 50.0..250.0),
        { t, o, m, v ->
            HeightRecord(time = t, zoneOffset = o, height = Length.meters(v / 100), metadata = m)
        },
        { it.height.inMeters * 100 },
        { it.time to it.zoneOffset }
    )

    private fun bloodPressure(): MeasurementType {
        val systolic = field("systolic", "mmHg", "systolic_mmhg", 60.0..200.0)
        val diastolic = field("diastolic", "mmHg", "diastolic_mmhg", 30.0..150.0)
        return MeasurementType(
            key = "blood_pressure",
            entryType = EntryType.VITALS,
            label = "Blood pressure",
            fields = listOf(systolic, diastolic),
            recordClass = BloodPressureRecord::class,
            build = { values, start, offset, metadata ->
                BloodPressureRecord(
                    time = start,
                    zoneOffset = offset,
                    metadata = metadata,
                    systolic = Pressure.millimetersOfMercury(values.getValue(systolic.name)),
                    diastolic = Pressure.millimetersOfMercury(values.getValue(diastolic.name))
                )
            },
            read = {
                val r = it as BloodPressureRecord
                mapOf(
                    systolic.name to r.systolic.inMillimetersOfMercury,
                    diastolic.name to r.diastolic.inMillimetersOfMercury
                )
            },
            startOf = { (it as BloodPressureRecord).let { r -> r.time to r.zoneOffset } }
        )
    }

    private fun heartRate(): MeasurementType {
        val bpm = field("heart_rate", "bpm", "heart_rate_bpm", 25.0..250.0, whole = true)
        return MeasurementType(
            key = "heart_rate",
            entryType = EntryType.VITALS,
            label = "Heart rate",
            fields = listOf(bpm),
            recordClass = HeartRateRecord::class,
            build = { values, start, offset, metadata ->
                HeartRateRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = start.plus(INTERVAL_LENGTH),
                    endZoneOffset = offset,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = start,
                            beatsPerMinute = values.getValue(bpm.name).roundToLong()
                        )
                    ),
                    metadata = metadata
                )
            },
            read = {
                mapOf(
                    bpm.name to (it as HeartRateRecord).samples.first().beatsPerMinute.toDouble()
                )
            },
            startOf = { (it as HeartRateRecord).let { r -> r.startTime to r.startZoneOffset } },
            interval = true
        )
    }
}

/**
 * A warning for [key], shown as [label], found where an entry of [type] keeps its values (the
 * entry itself, or a meal item for nutrition) when [key] belongs elsewhere: a measurement key of
 * another entry type, or a nutrient outside a meal item. The value is then not silently lost as an
 * unknown key. Null when [key] is not a known key of another place.
 */
internal fun misplacedKeyWarning(key: String, type: EntryType, label: String = key): String? {
    val owner = MeasurementTypes.byJsonKey(key)?.first?.entryType
    return when {
        owner != null && owner != type -> "$label belongs in a ${owner.jsonName} entry, not saved"
        owner == null && Nutrients.byKey(key) != null ->
            "$label belongs in an item of a nutrition entry, not saved"
        else -> null
    }
}
