package dev.playday3008.llmhealthbridge.measurement

import kotlin.math.round

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryParse
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.given
import dev.playday3008.llmhealthbridge.entry.resolveTime

private val RESERVED_KEYS = setOf("type", "id", "time")
private const val CONVERTED_DECIMALS = 4

/**
 * Parses a hydration, body or vitals entry object as entry [id]. Without any known measurement key
 * the entry is invalid; every other problem is fixable or a warning. Keys given as JSON `null`
 * count as not given.
 */
internal fun parseMeasurementEntry(
    obj: JsonObject,
    id: GroupId,
    now: Instant,
    zone: ZoneId
): EntryParse {
    val warnings = mutableListOf<String>()
    val known = mutableSetOf<String>()
    for (key in obj.keys) {
        if (key in RESERVED_KEYS || obj.given(key) == null) continue
        if (MeasurementTypes.byJsonKey(key)?.first?.entryType == id.type) {
            known += key
        } else {
            warnings += misplacedKeyWarning(key, id.type) ?: "Ignored unknown key: $key"
        }
    }
    val values = MeasurementTypes.forEntryType(id.type).mapNotNull { parseValue(obj, it, known) }
    if (values.isEmpty()) return EntryParse.Invalid(listOf("no measurement values"))

    val time = resolveTime(obj.given("time"), now, zone, warnings)
    return EntryParse.Valid(MeasurementEntry(id, time, values, warnings))
}

private fun parseValue(
    obj: JsonObject,
    type: MeasurementType,
    known: Set<String>
): MeasurementValue? {
    val values = mutableMapOf<String, Double>()
    val invalid = mutableMapOf<String, String>()
    var present = 0
    for (field in type.fields) {
        val keys = (listOf(field.jsonKey) + field.altKeys.keys).filter { it in known }
        if (keys.isNotEmpty()) present++
        when (val result = parseField(obj, field, keys)) {
            null -> Unit
            is FieldResult.Ok -> values[field.name] = result.value
            is FieldResult.Bad -> invalid[field.name] = result.reason
        }
    }
    if (present == 0) return null
    return MeasurementValue(type, values, invalid)
}

private sealed interface FieldResult {
    class Ok(val value: Double) : FieldResult

    class Bad(val reason: String) : FieldResult
}

/** Reads [field] from the JSON keys [keys] it was given under, or null when it was not given. */
private fun parseField(obj: JsonObject, field: MeasurementField, keys: List<String>): FieldResult? {
    if (keys.isEmpty()) return null
    val raw = if (keys.size == 1) numberOrNull(obj.getValue(keys.single())) else null
    val reason = if (keys.size > 1) "given in two units" else field.reject(raw)
    val convert = if (reason == null) field.altKeys[keys.single()] else null
    val value = raw?.takeIf { reason == null }?.let { convert?.invoke(it) ?: it }
    return when {
        value == null -> FieldResult.Bad(reason.orEmpty())
        // A conversion can overflow a finite raw value.
        !value.isFinite() -> FieldResult.Bad("must be a number")
        convert != null -> FieldResult.Ok(roundConverted(value))
        else -> FieldResult.Ok(value)
    }
}

/** A value converted from an alternative unit, rounded so float noise does not show. */
private fun roundConverted(value: Double): Double =
    BigDecimal.valueOf(value).setScale(CONVERTED_DECIMALS, RoundingMode.HALF_UP).toDouble()

/** Why [raw] is not an acceptable value for this field, or null when it is. */
internal fun MeasurementField.reject(raw: Double?): String? = when {
    raw == null || !raw.isFinite() -> "must be a number"
    raw < 0 -> "must be a number >= 0"
    whole && raw != round(raw) -> "must be a whole number"
    else -> null
}

private fun numberOrNull(raw: JsonElement): Double? =
    (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
