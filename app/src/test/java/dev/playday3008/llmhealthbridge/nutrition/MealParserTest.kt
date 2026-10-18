package dev.playday3008.llmhealthbridge.nutrition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.entry.ReplyResult
import dev.playday3008.llmhealthbridge.entry.looksLikeReply
import dev.playday3008.llmhealthbridge.entry.parseReply

private val now = Instant.parse("2026-10-18T12:00:00Z")
private val zone = ZoneId.of("Europe/Warsaw")

class MealParserTest {
    private fun meal(text: String) =
        (parseReply(text, now, zone) as ReplyResult.Parsed).groups.single() as Meal

    @Test fun fullReply() {
        val m = meal(
            "```json\n{\"meal_type\":\"lunch\",\"time\":\"2026-10-18T13:30:00+02:00\",\"items\":" +
                "[{\"name\":\"Rice\",\"energy_kcal\":260,\"protein_g\":5,\"carbs_g\":57,\"fat_g\":0.5}]}\n```"
        )
        assertEquals(MealType.LUNCH, m.mealType)
        assertEquals(OffsetDateTime.parse("2026-10-18T13:30:00+02:00"), m.time)
        assertEquals(260.0, m.items.single().nutrients["energy_kcal"])
        assertEquals(emptyList<Issue>(), m.issues(now))
    }

    @Test fun stringNumberIsFixableIssue() {
        val item = meal("""{"items":[{"name":"Rice","energy_kcal":"260"}]}""").items.single()
        assertEquals(listOf(Issue("energy_kcal", "must be a number >= 0")), item.issues())
        assertNull(item.base["energy_kcal"])
    }

    @Test fun negativeValueIsFixable() = assertEquals(
        listOf(Issue("protein_g", "must be a number >= 0")),
        meal("""{"items":[{"name":"A","energy_kcal":1,"protein_g":-2}]}""").items.single().issues()
    )

    @Test fun missingEnergyIsFixable() = assertEquals(
        listOf(Issue("energy_kcal", "required")),
        meal("""{"items":[{"name":"A"}]}""").items.single().issues()
    )

    @Test fun futureTimeIsFixable() = assertEquals(
        listOf(Issue("time", "in the future")),
        meal(
            """{"time":"2026-10-18T15:00:00+02:00","items":[{"name":"A","energy_kcal":1}]}"""
        ).issues(now)
    )

    @Test fun absentTimeIsNowInZone() = assertEquals(
        now.atZone(zone).toOffsetDateTime(),
        meal("""{"items":[{"name":"A","energy_kcal":1}]}""").time
    )

    @Test fun unknownMealTypeIsFixable() {
        val m = meal("""{"meal_type":"brunch","items":[{"name":"A","energy_kcal":1}]}""")
        assertEquals(MealType.UNKNOWN, m.mealType)
        assertEquals(listOf(Issue("meal_type", "unknown value: brunch")), m.issues(now))
    }

    @Test fun unknownKeysWarnLikeCopilotTotal() {
        val m =
            meal(
                """{"items":[{"name":"A","energy_kcal":1,"note":"x"}],"total":{"energy_kcal":1}}"""
            )
        assertTrue("Ignored unknown key: total" in m.warnings)
        assertTrue("Ignored unknown key: items[0].note" in m.warnings)
    }

    @Test fun nullsCountAsNotGiven() {
        val m = meal(
            """{"meal_type":null,"time":null,"items":[{"name":"A","energy_kcal":1,"fiber_g":null}]}"""
        )
        assertEquals(MealType.UNKNOWN, m.mealType)
        assertEquals(emptyList(), m.issues(now) + m.items.single().issues())
        assertEquals(emptyList(), m.warnings)
        assertEquals(setOf("energy_kcal"), m.items.single().base.keys)
    }

    @Test fun measurementKeyInAMealSaysWhereItBelongs() = assertTrue(
        "volume_ml belongs in a hydration entry, not saved" in
            meal("""{"items":[{"name":"Cola","energy_kcal":140}],"volume_ml":330}""").warnings
    )

    @Test fun measurementKeyInAnItemSaysWhereItBelongs() = assertTrue(
        "items[0].volume_ml belongs in a hydration entry, not saved" in
            meal("""{"items":[{"name":"Cola","energy_kcal":140,"volume_ml":330}]}""").warnings
    )

    @Test fun nutrientOutsideAnItemSaysWhereItBelongs() = assertTrue(
        "fat_g belongs in an item of a nutrition entry, not saved" in
            meal("""{"items":[{"name":"Cola","energy_kcal":140}],"fat_g":0}""").warnings
    )

    @Test fun sameNameItemsGetDistinctKeys() {
        val keys = meal(
            """{"items":[{"name":"Rice","energy_kcal":1},{"name":"Rice","energy_kcal":2}]}"""
        )
            .items.map { it.key }
        assertEquals(listOf(RecordIds.itemKey("Rice", 0), RecordIds.itemKey("Rice", 1)), keys)
    }

    @Test fun blockingCases() {
        assertEquals(
            ReplyResult.Blocking(listOf("No JSON found"), null),
            parseReply("no json here", now, zone)
        )
        assertTrue(
            (parseReply("""{"items":[]}""", now, zone) as ReplyResult.Blocking)
                .errors.contains("items: must be a non-empty array")
        )
        assertTrue(
            (parseReply("""{"type":"weight","items":[1]}""", now, zone) as ReplyResult.Blocking)
                .errors.contains("type: unsupported value: weight")
        )
    }

    @Test fun blockingResultsCarryTheJsonSnippet() {
        val badType = """{"type":"weight","items":[1]}"""
        assertEquals(badType, (parseReply(badType, now, zone) as ReplyResult.Blocking).snippet)
        val noItems = """{"items":[]}"""
        assertEquals(noItems, (parseReply(noItems, now, zone) as ReplyResult.Blocking).snippet)
    }

    @Test fun looksLikeReplyNeedsEntriesItemsOrType() {
        assertTrue(looksLikeReply("```json\n{\"items\":[]}\n```"))
        assertTrue(looksLikeReply("""{"type":"body","weight_kg":82}"""))
        assertFalse(looksLikeReply("""{"meal_type":"lunch"}"""))
    }
}
