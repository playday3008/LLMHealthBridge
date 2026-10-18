package dev.playday3008.llmhealthbridge.entry

import kotlin.random.Random

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale

private const val PREFIX = "llmhb3"
private const val KEY_HASH_CHARS = 16
private const val RADIX = 36
private const val NONCE_CHARS = 4
private const val FALLBACK_TAG_CHARS = 8
private const val FALLBACK_MARK = "~"
private const val ENTRY_ID_MAX = 32
private const val REQUEST = """[0-9a-z]{1,12}-[0-9a-z]{$NONCE_CHARS}"""
private const val ENTRY = """[A-Za-z0-9_~-]{1,$ENTRY_ID_MAX}"""
private const val RECORD_KEY = """\d+-[0-9a-f]{$KEY_HASH_CHARS}|[a-z][a-z0-9_]*"""
private val ID_FORMAT = Regex("""$PREFIX/([a-z]+)/($REQUEST)/($ENTRY)/($RECORD_KEY)""")
private val REQUEST_FORMAT = Regex(REQUEST)
private val LLM_ENTRY_FORMAT = Regex("""[A-Za-z0-9_-]{1,$ENTRY_ID_MAX}""")
private val ALPHABET = ('0'..'9') + ('a'..'z').toList()

/** Screen section an [EntryType] belongs to. */
enum class Section { NUTRITION, BODY, VITALS }

/** Kind of entry as named in the entry JSON. */
enum class EntryType(val jsonName: String, val section: Section) {
    NUTRITION("nutrition", Section.NUTRITION),
    HYDRATION("hydration", Section.NUTRITION),
    BODY("body", Section.BODY),
    VITALS("vitals", Section.VITALS);

    companion object {
        /** The type named [name] (trimmed, case-insensitive), or null if unknown. */
        fun fromJson(name: String): EntryType? {
            val wanted = name.trim()
            return entries.firstOrNull { it.jsonName.equals(wanted, ignoreCase = true) }
        }
    }
}

/**
 * Identifies one logged entry: [request] names the send it was logged for and [entry] the entry
 * within it, as the LLM labeled it. All Health Connect records of the entry share it, and a
 * corrected reply with the same ids replaces the entry, whatever its time.
 */
data class GroupId(val type: EntryType, val request: String, val entry: String)

/** Request ids: the send time in base 36 and a random suffix, as in `lq3x9k2a-7f2a`. */
object RequestIds {
    /**
     * How far before its request an entry may lie. Every record of a request is then within
     * [earliest] and now, which bounds the Health Connect reads that look for them.
     */
    val LOOKBACK: Duration = Duration.ofDays(30)

    /** A new request id for a send at [now]. */
    fun create(now: Instant, random: Random = Random.Default): String =
        now.toEpochMilli().toString(RADIX) + "-" +
            (1..NONCE_CHARS).map { ALPHABET[random.nextInt(ALPHABET.size)] }.joinToString("")

    /** [raw] trimmed and lowercased when it is a request id, else null. */
    fun normalize(raw: String): String? =
        raw.trim().lowercase(Locale.ROOT).takeIf { REQUEST_FORMAT.matches(it) }

    /** When [request] was sent. */
    fun sentAt(request: String): Instant =
        Instant.ofEpochMilli(request.substringBefore('-').toLong(RADIX))

    /** The earliest time an entry of [request] may have. */
    fun earliest(request: String): Instant = sentAt(request).minus(LOOKBACK)

    /** True when [raw] can be used as an entry id given by the LLM. */
    fun isEntryId(raw: String): Boolean = LLM_ENTRY_FORMAT.matches(raw)

    /**
     * Entry id for the entry at [index] of the reply whose JSON is [json], when the LLM gave none.
     * It never clashes with an LLM id, stays the same when the same reply is pasted again, and
     * differs between replies, so an entry of another reply is never replaced by position.
     */
    fun fallbackEntryId(json: String, index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        val tag = digest.joinToString("") {
            "%02x".format(Locale.ROOT, it)
        }.take(FALLBACK_TAG_CHARS)
        return "$FALLBACK_MARK$tag-${index + 1}"
    }

    /** True when [entry] was made by [fallbackEntryId] rather than given by the LLM. */
    fun isFallbackEntryId(entry: String): Boolean = entry.startsWith(FALLBACK_MARK)
}

/** Client record IDs, which tie Health Connect records back to the entry that wrote them. */
object RecordIds {
    /** Client record ID for the record named [recordKey] of [group]. */
    fun clientRecordId(group: GroupId, recordKey: String): String =
        "$PREFIX/${group.type.jsonName}/${group.request}/${group.entry}/$recordKey"

    /** Splits a client record ID into (group, recordKey), or null if it was not made by this app. */
    fun parse(clientRecordId: String): Pair<GroupId, String>? {
        val match = ID_FORMAT.matchEntire(clientRecordId) ?: return null
        val (type, request, entry) = match.destructured
        return EntryType.entries.firstOrNull { it.jsonName == type }
            ?.let { GroupId(it, request, entry) to match.groupValues.last() }
    }

    /**
     * Item key from the trimmed [originalName] and the number of earlier items with the same
     * trimmed name ([occurrence]).
     */
    fun itemKey(originalName: String, occurrence: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(originalName.trim().toByteArray())
        return "$occurrence-" +
            digest.joinToString("") { "%02x".format(Locale.ROOT, it) }.take(KEY_HASH_CHARS)
    }
}
