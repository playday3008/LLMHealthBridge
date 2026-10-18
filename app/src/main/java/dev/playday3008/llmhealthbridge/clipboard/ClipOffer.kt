package dev.playday3008.llmhealthbridge.clipboard

/** Decides whether a clipboard clip is worth offering to the user as a pasted reply. */
object ClipOffer {
    /** Label of the clip this app sets itself, so it never offers its own instructions back. */
    const val OWN_LABEL = "llmhb-instructions"

    /** True when the clip is newer than [lastSeen] and was not set by this app. */
    fun shouldOffer(timestamp: Long, label: CharSequence?, lastSeen: Long): Boolean =
        timestamp > lastSeen && label?.toString() != OWN_LABEL
}
