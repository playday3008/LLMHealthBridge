package dev.playday3008.llmhealthbridge.review

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

import kotlinx.coroutines.runBlocking

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.healthconnect.GroupSave
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

private const val REQUEST = "mvdrjvpc-7f2a"

class GroupWriteTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val time = OffsetDateTime.parse("2026-10-18T10:00:00+02:00")
    private val weightPermission = HealthPermission.getWritePermission(WeightRecord::class)
    private val nutritionPermission = HealthPermission.getWritePermission(NutritionRecord::class)

    private val meal = MealReview(
        Meal(
            GroupId(EntryType.NUTRITION, REQUEST, "e1"),
            MealType.LUNCH,
            null,
            time,
            listOf(MealItem("a", "Rice", mapOf("energy_kcal" to 100.0))),
            emptyList()
        )
    )

    private fun value(typeKey: String, vararg fields: Pair<String, Double>) =
        MeasurementValue(checkNotNull(MeasurementTypes.byKey(typeKey)), fields.toMap(), emptyMap())

    private fun measurement(type: EntryType, entry: String, vararg values: MeasurementValue) =
        MeasurementReview(
            MeasurementEntry(GroupId(type, REQUEST, entry), time, values.toList(), emptyList())
        )

    private val body = measurement(
        EntryType.BODY,
        "e2",
        value("weight", "weight" to 70.0),
        value("body_fat", "body_fat" to 20.0)
    )
    private val vitals = measurement(
        EntryType.VITALS,
        "e3",
        value("heart_rate", "heart_rate" to 60.0)
    )

    @Test
    fun onlyGrantedClassesAreWrittenAndUngrantedEntriesSkipped() {
        val r = ReplyReview.of(listOf(meal, body, vitals), emptyList(), false)
        val writes = r.writes(setOf(nutritionPermission, weightPermission), now)
        assertEquals(listOf(meal.id, body.id), writes.map { it.id })
        assertEquals(setOf(NutritionRecord::class), writes[0].allowedClasses)
        assertEquals(setOf(WeightRecord::class), writes[1].allowedClasses)
        val records = writes[1].build(7)
        assertEquals(listOf(WeightRecord::class), records.map { it::class })
        assertEquals(7, records.single().metadata.clientRecordVersion)
        assertEquals(1, writes[0].build(7).size)
    }

    @Test
    fun deniedMealIsNotWrittenAtAll() {
        val r = ReplyReview.of(listOf(meal, body), emptyList(), false)
        assertEquals(listOf(body.id), r.writes(setOf(weightPermission), now).map { it.id })
    }

    @Test
    fun emptiedEntriesAreSkipped() {
        val r = ReplyReview.of(
            listOf(meal.toggle("a"), body.removeValue("weight").removeValue("body_fat")),
            emptyList(),
            false
        )
        assertTrue(r.writes(setOf(nutritionPermission, weightPermission), now).isEmpty())
    }

    @Test
    fun recordProblemsCoverOnlyRowsThatWouldBeWritten() {
        val heavy = body.withValue("weight", "weight", 5_000.0)
        val huge = meal.withNutrient("a", "energy_kcal", 1e9)
        val r = ReplyReview.of(listOf(huge, heavy), emptyList(), false)
        val problems = r.writeProblems(now)
        assertEquals(setOf("a"), problems.getValue(huge.id).keys)
        assertEquals(setOf("weight"), problems.getValue(heavy.id).keys)
        assertTrue(
            r.withDenied(setOf(weightPermission, nutritionPermission)).writeProblems(now).isEmpty()
        )
        assertTrue(
            ReplyReview.of(
                listOf(huge.toggle("a")),
                emptyList(),
                false
            ).writeProblems(now).isEmpty()
        )
    }

    @Test fun saveAllSumsEveryGroup() = runBlocking {
        val writes = ReplyReview.of(listOf(meal, body), emptyList(), reopened = false)
            .writes(setOf(nutritionPermission, weightPermission), now)
        val outcome = saveAll(writes) { GroupSave(2) }
        assertEquals(4, outcome.saved)
        assertNull(outcome.failure)
    }

    @Test fun saveAllReportsRecordsSavedBeforeAFailure() = runBlocking {
        val writes = ReplyReview.of(listOf(meal, body), emptyList(), reopened = false)
            .writes(setOf(nutritionPermission, weightPermission), now)
        val boom = IllegalStateException("boom")
        val tried = mutableListOf<GroupId>()
        val outcome = saveAll(writes) {
            tried += it.id
            if (it.id == body.id) throw boom else GroupSave(3)
        }
        assertEquals(3, outcome.saved)
        assertSame(boom, outcome.failure)
        assertEquals(listOf(meal.id, body.id), tried)
        assertEquals(listOf(meal.id), outcome.written)
    }

    // The insert of a group already happened when removing its replaced records fails.
    @Test fun saveAllCountsCleanupFailuresAsSaved() = runBlocking {
        val writes = ReplyReview.of(listOf(meal, body), emptyList(), reopened = false)
            .writes(setOf(nutritionPermission, weightPermission), now)
        val outcome = saveAll(writes) {
            GroupSave(2, if (it.id == meal.id) IllegalStateException("delete") else null)
        }
        assertEquals(4, outcome.saved)
        assertNull(outcome.failure)
        assertEquals(1, outcome.cleanupFailures)
    }
}
