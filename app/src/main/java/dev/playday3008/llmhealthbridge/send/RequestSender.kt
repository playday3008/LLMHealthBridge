package dev.playday3008.llmhealthbridge.send

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap

import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import dev.playday3008.llmhealthbridge.clipboard.ClipboardReader
import dev.playday3008.llmhealthbridge.entry.RequestIds

/**
 * A prepared request: [intent] shares it, to [target] when that app can be started, else through a
 * chooser. [dropped] photos could not be used; a null [intent] means none of the photos could.
 */
class Share(val intent: Intent?, val target: LlmApp?, val dropped: Int)

/**
 * Starts [share] from [activity]; does nothing when it has no intent. The target app opens in a
 * task of its own, as the chooser also does, so the user comes back to this app through recents or
 * the launcher without finding the LLM app in this app's task. The task is always a new one: an
 * existing task of the app would take the share into the conversation open there, which belongs
 * to an earlier request.
 */
fun startShare(activity: Activity, share: Share) {
    val intent = share.intent ?: return
    val explicit = share.target?.let {
        Intent(intent).setPackage(it.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    val started = explicit != null &&
        try {
            activity.startActivity(explicit)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    if (!started) {
        val chooser = Intent.createChooser(intent, null)
        activity.startActivity(chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

/** Prepares photos and the instructions file for sharing with an LLM app. */
class RequestSender(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val authority = "${context.packageName}.files"
    private val photoDir get() = File(context.cacheDir, PHOTO_DIR).apply { mkdirs() }

    /** URI of the (stale-cleared) camera target file for `TakePicture`. */
    fun cameraUri(): Uri {
        val file = File(photoDir, CAMERA_FILE).apply { delete() }
        return FileProvider.getUriForFile(context, authority, file)
    }

    /** Copies up to [MAX_PHOTOS] of [uris] into the cache, skipping unreadable ones. */
    suspend fun preparePicked(uris: List<Uri>): List<File> = withContext(io) {
        photoDir.listFiles { f -> f.name.startsWith(PICK_PREFIX) }?.forEach { it.delete() }
        val copies = mutableListOf<File>()
        for (uri in uris) {
            if (copies.size == MAX_PHOTOS) break
            copyPicked(uri, copies.size)?.let { copies += it }
        }
        copies
    }

    /**
     * Re-encodes [photos] (dropping all metadata, including GPS), writes the instructions filled
     * for a new request and copies them to the clipboard, and returns the [Share] for [target]. A
     * photo that cannot be decoded is dropped and never shared. With no [photos], the instructions
     * are shared alone; when photos were given and all were dropped, the share has no intent.
     *
     * Each request's files live in a directory of their own and the previous request's are
     * deleted, so an app that got an earlier request cannot read this one through its old grant.
     */
    suspend fun prepare(photos: List<File>, target: LlmApp?): Share {
        val now = Instant.now()
        val request = RequestIds.create(now)
        val prepared = withContext(io) { reencodeAll(photos, requestDir(request)) }
        val dropped = photos.size - prepared.files.size
        val intent = if (photos.isEmpty() || prepared.files.isNotEmpty()) {
            withContext(io) { buildIntent(prepared, request, now) }
        } else {
            null
        }
        return Share(intent, target, dropped)
    }

    /**
     * A new, empty directory for [request]'s files, after deleting everything shared before; only
     * the inputs of this request (the camera file and picked copies) stay.
     */
    private fun requestDir(request: String): File {
        photoDir.listFiles { f -> f.name != CAMERA_FILE && !f.name.startsWith(PICK_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        return File(photoDir, request).apply { mkdirs() }
    }

    private class Prepared(
        val dir: File,
        val files: List<File>,
        val captures: List<OffsetDateTime?>
    )

    private fun reencodeAll(photos: List<File>, dir: File): Prepared {
        val zone = ZoneId.systemDefault()
        val files = mutableListOf<File>()
        val captures = mutableListOf<OffsetDateTime?>()
        for (photo in photos) {
            val capture = readCaptureTime(photo, zone)
            val out = File(dir, "$OUT_PREFIX${files.size}.jpg")
            if (PhotoReencoder.reencode(photo, out)) {
                files += out
                captures += capture
            } else {
                out.delete()
            }
        }
        return Prepared(dir, files, captures)
    }

    private fun copyPicked(uri: Uri, index: Int): File? {
        val mime = context.contentResolver.getType(uri)
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        val dest = File(photoDir, "$PICK_PREFIX$index.$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null
            dest
        } catch (_: IOException) {
            dest.delete()
            null
        } catch (_: SecurityException) {
            dest.delete()
            null
        }
    }

    private fun buildIntent(prepared: Prepared, request: String, now: Instant): Intent {
        val template = context.assets.open(Instructions.ASSET).bufferedReader().use {
            it.readText()
        }
        val zone = ZoneId.systemDefault()
        val text = Instructions.fill(
            template,
            defaultTime(prepared.captures, now, zone),
            zone,
            request
        )
        val instructions = File(prepared.dir, Instructions.ASSET).apply { writeText(text) }
        ClipboardReader(context).copyInstructions(text)

        val uris = (prepared.files + instructions).map {
            FileProvider.getUriForFile(context, authority, it)
        }
        val clip = ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun readCaptureTime(file: File, zone: ZoneId): OffsetDateTime? = try {
        val exif = ExifInterface(file)
        exifCaptureTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            zone
        )
    } catch (_: IOException) {
        null
    }

    companion object {
        /** Most photos one request carries. */
        const val MAX_PHOTOS = 5
        private const val PHOTO_DIR = "photos"
        private const val CAMERA_FILE = "camera.jpg"
        private const val PICK_PREFIX = "pick-"
        private const val OUT_PREFIX = "meal-"
    }
}
