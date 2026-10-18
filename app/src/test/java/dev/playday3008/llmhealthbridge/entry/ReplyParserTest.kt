package dev.playday3008.llmhealthbridge.entry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.EntryType.BODY
import dev.playday3008.llmhealthbridge.entry.EntryType.HYDRATION
import dev.playday3008.llmhealthbridge.entry.EntryType.NUTRITION
import dev.playday3008.llmhealthbridge.entry.EntryType.VITALS
import dev.playday3008.llmhealthbridge.nutrition.Meal

private val NOW = Instant.parse("2026-10-18T12:00:00Z")
private val ZONE = ZoneId.of("Europe/Warsaw")
private val REQ = RequestIds.create(NOW.minusSeconds(600), Random(0))
private val FALLBACK = RequestIds.create(NOW, Random(1))

private const val OLD_MEAL = """{"meal_type":"lunch","items":[{"name":"Rice","energy_kcal":260}]}"""
private const val NO_REQUEST =
    "request: missing or not from this app, so corrections of this reply are saved as new entries"

private fun reply(vararg entries: String) =
    """{"request":"$REQ","entries":[${entries.joinToString(",")}]}"""

private val MIXED = reply(
    """{"id":"e1","type":"nutrition","time":"2026-10-18T13:00:00+02:00","items":[{"name":"Rice","energy_kcal":260}]}""",
    """{"id":"e2","type":"hydration","time":"2026-10-18T13:05:00+02:00","volume_ml":300}""",
    """{"id":"e3","type":"body","time":"2026-10-18T07:12:00+02:00","weight_kg":82.4}""",
    """{"id":"e4","type":"vitals","time":"2026-10-18T08:00:00+02:00","heart_rate_bpm":64}"""
)

private fun parse(text: String) = parseReply(text, NOW, ZONE, FALLBACK)

private fun parsed(text: String) = parse(text) as ReplyResult.Parsed

class ReplyParserTest {
    @Test fun mixedReply() {
        val r = parsed(MIXED)
        assertEquals(listOf(NUTRITION, HYDRATION, BODY, VITALS), r.groups.map { it.id.type })
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun idsComeFromTheRequestAndEachEntry() = assertEquals(
        listOf(
            GroupId(NUTRITION, REQ, "e1"),
            GroupId(HYDRATION, REQ, "e2"),
            GroupId(BODY, REQ, "e3"),
            GroupId(VITALS, REQ, "e4")
        ),
        parsed(MIXED).groups.map { it.id }
    )

    @Test fun aChangedTimeKeepsTheIdentity() {
        val before =
            parsed(
                reply(
                    """{"id":"e1","type":"body","time":"2026-10-18T07:00:00+02:00","weight_kg":82}"""
                )
            )
        val after =
            parsed(
                reply(
                    """{"id":"e1","type":"body","time":"2026-10-18T08:00:00+02:00","weight_kg":82}"""
                )
            )
        assertEquals(before.groups.single().id, after.groups.single().id)
    }

    @Test fun droppingAnEntryKeepsTheOthersIds() {
        val water = """{"id":"e2","type":"hydration","volume_ml":500}"""
        val both = parsed(reply("""{"id":"e1","type":"hydration","volume_ml":330}""", water))
        val corrected = parsed(reply(water))
        assertEquals(both.groups[1].id, corrected.groups.single().id)
    }

    @Test fun requestIsTrimmedAndLowercased() {
        val text = """{"request":" ${REQ.uppercase()} ",""" +
            """"entries":[{"id":"e1","type":"body","weight_kg":82}]}"""
        assertEquals(REQ, parsed(text).groups.single().id.request)
    }

    @Test fun missingRequestFallsBackWithAWarning() {
        val r = parsed("""{"entries":[{"id":"e1","type":"body","weight_kg":82}]}""")
        assertEquals(FALLBACK, r.groups.single().id.request)
        assertEquals(listOf(NO_REQUEST), r.warnings)
    }

    @Test fun requestFromTheFutureIsNotTrusted() {
        val future = RequestIds.create(NOW.plus(Duration.ofDays(2)), Random(2))
        val r =
            parsed("""{"request":"$future","entries":[{"id":"e1","type":"body","weight_kg":82}]}""")
        assertEquals(FALLBACK, r.groups.single().id.request)
    }

    // The phone clock may have been set back a little between send and paste.
    @Test fun requestSlightlyInTheFutureIsKept() {
        val ahead = RequestIds.create(NOW.plusSeconds(3600), Random(2))
        val r =
            parsed("""{"request":"$ahead","entries":[{"id":"e1","type":"body","weight_kg":82}]}""")
        assertEquals(ahead, r.groups.single().id.request)
    }

    @Test fun missingIdFallsBackWithAWarning() {
        val text = reply("""{"type":"body","weight_kg":82}""", """{"type":"body","weight_kg":83}""")
        val r = parsed(text)
        assertEquals(
            listOf(RequestIds.fallbackEntryId(text, 0), RequestIds.fallbackEntryId(text, 1)),
            r.groups.map { it.id.entry }
        )
        assertEquals(
            listOf(
                "entries[0]: no id, so it is saved as a new entry",
                "entries[1]: no id, so it is saved as a new entry"
            ),
            r.warnings
        )
    }

    @Test fun duplicateIdFallsBackForTheLaterEntry() {
        val text = reply(
            """{"id":"e1","type":"body","weight_kg":82}""",
            """{"id":"e1","type":"vitals","heart_rate_bpm":60}"""
        )
        val r = parsed(text)
        assertEquals(
            listOf("e1", RequestIds.fallbackEntryId(text, 1)),
            r.groups.map { it.id.entry }
        )
        assertEquals(
            listOf("entries[1]: id e1 is used twice, so it is saved as a new entry"),
            r.warnings
        )
    }

    @Test fun unusableIdFallsBack() {
        val r = parsed(reply("""{"id":"e/1","type":"body","weight_kg":82}"""))
        assertTrue(RequestIds.isFallbackEntryId(r.groups.single().id.entry))
        assertEquals(
            listOf(
                "entries[0]: id must be up to 32 letters, digits, - or _, so it is saved as a new entry"
            ),
            r.warnings
        )
    }

    @Test fun numericIdIsUsedAsText() = assertEquals(
        "7",
        parsed(reply("""{"id":7,"type":"body","weight_kg":82}""")).groups.single().id.entry
    )

    @Test fun nullIdAndRequestCountAsMissing() {
        val text = """{"request":null,"entries":[{"id":null,"type":"body","weight_kg":82}]}"""
        assertEquals(
            GroupId(BODY, FALLBACK, RequestIds.fallbackEntryId(text, 0)),
            parsed(text).groups.single().id
        )
    }

    @Test fun idlessRepliesOfOneRequestDoNotShareIds() {
        val meal =
            parsed(reply("""{"type":"nutrition","items":[{"name":"Soup","energy_kcal":200}]}"""))
        val snack =
            parsed(reply("""{"type":"nutrition","items":[{"name":"Apple","energy_kcal":80}]}"""))
        assertNotEquals(meal.groups.single().id, snack.groups.single().id)
    }

    @Test fun idlessReplyPastedAgainKeepsItsIds() {
        val text = reply("""{"type":"hydration","volume_ml":250}""")
        assertEquals(parsed(text).groups.single().id, parsed(text).groups.single().id)
    }

    @Test fun bareMealStillParses() = assertTrue(parsed(OLD_MEAL).groups.single() is Meal)

    @Test fun bareEntryTakesItsRequestAndId() {
        val r = parsed("""{"request":"$REQ","id":"e1","type":"body","weight_kg":82}""")
        assertEquals(GroupId(BODY, REQ, "e1"), r.groups.single().id)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun samePasteSameIds() = assertEquals(
        parsed(MIXED).groups.map { it.id },
        (parseReply(MIXED, NOW.plusSeconds(3600), ZONE) as ReplyResult.Parsed).groups.map { it.id }
    )

    @Test fun cutOffReplyBlocks() {
        val text = "```json\n" + reply(
            """{"id":"e1","type":"hydration","volume_ml":500}""",
            """{"id":"e2","type":"body","weight_kg":82}"""
        ).dropLast(4) + "\n```"
        val r = parse(text) as ReplyResult.Blocking
        assertEquals(
            listOf("The reply looks cut off: its JSON never closes. Copy the whole reply."),
            r.errors
        )
    }

    @Test fun replyCopiedFromTheMiddleBlocks() {
        val r =
            parse(
                """{"id":"e2","type":"hydration","volume_ml":500},{"id":"e3","type":"body","weight_kg":82}]}"""
            )
        assertEquals(
            listOf(
                "The reply looks partly copied: its JSON is one element of a list. Copy the whole code block."
            ),
            (r as ReplyResult.Blocking).errors
        )
    }

    @Test fun replyCopiedFromTheMiddleIsNotOffered() = assertFalse(
        looksLikeReply("""{"id":"e2","type":"hydration","volume_ml":500},{"id":"e3"}]}""")
    )

    @Test fun cutOffReplyIsNotOffered() = assertFalse(
        looksLikeReply("""{"entries":[{"type":"hydration","volume_ml":500},{"type":"body"""")
    )

    @Test fun unknownEntryTypeIsIgnored() {
        val r =
            parsed(
                reply(
                    """{"id":"e1","type":"sleep","hours":7}""",
                    """{"id":"e2","type":"body","weight_kg":82}"""
                )
            )
        assertEquals(1, r.groups.size)
        assertEquals("entries[0]: unsupported type sleep, ignored", r.warnings.single())
    }

    @Test fun invalidEntryIsIgnoredWithItsErrors() {
        val r =
            parsed(
                reply("""{"id":"e1","items":[]}""", """{"id":"e2","type":"body","weight_kg":82}""")
            )
        assertEquals(listOf(BODY), r.groups.map { it.id.type })
        assertEquals(listOf("entries[0]: items: must be a non-empty array, ignored"), r.warnings)
    }

    @Test fun nonObjectEntryIsIgnored() = assertEquals(
        listOf("entries[0]: must be an object, ignored"),
        parsed(reply("5", """{"id":"e1","type":"body","weight_kg":82}""")).warnings
    )

    @Test fun unknownTopLevelKeyWarns() {
        val r =
            parsed(
                """{"note":"x","request":"$REQ","entries":[{"id":"e1","type":"body","weight_kg":82}]}"""
            )
        assertEquals(listOf("Ignored unknown key: note"), r.warnings)
    }

    @Test fun nothingUsableBlocksWithCollectedErrors() {
        val json = reply("""{"id":"e1","type":"sleep"}""")
        assertEquals(
            ReplyResult.Blocking(listOf("entries[0]: unsupported type sleep, ignored"), json),
            parse(json)
        )
    }

    @Test fun emptyEntriesBlocks() = assertTrue(parse("""{"entries":[]}""") is ReplyResult.Blocking)

    @Test fun entriesNotAnArrayBlocks() =
        assertTrue(parse("""{"entries":{"type":"body"}}""") is ReplyResult.Blocking)

    @Test fun bareInvalidMeasurementBlocksWithOwnErrors() {
        val r = parse("""{"type":"body","note":"x"}""") as ReplyResult.Blocking
        assertEquals(listOf("no measurement values"), r.errors)
    }

    @Test fun measurementOnlyReplyLooksLikeReply() =
        assertTrue(looksLikeReply("""{"entries":[{"type":"body","weight_kg":82}]}"""))
}
