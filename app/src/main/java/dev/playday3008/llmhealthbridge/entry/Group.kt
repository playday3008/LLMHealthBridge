package dev.playday3008.llmhealthbridge.entry

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

import java.time.Instant
import java.time.OffsetDateTime

/** One logged entry, written to Health Connect as one or more records that share [id]. */
interface Group {
    val id: GroupId
    val time: OffsetDateTime

    /** Informational notes that never block saving. */
    val warnings: List<String>
}

/** Outcome of parsing one entry object. */
internal sealed interface EntryParse {
    /** The object cannot be turned into an entry. */
    data class Invalid(val errors: List<String>) : EntryParse

    /** A parsed entry; it may still carry fixable issues. */
    data class Valid(val group: Group) : EntryParse
}

/** The value of [key], or null when it is missing or JSON `null`; both mean "not given". */
internal fun JsonObject.given(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

/**
 * Why [time] cannot be saved for entry [id], or null when it can: it must not be after [now] nor
 * more than [RequestIds.LOOKBACK] before the entry's request was sent.
 */
internal fun timeIssue(id: GroupId, time: OffsetDateTime, now: Instant): String? {
    val instant = time.toInstant()
    return when {
        instant.isAfter(now) -> "in the future"
        instant.isBefore(RequestIds.earliest(id.request)) ->
            "more than ${RequestIds.LOOKBACK.toDays()} days before the request"
        else -> null
    }
}
