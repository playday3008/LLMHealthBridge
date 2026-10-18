@file:Suppress("TooManyFunctions")

package dev.playday3008.llmhealthbridge.entry

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.measurement.parseMeasurementEntry
import dev.playday3008.llmhealthbridge.nutrition.parseMeal

private const val ENTRIES_KEY = "entries"
private const val REQUEST_KEY = "request"
private const val ID_KEY = "id"

/** How far a request may lie in the future, for a phone clock set back between send and paste. */
private val CLOCK_SKEW: Duration = Duration.ofDays(1)
private const val CUT_OFF = "The reply looks cut off: its JSON never closes. Copy the whole reply."
private const val ARRAY_PART =
    "The reply looks partly copied: its JSON is one element of a list. Copy the whole code block."
private const val NO_REQUEST =
    "request: missing or not from this app, so corrections of this reply are saved as new entries"

/**
 * Parses [text] (an LLM reply containing one JSON object holding one entry or an `entries` array)
 * into a [ReplyResult], using [zone] for local times. Entries are identified by the reply's
 * `request` and each entry's `id`; a reply without a usable request gets [fallbackRequest].
 */
fun parseReply(
    text: String,
    now: Instant,
    zone: ZoneId,
    fallbackRequest: String = RequestIds.create(now)
): ReplyResult {
    val json = extractJsonObject(text)
    return when {
        json == null -> ReplyResult.Blocking(listOf("No JSON found"), null)
        !isClosedObject(json) -> ReplyResult.Blocking(listOf(CUT_OFF), json)
        isArrayElement(text) -> ReplyResult.Blocking(listOf(ARRAY_PART), json)
        else -> readObject(json).fold(
            onSuccess = { obj ->
                val warnings = mutableListOf<String>()
                val context = ParseContext(
                    json,
                    requestOf(obj, now, fallbackRequest, warnings),
                    now,
                    zone,
                    warnings
                )
                if (ENTRIES_KEY in obj) {
                    parseEntries(obj, json, context)
                } else {
                    parseBare(obj, json, context)
                }
            },
            onFailure = { ReplyResult.Blocking(listOf(it.message ?: "Invalid JSON"), json) }
        )
    }
}

/**
 * True when [text] contains a JSON object that parses, is not an element of a list and has an
 * `entries`, `items` or `type` key.
 */
fun looksLikeReply(text: String): Boolean {
    if (isArrayElement(text)) return false
    val obj = extractJsonObject(text)?.let { readObject(it).getOrNull() }
    return obj != null && (ENTRIES_KEY in obj || "items" in obj || "type" in obj)
}

private class ParseContext(
    val json: String,
    val request: String,
    val now: Instant,
    val zone: ZoneId,
    val warnings: MutableList<String>
) {
    val usedIds = mutableSetOf<String>()
}

private fun requestOf(
    obj: JsonObject,
    now: Instant,
    fallback: String,
    warnings: MutableList<String>
): String {
    val request = (obj.given(REQUEST_KEY) as? JsonPrimitive)?.takeIf { it.isString }
        ?.content?.let(RequestIds::normalize)
        ?.takeIf { !RequestIds.sentAt(it).isAfter(now.plus(CLOCK_SKEW)) }
    if (request == null) warnings += NO_REQUEST
    return request ?: fallback
}

private fun parseBare(obj: JsonObject, json: String, context: ParseContext): ReplyResult {
    val type = entryType(obj)
        ?: return ReplyResult.Blocking(listOf("type: unsupported value: ${rawType(obj)}"), json)
    val entry = JsonObject(obj - REQUEST_KEY)
    val id = GroupId(type, context.request, entryId(entry, 0, "", context))
    return when (val parsed = parseOne(entry, id, context)) {
        is EntryParse.Invalid -> ReplyResult.Blocking(parsed.errors, json)
        is EntryParse.Valid -> ReplyResult.Parsed(listOf(parsed.group), context.warnings)
    }
}

private fun parseEntries(obj: JsonObject, json: String, context: ParseContext): ReplyResult {
    val entries = obj.getValue(ENTRIES_KEY)
    if (entries !is JsonArray || entries.isEmpty()) {
        return ReplyResult.Blocking(listOf("entries: must be a non-empty array"), json)
    }
    obj.keys.filter { it != ENTRIES_KEY && it != REQUEST_KEY }
        .forEach { context.warnings += "Ignored unknown key: $it" }
    val ignored = mutableListOf<String>()
    val groups = mutableListOf<Group>()
    entries.forEachIndexed { index, element ->
        when (val problem = parseEntryElement(element, index, context)) {
            is EntryParse.Valid -> groups += problem.group
            is EntryParse.Invalid ->
                ignored +=
                    "entries[$index]: ${problem.errors.joinToString("; ")}, ignored"
        }
    }
    context.warnings += ignored
    return if (groups.isEmpty()) {
        ReplyResult.Blocking(ignored, json)
    } else {
        ReplyResult.Parsed(groups, context.warnings)
    }
}

private fun parseEntryElement(element: JsonElement, index: Int, context: ParseContext): EntryParse {
    val type = (element as? JsonObject)?.let { entryType(it) }
    return when {
        element !is JsonObject -> EntryParse.Invalid(listOf("must be an object"))
        type == null -> EntryParse.Invalid(listOf("unsupported type ${rawType(element)}"))
        else -> {
            val id =
                GroupId(
                    type,
                    context.request,
                    entryId(element, index, "entries[$index]: ", context)
                )
            parseOne(element, id, context)
        }
    }
}

/**
 * The entry's `id` when it is usable and not taken by an earlier entry of the reply, else a
 * fallback id, with a warning prefixed by [label]: such an entry cannot be matched by a later
 * correction.
 */
private fun entryId(obj: JsonObject, index: Int, label: String, context: ParseContext): String {
    val raw = obj.given(ID_KEY)?.let { (it as? JsonPrimitive)?.content?.trim().orEmpty() }
    val problem = when {
        raw == null -> "no id"
        !RequestIds.isEntryId(raw) -> "id must be up to 32 letters, digits, - or _"
        raw in context.usedIds -> "id $raw is used twice"
        else -> null
    }
    if (problem != null) context.warnings += "$label$problem, so it is saved as a new entry"
    val id = raw.takeIf { problem == null } ?: RequestIds.fallbackEntryId(context.json, index)
    context.usedIds += id
    return id
}

private fun parseOne(obj: JsonObject, id: GroupId, context: ParseContext): EntryParse =
    if (id.type == EntryType.NUTRITION) {
        parseMeal(obj, id, context.now, context.zone)
    } else {
        parseMeasurementEntry(obj, id, context.now, context.zone)
    }

/** The entry's type; a missing `type` means nutrition. Null when the value names no known type. */
private fun entryType(obj: JsonObject): EntryType? =
    obj.given("type")?.let { EntryType.fromJson(rawText(it)) }
        ?: EntryType.NUTRITION.takeIf { obj.given("type") == null }

private fun rawType(obj: JsonObject): String = obj["type"]?.let { rawText(it) }.orEmpty()

private fun readObject(json: String): Result<JsonObject> = try {
    Result.success(Json.parseToJsonElement(json) as? JsonObject ?: error("Invalid JSON"))
} catch (e: SerializationException) {
    Result.failure(e)
} catch (e: IllegalStateException) {
    Result.failure(e)
}

private fun rawText(element: JsonElement): String =
    if (element is JsonPrimitive) element.content else element.toString()
