package dev.playday3008.llmhealthbridge.entry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonExtractionTest {
    @Test fun rawObject() = assertEquals("""{"items":[]}""", extractJsonObject("""{"items":[]}"""))

    @Test fun taggedFenceInProse() = assertEquals(
        """{"a":1}""",
        extractJsonObject("Note.\n\n```json\n{\"a\":1}\n```\n\nSave to Health Connect")
    )

    @Test fun taggedFenceWinsOverEarlierBraces() = assertEquals(
        """{"a":2}""",
        extractJsonObject("Use {braces} freely.\n```json\n{\"a\":2}\n```")
    )

    @Test fun untaggedFence() = assertEquals(
        """{"a":3}""",
        extractJsonObject("x\n```\n{\"a\":3}\n```")
    )

    @Test fun inlineInProseLikeGemini() = assertEquals(
        """{"meal_type":"lunch","items":[{"name":"Rice","energy_kcal":500}]}""",
        extractJsonObject(
            "Here is the breakdown:\n" +
                "{\"meal_type\":\"lunch\"," +
                "\"items\":[{\"name\":\"Rice\",\"energy_kcal\":500}]}\n\n" +
                "Save to Health Connect\nthen https://example.com"
        )
    )

    @Test fun bracesInsideStrings() = assertEquals(
        """{"name":"a } b { c","x":1}""",
        extractJsonObject("""pre {"name":"a } b { c","x":1} post""")
    )

    @Test fun noJson() = assertNull(extractJsonObject("I cannot see a photo."))

    @Test fun unclosedObjectIsReturnedAsIs() {
        val json = extractJsonObject("""{"a":1""")
        assertEquals("""{"a":1""", json)
        assertFalse(isClosedObject(checkNotNull(json)))
    }

    // A cut-off reply must not shrink to its first complete inner entry.
    @Test fun unclosedOuterObjectIsNotReplacedByAnInnerOne() {
        val json = checkNotNull(
            extractJsonObject("```json\n{\"entries\":[{\"type\":\"hydration\"},{\"type\":\"bo\n```")
        )
        assertTrue(json.startsWith("{\"entries\""))
        assertFalse(isClosedObject(json))
    }

    @Test fun closedObjectIsClosed() = assertTrue(isClosedObject("""{"a":{"b":"}"}}"""))

    @Test fun replyCopiedFromTheMiddleIsAnArrayElement() =
        assertTrue(isArrayElement("""{"id":"e2","volume_ml":500},{"id":"e3","weight_kg":82}]}"""))

    @Test fun lastEntryCopiedAloneIsAnArrayElement() =
        assertTrue(isArrayElement("""{"id":"e3","type":"body","weight_kg":82}]}"""))

    @Test fun bareArrayInAFenceIsAnArrayElement() = assertTrue(
        isArrayElement("Note.\n```json\n[{\"type\":\"body\"}, {\"type\":\"vitals\"}]\n```")
    )

    @Test fun wholeReplyIsNotAnArrayElement() {
        assertFalse(
            isArrayElement("Note, done.\n```json\n{\"entries\":[{\"type\":\"body\"}]}\n```\n, ok")
        )
        assertFalse(isArrayElement("""{"type":"body","weight_kg":82}"""))
    }

    @Test fun cutOffObjectIsNotAnArrayElement() = assertFalse(isArrayElement("""[{"type":"body""""))
}
