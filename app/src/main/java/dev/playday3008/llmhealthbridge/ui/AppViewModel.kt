package dev.playday3008.llmhealthbridge.ui

import android.app.Application
import android.net.Uri

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.R
import dev.playday3008.llmhealthbridge.clipboard.ClipboardReader
import dev.playday3008.llmhealthbridge.entry.EntryType
import dev.playday3008.llmhealthbridge.entry.Group
import dev.playday3008.llmhealthbridge.entry.ReplyResult
import dev.playday3008.llmhealthbridge.entry.parseReply
import dev.playday3008.llmhealthbridge.healthconnect.HcStatus
import dev.playday3008.llmhealthbridge.healthconnect.HealthConnectGateway
import dev.playday3008.llmhealthbridge.healthconnect.grantedClasses
import dev.playday3008.llmhealthbridge.healthconnect.writePermissions
import dev.playday3008.llmhealthbridge.measurement.MeasurementEntry
import dev.playday3008.llmhealthbridge.nutrition.Meal
import dev.playday3008.llmhealthbridge.review.MealReview
import dev.playday3008.llmhealthbridge.review.MeasurementReview
import dev.playday3008.llmhealthbridge.review.ReplyReview
import dev.playday3008.llmhealthbridge.review.ReviewEntry
import dev.playday3008.llmhealthbridge.review.SavedLookup
import dev.playday3008.llmhealthbridge.review.canSave
import dev.playday3008.llmhealthbridge.review.missingPermissions
import dev.playday3008.llmhealthbridge.review.removals
import dev.playday3008.llmhealthbridge.review.saveAll
import dev.playday3008.llmhealthbridge.review.withDenied
import dev.playday3008.llmhealthbridge.review.withGranted
import dev.playday3008.llmhealthbridge.review.withSaved
import dev.playday3008.llmhealthbridge.review.withWritten
import dev.playday3008.llmhealthbridge.review.withoutLeftovers
import dev.playday3008.llmhealthbridge.review.writeProblems
import dev.playday3008.llmhealthbridge.review.writes
import dev.playday3008.llmhealthbridge.send.LlmApp
import dev.playday3008.llmhealthbridge.send.LlmApps
import dev.playday3008.llmhealthbridge.send.RequestSender
import dev.playday3008.llmhealthbridge.send.Share

/** The screen currently shown. */
sealed interface Screen {
    data object Home : Screen

    /** Review of every entry of a reply, or of one group rebuilt from Health Connect. */
    data class Review(val review: ReplyReview) : Screen

    /** A reply that cannot be turned into any entry. */
    data class Blocked(val result: ReplyResult.Blocking) : Screen
}

/** Why the last save attempt could not proceed; shown on the review screen. */
sealed interface SaveBlock {
    /** Some write permissions were refused; nothing was written. */
    data object PermissionDenied : SaveBlock

    data object NeedsInstall : SaveBlock

    data object Unavailable : SaveBlock
}

/** The "Reply found in clipboard" offer; [text] summarises the reply. */
data class Banner(val text: String)

/** State of the recent-entries list. */
sealed interface RecentGroups {
    data object Loading : RecentGroups

    /** Saved groups of the types the app may write, newest first. */
    data class Loaded(val groups: List<Group>) : RecentGroups

    /** No Health Connect permission yet, so nothing of this app can have been saved. */
    data object NothingSaved : RecentGroups

    data class Failed(val message: String) : RecentGroups
}

/** Everything the home screen shows; [sending] is true while a request is being prepared. */
data class HomeUi(
    val apps: List<LlmApp>,
    val selected: LlmApp?,
    val banner: Banner?,
    val recent: RecentGroups,
    val sending: Boolean = false
)

/** Holds navigation, the clipboard offer, the chosen LLM app and the recent-entries list. */
@Suppress("TooManyFunctions")
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val clipboard = ClipboardReader(app)
    private val gateway = HealthConnectGateway(app)
    private val sender = RequestSender(app)
    private val prefs = app.getSharedPreferences(SETTINGS, Application.MODE_PRIVATE)
    private var pendingText: String? = null
    private var requested: Set<String>? = null

    /**
     * A message about the last prepared share, shown once the user is back from the LLM app: posted
     * at once, it would be dismissed when the share takes the user away.
     */
    private var afterShareMessage: String? = null

    /** Changes whenever a review is opened or left, so a finishing save can tell it is stale. */
    private var reviewSession = 0

    /** The [reviewSession] of the review being saved. */
    private var saveSession = 0

    /**
     * Write permissions the user refused in this run of the app and has not granted since. New
     * reviews start with them marked, so a save writes the allowed types without asking again: once
     * refused twice, Health Connect answers a request at once without showing anything.
     */
    private var refused: Set<String> = emptySet()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)

    /** The screen currently shown. */
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _home = MutableStateFlow(initialHome())

    /** Everything the home screen shows. */
    val home: StateFlow<HomeUi> = _home.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Snackbar messages; each is delivered once, held until a collector is active. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _shares = Channel<Share>(Channel.BUFFERED)

    /** Prepared requests for the activity to start; each is delivered once. */
    val shares: Flow<Share> = _shares.receiveAsFlow()

    private val _permissionRequests = Channel<Set<String>>(Channel.BUFFERED)

    /**
     * Write permissions a save needs requested; the activity launches the request and reports the
     * answer to [onPermissionResult].
     */
    val permissionRequests: Flow<Set<String>> = _permissionRequests.receiveAsFlow()

    private val _saveBlock = MutableStateFlow<SaveBlock?>(null)

    /** Why the last save attempt could not proceed, or null. */
    val saveBlock: StateFlow<SaveBlock?> = _saveBlock.asStateFlow()

    private val _saving = MutableStateFlow(false)

    /** True while a save is in progress. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        refreshRecent()
    }

    private fun initialHome(): HomeUi {
        val apps = LlmApps.installed(getApplication<Application>().packageManager)
        val saved = prefs.getString(LAST_APP, null)
        val selected = if (saved ==
            null
        ) {
            apps.firstOrNull()
        } else {
            apps.find { it.packageName == saved }
        }
        return HomeUi(apps, selected, null, RecentGroups.Loading)
    }

    /** Makes [app] the send target and remembers it; null means choose through a chooser. */
    fun selectApp(app: LlmApp?) {
        prefs.edit { putString(LAST_APP, app?.packageName ?: "") }
        _home.update { it.copy(selected = app) }
    }

    /** URI of the camera target file for `TakePicture`. */
    fun cameraUri(): Uri = sender.cameraUri()

    /** Prepares a request with the photos at [uris] and hands it to the activity via [shares]. */
    fun sendPhotos(uris: List<Uri>) = prepareShare(R.string.send_failed) {
        val photos = sender.preparePicked(uris)
        if (photos.isEmpty()) {
            showMessage(string(R.string.photos_none_read))
            return@prepareShare null
        }
        val share = sender.prepare(photos, _home.value.selected)
        val dropped = uris.size.coerceAtMost(RequestSender.MAX_PHOTOS) - photos.size + share.dropped
        if (dropped > 0) {
            val message = plural(R.plurals.photos_dropped, dropped, dropped)
            if (share.intent == null) showMessage(message) else afterShareMessage = message
        }
        share
    }

    /** Prepares a request without photos, for a meal the user describes in the LLM app. */
    fun describe() = prepareShare(R.string.describe_failed) {
        sender.prepare(emptyList(), _home.value.selected)
    }

    /**
     * Runs [prepare] unless a request is already being prepared, reporting failures with the
     * [failure] message. The share survives activity recreation in [shares].
     */
    private fun prepareShare(@StringRes failure: Int, prepare: suspend () -> Share?) {
        if (_home.value.sending) return
        _home.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                prepare()?.takeIf { it.intent != null }?.let { _shares.send(it) }
            } catch (e: IOException) {
                showMessage(string(failure, reason(e)))
            } catch (e: SecurityException) {
                showMessage(string(failure, reason(e)))
            } finally {
                _home.update { it.copy(sending = false) }
            }
        }
    }

    /** Reports that the share for a prepared request could not be started. */
    fun onShareFailed(e: RuntimeException) {
        afterShareMessage = null
        showMessage(string(R.string.share_failed, reason(e)))
    }

    /**
     * Offers a new reply from the clipboard, once per clip, and rechecks refused permissions of the
     * review shown. Call when the window gains focus.
     */
    fun onWindowFocused() {
        afterShareMessage?.let(::showMessage)
        afterShareMessage = null
        refreshDenied()
        val text = clipboard.newMealText() ?: return
        val reply = parse(text) as? ReplyResult.Parsed ?: return
        pendingText = text
        _home.update { it.copy(banner = Banner(replySummary(reply.groups))) }
    }

    /**
     * Clears refusals the user has since granted, for example in Health Connect settings, so the
     * rows count again. Skipped during a save, which does its own check.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun refreshDenied() {
        if (_saving.value || currentReview()?.denied.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                if (gateway.status() == HcStatus.AVAILABLE) {
                    val granted = grantedNow()
                    updateReview { it.withGranted(granted) }
                    if (currentReview()?.denied.isNullOrEmpty() &&
                        _saveBlock.value == SaveBlock.PermissionDenied
                    ) {
                        _saveBlock.value = null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The refusals stay marked; the next save checks the permissions again.
            }
        }
    }

    /**
     * Opens the clipboard text for review. Refuses with a message when the clipboard is empty or
     * still holds this app's own instructions.
     */
    fun paste() {
        if (clipboard.holdsOwnInstructions()) {
            showMessage(string(R.string.clipboard_own_instructions))
            return
        }
        val text = clipboard.currentText()
        if (text == null) showMessage(string(R.string.clipboard_no_text)) else open(text)
    }

    /** Opens text shared into the app for review. */
    fun receiveSharedText(text: String) = open(text)

    /**
     * Reads the text file at [uri], shared into the app, and opens it for review. The providing
     * app's errors arrive as any exception, so every one is reported.
     */
    @Suppress("TooGenericExceptionCaught")
    fun receiveSharedFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { readLimited(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(string(R.string.shared_unreadable, reason(e)))
                return@launch
            }
            if (text.isNullOrBlank()) showMessage(string(R.string.shared_nothing)) else open(text)
        }
    }

    /** Opens the clipboard reply offered by the banner. */
    fun openBanner() {
        open(pendingText ?: return)
    }

    /** Hides the banner and forgets the offered reply. */
    fun dismissBanner() {
        pendingText = null
        _home.update { it.copy(banner = null) }
    }

    /** Reloads the recent-entries list from Health Connect. */
    fun refreshRecent() {
        _home.update { it.copy(recent = RecentGroups.Loading) }
        viewModelScope.launch {
            val recent = when (gateway.status()) {
                HcStatus.AVAILABLE -> loadRecent()
                HcStatus.NEEDS_INSTALL_OR_UPDATE ->
                    RecentGroups.Failed(string(R.string.recent_hc_needs_install))
                HcStatus.UNAVAILABLE -> RecentGroups.Failed(string(R.string.recent_hc_unavailable))
            }
            _home.update { it.copy(recent = recent) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadRecent(): RecentGroups = try {
        val granted = grantedNow()
        if (granted.isEmpty()) {
            RecentGroups.NothingSaved
        } else {
            val classes = EntryType.entries.flatMap { grantedClasses(it, granted) }.toSet()
            RecentGroups.Loaded(gateway.recentGroups(Instant.now().minus(RECENT_WINDOW), classes))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RecentGroups.Failed(
            string(R.string.recent_read_failed, reason(e))
        )
    }

    /** Opens a saved [group] for review and re-saving. */
    fun openGroup(group: Group) {
        reviewSession++
        _saveBlock.value = null
        _screen.value = Screen.Review(
            ReplyReview.of(
                listOf(reviewEntry(group)),
                emptyList(),
                reopened = true,
                denied = refused
            )
        )
        refreshDenied()
    }

    /** Returns to the home screen and refreshes the recent entries. */
    fun backToHome() {
        reviewSession++
        _screen.value = Screen.Home
        _saveBlock.value = null
        refreshRecent()
    }

    /**
     * Deletes the records of [group] of the types the app may write and refreshes the list. Reports
     * failures, a missing permission for every type of the entry, or types left alone, as a message.
     */
    @Suppress("TooGenericExceptionCaught")
    fun deleteGroup(group: Group) {
        viewModelScope.launch {
            try {
                val granted = grantedNow()
                val allowed = grantedClasses(group.id.type, granted)
                if (allowed.isEmpty()) {
                    showMessage(string(R.string.delete_denied))
                } else {
                    gateway.deleteGroup(group.id, allowed, Instant.now())
                    val partly = lostWriteAccess(group.id.type, granted)
                    showMessage(
                        string(
                            if (partly) R.string.entry_deleted_partly else R.string.entry_deleted
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(string(R.string.delete_failed, reason(e)))
            }
            refreshRecent()
        }
    }

    /** Applies [transform] to the reply under review; ignored on other screens. */
    fun updateReview(transform: (ReplyReview) -> ReplyReview) {
        _screen.update { if (it is Screen.Review) it.copy(review = transform(it.review)) else it }
    }

    private fun currentReview(): ReplyReview? = (_screen.value as? Screen.Review)?.review

    /** Activity-result contract that requests Health Connect permissions. */
    fun permissionContract() = gateway.permissionContract()

    /** Intent that opens the Health Connect listing in the app store. */
    fun installIntent() = gateway.installIntent()

    /** Intents that open Health Connect where this app's permissions can be changed, best first. */
    fun permissionSettingsIntents() = gateway.permissionSettingsIntents()

    /**
     * Saves the reply under review. Write permissions that are neither granted nor refused yet
     * are requested through [permissionRequests] and the save continues from
     * [onPermissionResult].
     */
    @Suppress("TooGenericExceptionCaught")
    fun save() {
        if (_saving.value || _screen.value !is Screen.Review) return
        _saving.value = true
        saveSession = reviewSession
        _saveBlock.value = null
        viewModelScope.launch {
            try {
                when (gateway.status()) {
                    HcStatus.NEEDS_INSTALL_OR_UPDATE -> finishSave(SaveBlock.NeedsInstall)
                    HcStatus.UNAVAILABLE -> finishSave(SaveBlock.Unavailable)
                    HcStatus.AVAILABLE -> requestOrWrite()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSave(e)
            }
        }
    }

    private suspend fun requestOrWrite() {
        val granted = grantedNow()
        if (saveSession != reviewSession) return finishSave(null)
        updateReview { it.withGranted(granted) }
        val review = currentReview() ?: return finishSave(null)
        val missing = review.missingPermissions(granted)
        if (missing.isEmpty()) {
            if (savedStillKnown()) writeReply()
        } else {
            requested = missing
            _permissionRequests.send(missing)
        }
    }

    /**
     * Continues the save that asked for permissions. If any were refused they are marked on the
     * review and nothing is written; the next save writes the rest.
     */
    @Suppress("TooGenericExceptionCaught")
    fun onPermissionResult(granted: Set<String>) {
        val missing = requested
        requested = null
        if (!_saving.value) return
        val newlyDenied = missing?.minus(granted).orEmpty()
        when {
            missing == null -> finishSave(null)
            newlyDenied.isNotEmpty() -> {
                refused = refused + newlyDenied
                updateReview { it.withDenied(newlyDenied) }
                finishSave(SaveBlock.PermissionDenied)
            }
            else -> viewModelScope.launch {
                try {
                    if (savedStillKnown()) writeReply()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failSave(e)
                }
            }
        }
    }

    private fun finishSave(block: SaveBlock?) {
        _saveBlock.value = block
        _saving.value = false
    }

    private fun failSave(e: Exception) {
        showMessage(string(R.string.save_failed, reason(e)))
        finishSave(null)
    }

    /**
     * Writes the review, then, when every write succeeded, deletes the leftovers marked for
     * removal. Returns home only when the user is still on the review that was saved and nothing
     * is left to finish; after a failed cleanup the review stays open, so saving it again does it.
     */
    private suspend fun writeReply() {
        val now = Instant.now()
        val granted = grantedNow()
        if (saveSession != reviewSession) return finishSave(null)
        val review = currentReview()?.withGranted(granted)
        if (review == null || !review.canSave(now) || review.writeProblems(now).isNotEmpty()) {
            showMessage(string(R.string.save_blocked))
            finishSave(null)
            return
        }
        val outcome = saveAll(review.writes(granted, now)) {
            gateway.saveGroup(it.id, it.allowedClasses, now, it.build)
        }
        val failure = outcome.failure
        val cleanup = string(R.string.save_cleanup_failed).takeIf { outcome.cleanupFailures > 0 }
        when {
            failure == null -> {
                val removal = removeLeftovers(review, granted, now)
                val saved = plural(R.plurals.saved_records, outcome.saved, outcome.saved)
                showMessage(
                    listOfNotNull(saved.takeIf { outcome.saved > 0 }, removal.message, cleanup)
                        .joinToString(" ")
                        .ifEmpty { string(R.string.nothing_saved) }
                )
                refreshRecent()
                if (saveSession == reviewSession) {
                    if (cleanup == null && removal.complete) {
                        _screen.value = Screen.Home
                    } else {
                        updateReview {
                            it.withWritten(outcome.written).withoutLeftovers(removal.deleted)
                        }
                    }
                }
                finishSave(null)
            }
            outcome.saved == 0 -> failSave(failure)
            else -> {
                val partly = plural(
                    R.plurals.save_partly_failed,
                    outcome.saved,
                    outcome.saved,
                    reason(failure)
                )
                showMessage(listOfNotNull(partly, cleanup).joinToString(". "))
                if (saveSession == reviewSession) updateReview { it.withWritten(outcome.written) }
                finishSave(null)
            }
        }
    }

    /**
     * What [removeLeftovers] did: the [message] to show, if any, the groups it [deleted], and whether
     * it deleted every one it tried ([complete]).
     */
    private class Removal(val message: String?, val deleted: List<Group>, val complete: Boolean)

    /** Deletes the leftovers of [review] marked for removal, through the types that may be written. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun removeLeftovers(
        review: ReplyReview,
        granted: Set<String>,
        now: Instant
    ): Removal {
        val removable = review.removals().map { it to grantedClasses(it.id.type, granted) }
            .filter { it.second.isNotEmpty() }
        val removed = mutableListOf<Group>()
        return try {
            removable.forEach { (group, allowed) ->
                gateway.deleteGroup(group.id, allowed, now)
                removed += group
            }
            val count = removed.size
            val lost = removed.any { lostWriteAccess(it.id.type, granted) }
            val message = listOfNotNull(
                plural(R.plurals.removed_entries, count, count).takeIf { count > 0 },
                string(R.string.removed_partly).takeIf { lost }
            ).joinToString(" ").ifEmpty { null }
            Removal(message, removed, complete = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Removal(
                string(R.string.remove_failed, reason(e)),
                removed,
                complete = false
            )
        }
    }

    /**
     * The write permissions granted now. Refusals since granted are forgotten, and every permission
     * seen granted is remembered, so a delete can tell when types it may no longer write could
     * still hold records.
     */
    private suspend fun grantedNow(): Set<String> {
        val granted = gateway.grantedPermissions()
        refused = refused - granted
        val ever = prefs.getStringSet(EVER_GRANTED, null).orEmpty()
        if (!ever.containsAll(granted)) prefs.edit { putStringSet(EVER_GRANTED, ever + granted) }
        return granted
    }

    /**
     * True when a write permission of [type] was granted before but is not in [granted] now, so a
     * delete through the allowed types may leave records of that entry behind.
     */
    private fun lostWriteAccess(type: EntryType, granted: Set<String>): Boolean {
        val ever = prefs.getStringSet(EVER_GRANTED, null).orEmpty()
        return writePermissions(type).any { it in ever && it !in granted }
    }

    /** Posts a snackbar message. */
    fun showMessage(text: String) {
        _messages.trySend(text)
    }

    private fun open(text: String) {
        reviewSession++
        dismissBanner()
        _saveBlock.value = null
        when (val result = parse(text)) {
            is ReplyResult.Blocking -> _screen.value = Screen.Blocked(result)
            is ReplyResult.Parsed -> {
                val review = ReplyReview.of(
                    result.groups.map(::reviewEntry),
                    result.warnings,
                    reopened = false,
                    denied = refused
                ).copy(lookup = SavedLookup.PENDING)
                _screen.value = Screen.Review(review)
                refreshDenied()
                findSaved(review)
            }
        }
    }

    /**
     * Looks up the saved groups of the request of [review] with [lookupSaved], so the review shows
     * what saving replaces and which saved entries the reply no longer contains, and ends the
     * review's lookup.
     */
    private fun findSaved(review: ReplyReview) {
        val session = reviewSession
        viewModelScope.launch {
            val saved = lookupSaved(review)
            if (session == reviewSession) {
                updateReview { r ->
                    saved?.let(r::withSaved) ?: r.copy(lookup = SavedLookup.FAILED)
                }
            }
        }
    }

    /**
     * This app's saved groups of the request of [review], or null when the lookup failed. Only
     * types with a granted permission can be read; without Health Connect or any permission, nothing
     * of this app can be found.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun lookupSaved(review: ReplyReview): List<Group>? = try {
        val available = gateway.status() == HcStatus.AVAILABLE
        val granted = if (available) grantedNow() else emptySet()
        val classes = EntryType.entries.flatMap { grantedClasses(it, granted) }.toSet()
        if (classes.isEmpty()) {
            emptyList()
        } else {
            gateway.requestGroups(review.entries.first().id.request, classes, Instant.now())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Right before writing, looks the saved groups up again, since types allowed after the first
     * lookup (in the permission dialog, in Health Connect settings, or after Health Connect became
     * available) could not be read then. Returns true when the review already knew every one, or is
     * a reopened entry, which has no leftovers; otherwise shows the new ones on the review, ends the
     * save and returns false, so the user sees them before anything is written or deleted.
     */
    private suspend fun savedStillKnown(): Boolean {
        val review = currentReview()?.takeIf { saveSession == reviewSession }
            ?: return false.also { finishSave(null) }
        val saved = if (review.reopened) emptyList() else lookupSaved(review).orEmpty()
        val known = review.replaces.keys + review.leftovers.map { it.group.id }
        val current = saveSession == reviewSession
        val stillKnown = current && saved.all { it.id in known }
        if (!stillKnown) {
            if (current) {
                updateReview { it.withSaved(saved) }
                showMessage(string(R.string.saved_entries_found))
            }
            finishSave(null)
        }
        return stillKnown
    }

    private fun reviewEntry(group: Group): ReviewEntry = when (group) {
        is Meal -> MealReview(group)
        is MeasurementEntry -> MeasurementReview(group)
        else -> error("No review for ${group::class.simpleName}")
    }

    private fun parse(text: String) = parseReply(text, Instant.now(), ZoneId.systemDefault())

    /** What went wrong, for a message: the exception's message, else its class name. */
    private fun reason(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, count, *args)

    private companion object {
        const val SETTINGS = "settings"
        const val LAST_APP = "lastApp"
        const val EVER_GRANTED = "everGranted"
        val RECENT_WINDOW: Duration = Duration.ofDays(7)
    }
}

/** Largest shared text file read, far above any reply. */
private const val MAX_SHARED_BYTES = 1 shl 20

/** The UTF-8 text of [input], or an [IOException] when it is larger than [MAX_SHARED_BYTES]. */
private fun readLimited(input: InputStream): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        out.write(buffer, 0, count)
        if (out.size() > MAX_SHARED_BYTES) throw IOException("larger than 1 MB")
    }
    return out.toString(Charsets.UTF_8.name())
}
