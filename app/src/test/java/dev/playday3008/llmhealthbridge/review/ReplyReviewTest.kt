package dev.playday3008.llmhealthbridge.review

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import java.time.Instant
import java.time.OffsetDateTime

import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RequestIds
import dev.playday3008.llmhealthbridge.healthconnect.writePermissions
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.measurement.MeasurementTypes
import dev.playday3008.llmhealthbridge.measurement.MeasurementValue
import dev.playday3008.llmhealthbridge.nutrition.Issue
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.nutrition.MealItem
import dev.playday3008.llmhealthbridge.nutrition.MealType

private const val REQUEST = "mvdrjvpc-7f2a"

class ReplyReviewTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val time = OffsetDateTime.parse("2026-10-18T10:00:00+02:00")
    private val weightPermission = HealthPermission.getWritePermission(WeightRecord::class)
    private val fatPermission = HealthPermission.getWritePermission(BodyFatRecord::class)
    private val nutritionPermission = HealthPermission.getWritePermission(NutritionRecord::class)

    private fun meal(entry: String, vararg keys: String) = MealReview(
        Meal(
            GroupId(EntryType.NUTRITION, REQUEST, entry),
            MealType.LUNCH,
            null,
            time,
            keys.map { MealItem(it, it, mapOf("energy_kcal" to 100.0)) },
            emptyList()
        )
    )

    private fun value(typeKey: String, vararg fields: Pair<String, Double>) =
        MeasurementValue(checkNotNull(MeasurementTypes.byKey(typeKey)), fields.toMap(), emptyMap())

    private fun measurement(type: EntryType, vararg values: MeasurementValue) = MeasurementReview(
        MeasurementEntry(GroupId(type, REQUEST, type.jsonName), time, values.toList(), emptyList())
    )

    private fun reply(vararg entries: ReviewEntry, reopened: Boolean = false) =
        ReplyReview.of(entries.toList(), emptyList(), reopened)

    @Test
    fun removedEntryIsSkipped() {
        val body = measurement(EntryType.BODY, value("weight", "weight" to 70.0))
        val r = reply(meal("e1", "a", "b"), body)
        assertEquals(3, r.recordCount())
        assertEquals(2, r.remove(body.id).recordCount())
        assertEquals(1, r.remove(body.id).entries.size)
    }

    @Test
    fun emptiedEntryDoesNotBlockFreshReply() {
        val future = meal("e1", "a").let {
            it.copy(meal = it.meal.copy(time = OffsetDateTime.parse("2027-01-01T00:00:00Z")))
        }
        val body = measurement(EntryType.BODY, value("weight", "weight" to 70.0))
        val withIssue = reply(future, body)
        assertFalse(withIssue.canSave(now))
        val r = withIssue.update(future.id) { (it as MealReview).toggle("a") }
        assertTrue(r.blockingIssues(now).isEmpty())
        assertTrue(r.canSave(now))
        assertEquals(1, r.recordCount())
        assertEquals(setOf(weightPermission), r.requiredPermissions())
    }

    @Test
    fun emptiedReopenedEntryCannotSave() {
        val m = meal("e1", "a")
        val r = reply(m, reopened = true).update(m.id) { (it as MealReview).toggle("a") }
        assertFalse(r.canSave(now))
        val body = measurement(EntryType.BODY, value("weight", "weight" to 70.0))
        val b = reply(body, reopened = true).update(body.id) {
            (it as MeasurementReview).removeValue("weight")
        }
        assertFalse(b.canSave(now))
    }

    @Test
    fun deniedRowsDoNotCountOrBlock() {
        val body = measurement(
            EntryType.BODY,
            value("weight", "weight" to 70.0),
            value(
                "body_fat",
                "body_fat" to 20.0
            )
        )
            .withValue("weight", "weight", null)
        val r = reply(body)
        assertEquals(listOf(Issue("Weight: weight", "must be a number")), r.blockingIssues(now))
        assertFalse(r.canSave(now))
        val denied = r.withDenied(setOf(weightPermission))
        assertEquals(1, denied.recordCount())
        assertTrue(denied.blockingIssues(now).isEmpty())
        assertTrue(denied.canSave(now))
        assertTrue(denied.isDenied(weightPermission))
        assertFalse(denied.isDenied(fatPermission))
    }

    @Test
    fun grantingClearsDenial() {
        val r = reply(meal("e1", "a")).withDenied(setOf(weightPermission, fatPermission))
        assertEquals(setOf(fatPermission), r.withGranted(setOf(weightPermission)).denied)
        assertTrue(r.withGranted(setOf(weightPermission, fatPermission)).denied.isEmpty())
    }

    @Test
    fun deniedThenGrantedCountsAgain() {
        val r = reply(meal("e1", "a"))
        assertEquals(setOf(nutritionPermission), r.missingPermissions(emptySet()))
        assertTrue(r.missingPermissions(setOf(nutritionPermission)).isEmpty())
        val denied = r.withDenied(setOf(nutritionPermission))
        assertTrue(denied.missingPermissions(emptySet()).isEmpty())
        assertEquals(0, denied.recordCount())
        assertFalse(denied.canSave(now))
        val granted = denied.withGranted(setOf(nutritionPermission))
        assertTrue(granted.denied.isEmpty())
        assertEquals(1, granted.recordCount())
        assertTrue(granted.canSave(now))
    }

    @Test
    fun requiredPermissionsCoverEveryWrittenType() {
        val body = measurement(
            EntryType.BODY,
            value("weight", "weight" to 70.0),
            value(
                "body_fat",
                "body_fat" to 20.0
            )
        )
        val r = reply(meal("e1", "a"), body).withDenied(setOf(weightPermission))
        assertEquals(
            writePermissions(EntryType.NUTRITION) + weightPermission + fatPermission,
            r.requiredPermissions()
        )
    }

    @Test
    fun entriesOrderedBySection() {
        val vitals = measurement(EntryType.VITALS, value("heart_rate", "heart_rate" to 60.0))
        val body = measurement(EntryType.BODY, value("weight", "weight" to 70.0))
        val m = meal("e1", "a")
        val r = reply(vitals, body, m)
        assertEquals(listOf(m.id, body.id, vitals.id), r.entries.map { it.id })
        val water = measurement(EntryType.HYDRATION, value("hydration", "volume" to 250.0))
        val sameSection = reply(water, m, vitals)
        assertEquals(listOf(water.id, m.id, vitals.id), sameSection.entries.map { it.id })
        val updated = r.update(m.id) { it }
        assertEquals(r.entries.map { it.id }, updated.entries.map { it.id })
    }

    @Test
    fun measurementEditsMarkInvalidLikeTheParser() {
        val e = measurement(EntryType.VITALS, value("heart_rate", "heart_rate" to 60.0))
        assertEquals(
            mapOf("heart_rate" to "must be a whole number"),
            e.withValue("heart_rate", "heart_rate", 60.5).values[0].invalid
        )
        assertEquals(
            mapOf("heart_rate" to "must be a number >= 0"),
            e.withValue("heart_rate", "heart_rate", -1.0).values[0].invalid
        )
        assertEquals(
            70.0,
            e.withValue("heart_rate", "heart_rate", 70.0).values[0].values["heart_rate"]
        )
    }

    private val MeasurementReview.values get() = entry.values

    @Test fun savedGroupsWithAnEntrysIdAreReplaced() {
        val m = meal("e1", "a")
        val r = reply(m).withSaved(listOf(m.meal))
        assertEquals(mapOf(m.id to m.meal), r.replaces)
        assertEquals(emptyList(), r.leftovers)
    }

    // A correction resends the full list: an entry it repeats proves the rest were dropped on purpose.
    @Test fun leftoversOfAResendAreRemoved() {
        val cola = meal("e1", "cola").meal
        val water = meal("e2", "water")
        val r = reply(water).withSaved(listOf(cola, water.meal))
        assertEquals(listOf(Leftover(cola, remove = true)), r.leftovers)
        assertEquals(listOf(cola), r.removals())
    }

    // A reply with only new ids adds to the conversation; earlier entries stay unless ticked.
    @Test fun leftoversOfAnAdditionAreKept() {
        val earlier = meal("e1", "rice").meal
        val r = reply(meal("e2", "snack")).withSaved(listOf(earlier))
        assertEquals(listOf(Leftover(earlier, remove = false)), r.leftovers)
        assertEquals(emptyList(), r.removals())
        assertEquals(listOf(earlier), r.toggleLeftover(earlier.id).removals())
    }

    @Test fun anEntryThatChangedTypeLeavesItsOldTypeForRemoval() {
        val oldBody = measurement(EntryType.BODY, value("weight", "weight" to 70.0)).entry
            .let { it.copy(id = it.id.copy(entry = "e1")) }
        val nowVitals = measurement(EntryType.VITALS, value("heart_rate", "heart_rate" to 60.0))
            .let { it.copy(entry = it.entry.copy(id = it.entry.id.copy(entry = "e1"))) }
        val r = reply(nowVitals).withSaved(listOf(oldBody))
        assertEquals(listOf(oldBody), r.removals())
    }

    // Only the types a leftover holds: they were readable, so their permission is already granted.
    @Test fun removalsNeedThePermissionsOfTheTypesTheyHold() {
        val body = measurement(EntryType.BODY, value("weight", "weight" to 70.0)).entry
        val m = meal("e1", "a")
        val r = reply(m).withSaved(listOf(m.meal, body))
        assertEquals(setOf(nutritionPermission, weightPermission), r.requiredPermissions())
        assertEquals(setOf(nutritionPermission), r.toggleLeftover(body.id).requiredPermissions())
    }

    // Ids the app made up for an id-less reply say nothing about what the LLM resent.
    @Test fun fallbackIdsDoNotMakeAResend() {
        val fallback = RequestIds.fallbackEntryId("{}", 0)
        val earlier = meal("e1", "rice").meal
        val saved = meal(fallback, "soup")
        val r = reply(saved).withSaved(listOf(earlier, saved.meal))
        assertEquals(listOf(Leftover(earlier, remove = false)), r.leftovers)
    }

    @Test fun removingAnEntryKeepsWhatItWouldReplaceAsALeftover() {
        val m = meal("e1", "a")
        val water = meal("e2", "water")
        val r = reply(m, water).withSaved(listOf(m.meal)).remove(m.id)
        assertEquals(emptyMap(), r.replaces)
        assertEquals(listOf(Leftover(m.meal, remove = false)), r.leftovers)
        assertEquals(listOf(m.meal), r.toggleLeftover(m.id).removals())
    }

    // Only what was deleted goes: a leftover ticked while the save ran stays listed.
    @Test fun onlyDeletedLeftoversAreDropped() {
        val a = meal("e1", "a").meal
        val b = meal("e2", "b").meal
        val c = meal("e3", "c").meal
        val r = reply(meal("e4", "d")).withSaved(listOf(a, b, c))
            .toggleLeftover(a.id)
            .toggleLeftover(b.id)
        assertEquals(
            listOf(Leftover(b, remove = true), Leftover(c, remove = false)),
            r.withoutLeftovers(listOf(a)).leftovers
        )
    }

    @Test fun aLookupAgainKeepsTheUsersMarks() {
        val cola = meal("e1", "cola").meal
        val water = meal("e2", "water")
        val r = reply(water).withSaved(listOf(cola, water.meal)).toggleLeftover(cola.id)
        assertEquals(
            listOf(Leftover(cola, remove = false)),
            r.withSaved(listOf(cola, water.meal)).leftovers
        )
    }

    // Removed before the lookup ended: the group it would have replaced stays unmarked.
    @Test fun anEntryRemovedDuringTheLookupKeepsItsSavedGroup() {
        val m = meal("e1", "a")
        val other = meal("e2", "b")
        val r = reply(m, other).copy(lookup = SavedLookup.PENDING).remove(m.id)
            .withSaved(listOf(m.meal, other.meal))
        assertEquals(listOf(Leftover(m.meal, remove = false)), r.leftovers)
    }

    // Unticking every item of an entry already saved deletes it, as unticking one item deletes that item.
    @Test fun anEmptiedEntryDeletesWhatItReplaces() {
        val m = meal("e1", "a")
        val r = reply(m).withSaved(listOf(m.meal)).update(m.id) { (it as MealReview).toggle("a") }
        assertEquals(listOf(m.meal), r.removals())
        assertTrue(r.canSave(now))
        assertEquals(0, r.recordCount())
        assertEquals(emptyMap(), r.withoutLeftovers(listOf(m.meal)).replaces)
    }

    @Test fun anEmptiedNewEntryDeletesNothing() {
        val m = meal("e1", "a")
        val r = reply(m).update(m.id) { (it as MealReview).toggle("a") }
        assertEquals(emptyList(), r.removals())
        assertFalse(r.canSave(now))
    }

    @Test fun writtenEntriesReplaceWhatTheyWrote() {
        val m = meal("e1", "a", "b")
        val r = reply(m, meal("e2", "c")).update(m.id) { (it as MealReview).toggle("b") }
            .withWritten(listOf(m.id))
        assertEquals(mapOf(m.id to m.meal.copy(items = m.meal.items.take(1))), r.replaces)
    }

    // A deletion whose type was refused cannot go ahead, so it does not make the review savable.
    @Test fun refusedDeletionsDoNotCount() {
        val m = meal("e1", "a")
        val r = reply(m).withSaved(listOf(m.meal)).update(m.id) { (it as MealReview).toggle("a") }
            .withDenied(setOf(nutritionPermission))
        assertEquals(emptyList(), r.allowedRemovals())
        assertFalse(r.canSave(now))
    }

    // Deleting goes ahead through the types still allowed, as removing leftovers does.
    @Test fun aDeletionWithOneTypeRefusedStillCounts() {
        val body = measurement(
            EntryType.BODY,
            value("weight", "weight" to 70.0),
            value(
                "body_fat",
                "body_fat" to 20.0
            )
        ).entry
        val r = reply(meal("e9", "x")).withSaved(listOf(body)).toggleLeftover(body.id)
            .withDenied(setOf(fatPermission))
        assertEquals(listOf(body), r.allowedRemovals())
    }

    @Test fun savingWaitsForTheLookup() {
        val m = meal("e1", "a")
        val pending = reply(m).copy(lookup = SavedLookup.PENDING)
        assertFalse(pending.canSave(now))
        assertTrue(pending.withSaved(emptyList()).canSave(now))
        assertTrue(pending.copy(lookup = SavedLookup.FAILED).canSave(now))
    }

    @Test fun lookupStateSurvivesEdits() {
        val m = meal("e1", "a", "b")
        val r = reply(m).copy(lookup = SavedLookup.PENDING).update(m.id) {
            (it as MealReview).toggle("a")
        }
        assertEquals(SavedLookup.PENDING, r.lookup)
    }

    @Test fun savedStateSurvivesEdits() {
        val m = meal("e1", "a", "b")
        val other = meal("e2", "c").meal
        val r = reply(m).withSaved(listOf(m.meal, other))
            .update(m.id) { (it as MealReview).toggle("a") }
        assertEquals(setOf(m.id), r.replaces.keys)
        assertEquals(listOf(other), r.removals())
    }

    @Test fun timeFarBeforeTheRequestBlocks() {
        val m = meal("e1", "a").withTime(time.minusDays(31))
        assertEquals(
            listOf(Issue("time", "more than 30 days before the request")),
            m.meal.issues(now)
        )
    }
}
