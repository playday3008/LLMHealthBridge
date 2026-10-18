package dev.playday3008.llmhealthbridge.measurement

import kotlin.math.round

import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.timeIssue
import dev.playday3008.llmhealthbridge.nutrition.Issue

/**
 * The values of one [MeasurementType] in an entry. [values] holds the valid canonical values by
 * field name; [invalid] maps field names whose raw value was unusable to the reason.
 */
data class MeasurementValue(
    val type: MeasurementType,
    val values: Map<String, Double>,
    val invalid: Map<String, String>
) {
    /** Problems that block saving this value. */
    fun issues(): List<Issue> = buildList {
        invalid.forEach { (field, reason) -> add(Issue(field, reason)) }
        val missing = type.fields.any { it.name !in values && it.name !in invalid }
        if (type.fields.size > 1 &&
            missing
        ) {
            add(Issue(type.key, "systolic and diastolic go together"))
        }
    }

    /** Informational notes about implausible values. */
    fun warnings(): List<String> = buildList {
        for (f in type.fields) {
            val v = values[f.name] ?: continue
            if (v !in f.plausible) {
                val label = if (type.fields.size == 1) type.label else "${type.label} ${f.name}"
                add("$label looks wrong: ${formatNumber(v)} ${f.unit}")
            }
        }
        val systolic = values["systolic"]
        val diastolic = values["diastolic"]
        if (systolic != null && diastolic != null && systolic <= diastolic) {
            add("Systolic is not above diastolic")
        }
    }

    internal fun summary(): String {
        val numbers = type.fields.mapNotNull { values[it.name] }
        return when {
            numbers.isEmpty() -> type.label
            type.fields.size > 1 && numbers.size == type.fields.size ->
                "${type.label} ${numbers.joinToString("/") {
                    formatNumber(it)
                }} ${type.fields.first().unit}"
            else -> "${type.label} ${formatNumber(numbers.first())} ${type.fields.first().unit}"
        }
    }
}

/** A parsed hydration, body or vitals entry: one [MeasurementValue] per measurement given. */
data class MeasurementEntry(
    override val id: GroupId,
    override val time: OffsetDateTime,
    val values: List<MeasurementValue>,
    override val warnings: List<String>
) : Group {
    /** Problems that block saving, evaluated against the current instant [now]. */
    fun issues(now: Instant): List<Issue> = buildList {
        timeIssue(id, time, now)?.let { add(Issue("time", it)) }
        values.forEach { addAll(it.issues()) }
    }

    /** One-line description: the first value, plus a count of the others. */
    fun summary(): String {
        val first = values.firstOrNull()?.summary() ?: return ""
        return if (values.size > 1) "$first + ${values.size - 1} more" else first
    }
}

private const val MAX_WHOLE_DISPLAY = 1_000_000_000.0

internal fun formatNumber(value: Double): String =
    if (value == round(value) && kotlin.math.abs(value) < MAX_WHOLE_DISPLAY) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
