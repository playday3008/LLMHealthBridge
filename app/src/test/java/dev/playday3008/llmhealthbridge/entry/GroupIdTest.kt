package dev.playday3008.llmhealthbridge.entry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

import java.time.Duration
import java.time.Instant

class GroupIdTest {
    private val request = "mvdrjvpc-7f2a"

    @Test fun formatIsVersioned() = assertEquals(
        "llmhb3/body/mvdrjvpc-7f2a/e1/weight",
        RecordIds.clientRecordId(GroupId(EntryType.BODY, request, "e1"), "weight")
    )

    @Test fun parsesBack() {
        val id = GroupId(EntryType.NUTRITION, request, "~2")
        val key = RecordIds.itemKey("Rice", 0)
        assertEquals(id to key, RecordIds.parse(RecordIds.clientRecordId(id, key)))
    }

    @Test fun rejectsOldPrefixAndForeignIds() {
        assertNull(RecordIds.parse("llmhb2/body/1760787120000-0/weight"))
        assertNull(RecordIds.parse("llmhb3/sleep/mvdrjvpc-7f2a/e1/x"))
        assertNull(RecordIds.parse("llmhb3/body/not-a-request/e1/weight"))
        assertNull(RecordIds.parse("some-other-app-id"))
    }

    @Test fun requestEncodesItsSendTime() {
        val sent = Instant.parse("2026-10-18T11:50:00Z")
        val request = RequestIds.create(sent, Random(0))
        assertEquals(request, RequestIds.normalize(request))
        assertEquals(sent, RequestIds.sentAt(request))
        assertEquals(sent.minus(Duration.ofDays(30)), RequestIds.earliest(request))
    }

    @Test fun normalizeRejectsWhatIsNotARequest() {
        assertNull(RequestIds.normalize("e1"))
        assertNull(RequestIds.normalize("mvdrjvpc-7f2"))
        assertNull(RequestIds.normalize("zzzzzzzzzzzzz-0000"))
    }

    @Test fun fallbackEntryIdsNeverLookLikeLlmIds() {
        val fallback = RequestIds.fallbackEntryId("{}", 0)
        assertTrue(RequestIds.isEntryId("e1"))
        assertFalse(RequestIds.isEntryId(fallback))
        assertTrue(RequestIds.isFallbackEntryId(fallback))
        assertFalse(RequestIds.isFallbackEntryId("e1"))
    }

    @Test fun fallbackEntryIdsFitClientRecordIds() {
        val id = GroupId(EntryType.BODY, request, RequestIds.fallbackEntryId("{}", 99))
        assertEquals(id to "weight", RecordIds.parse(RecordIds.clientRecordId(id, "weight")))
    }

    @Test fun fallbackEntryIdsDependOnTheReply() {
        val first = RequestIds.fallbackEntryId("""{"a":1}""", 0)
        assertEquals(first, RequestIds.fallbackEntryId("""{"a":1}""", 0))
        assertNotEquals(first, RequestIds.fallbackEntryId("""{"a":2}""", 0))
        assertNotEquals(first, RequestIds.fallbackEntryId("""{"a":1}""", 1))
    }

    @Test fun entryTypeIsCaseInsensitive() =
        assertEquals(EntryType.VITALS, EntryType.fromJson(" Vitals "))

    @Test fun itemKeyIgnoresSurroundingWhitespace() = assertEquals(
        RecordIds.itemKey("Rice", 0),
        RecordIds.itemKey("  Rice ", 0)
    )
}
