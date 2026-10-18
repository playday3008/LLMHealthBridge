package dev.playday3008.llmhealthbridge.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.core.content.edit

import dev.playday3008.llmhealthbridge.entry.looksLikeReply

/** Reads the LLM reply from the clipboard and puts the instructions on it. */
class ClipboardReader(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns the primary clip text if it is new since the last call and looks like a meal entry,
     * else null. Each clip timestamp is examined once.
     */
    fun newMealText(): String? = try {
        val description = clipboard.primaryClipDescription
        val timestamp = description?.timestamp ?: 0L
        if (description != null &&
            ClipOffer.shouldOffer(timestamp, description.label, prefs.getLong(LAST_SEEN, 0L))
        ) {
            prefs.edit { putLong(LAST_SEEN, timestamp) }
            currentText()?.takeIf { looksLikeReply(it) }
        } else {
            null
        }
    } catch (_: SecurityException) {
        null
    }

    /** Returns the primary clip text regardless of seen state, for an explicit Paste. */
    fun currentText(): String? = try {
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.let { it.text ?: it.htmlText }
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    /** True when the primary clip is the one this app set with [copyInstructions]. */
    fun holdsOwnInstructions(): Boolean = try {
        clipboard.primaryClipDescription?.label?.toString() == ClipOffer.OWN_LABEL
    } catch (_: SecurityException) {
        false
    }

    /** Copies [text] as a plain-text clip labelled [ClipOffer.OWN_LABEL]. */
    fun copyInstructions(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(ClipOffer.OWN_LABEL, text))
        val timestamp = try {
            clipboard.primaryClipDescription?.timestamp ?: 0L
        } catch (_: SecurityException) {
            0L
        }
        if (timestamp > 0L) prefs.edit { putLong(LAST_SEEN, timestamp) }
    }

    private companion object {
        const val PREFS = "clipboard"
        const val LAST_SEEN = "last_seen"
    }
}
