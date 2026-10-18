package dev.playday3008.llmhealthbridge.healthconnect

import android.content.Context
import android.content.Intent
import android.os.Build

import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

import kotlin.reflect.KClass

import kotlinx.coroutines.CancellationException

import java.time.Duration
import java.time.Instant

import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.GroupId
import dev.playday3008.llmhealthbridge.entry.RecordIds
import dev.playday3008.llmhealthbridge.entry.RequestIds

private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

// Health Connect home on Android 14+; the framework does not expose this action as public API.
private const val HEALTH_HOME_SETTINGS = "android.health.connect.action.HEALTH_HOME_SETTINGS"
private val LOOKAHEAD: Duration = Duration.ofMinutes(1)

/** Whether the Health Connect provider is usable on this device. */
enum class HcStatus { AVAILABLE, NEEDS_INSTALL_OR_UPDATE, UNAVAILABLE }

/**
 * Thin layer over Health Connect. Only this app's own records are read, filtered by
 * data origin, so no read permission is requested.
 */
@Suppress("TooManyFunctions")
class HealthConnectGateway(private val context: Context) {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** Current availability of the Health Connect provider. */
    fun status(): HcStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HcStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HcStatus.NEEDS_INSTALL_OR_UPDATE
        else -> HcStatus.UNAVAILABLE
    }

    /** Health Connect permissions currently granted to this app. */
    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Activity-result contract that requests Health Connect permissions. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /**
     * Inserts the records from [build] for group [id] in one call, then deletes this group's earlier
     * records of [allowedClasses] that the new set no longer contains. Own records of the group's
     * request window up to [now] are read to pick the version and find stale ones. A failure of
     * that deletion is reported in the result, since the insert already happened.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun saveGroup(
        id: GroupId,
        allowedClasses: Set<KClass<out Record>>,
        now: Instant,
        build: (version: Long) -> List<Record>
    ): GroupSave {
        val existing = allowedClasses.flatMap { readOwn(it, window(id.request, now)) }
        val records = build(nextVersion(existing, now))
        client.insertRecords(records)
        val keep = records.mapNotNull { it.metadata.clientRecordId }
            .mapNotNull { RecordIds.parse(it)?.second }
            .toSet()
        return try {
            deleteStale(staleClientIds(existing, id, keep, allowedClasses))
            GroupSave(records.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GroupSave(records.size, e)
        }
    }

    /** Deletes every record of group [id] whose class is in [allowedClasses]. */
    suspend fun deleteGroup(id: GroupId, allowedClasses: Set<KClass<out Record>>, now: Instant) {
        val existing = allowedClasses.flatMap { readOwn(it, window(id.request, now)) }
        deleteStale(staleClientIds(existing, id, emptySet(), allowedClasses))
    }

    /** This app's groups of [classes] with a record at or after [since], newest first. */
    suspend fun recentGroups(since: Instant, classes: Set<KClass<out Record>>): List<Group> =
        groupsFromRecords(classes.flatMap { readOwn(it, TimeRangeFilter.after(since)) })

    /** This app's saved groups of [request], read from its records of [classes] up to [now]. */
    suspend fun requestGroups(
        request: String,
        classes: Set<KClass<out Record>>,
        now: Instant
    ): List<Group> = requestGroups(request, classes.flatMap { readOwn(it, window(request, now)) })

    /**
     * Intents that open Health Connect where this app's per-type permissions can be changed, best
     * first: Health Connect home, from which App permissions is reachable, then its data management
     * screen. The MANAGE_HEALTH_PERMISSIONS action is not usable by third-party apps (it requires
     * GRANT_RUNTIME_PERMISSIONS).
     */
    fun permissionSettingsIntents(): List<Intent> = listOf(
        Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HEALTH_HOME_SETTINGS
            } else {
                HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
            }
        ),
        HealthConnectClient.getHealthConnectManageDataIntent(context)
    )

    /** Play Store listing of the Health Connect provider. */
    fun installIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri()
    ).setPackage("com.android.vending")

    /** Where every record of [request] lies, since entry times are bounded by the request. */
    private fun window(request: String, now: Instant): TimeRangeFilter =
        TimeRangeFilter.between(RequestIds.earliest(request), now.plus(LOOKAHEAD))

    private suspend fun <T : Record> readOwn(
        recordClass: KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val origin = setOf(DataOrigin(context.packageName))
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordClass,
                    timeRangeFilter = range,
                    dataOriginFilter = origin,
                    pageToken = token
                )
            )
            out += page.records
            // The Health Connect APK (Android 13 and older) marks the last page with "", not null.
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return out
    }

    private suspend fun deleteStale(stale: Map<KClass<out Record>, List<String>>) {
        stale.forEach { (recordClass, ids) ->
            client.deleteRecords(
                recordClass,
                recordIdsList = emptyList(),
                clientRecordIdsList = ids
            )
        }
    }
}
