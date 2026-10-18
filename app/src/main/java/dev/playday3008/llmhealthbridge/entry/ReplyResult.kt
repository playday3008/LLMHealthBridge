package dev.playday3008.llmhealthbridge.entry

/** Outcome of parsing a pasted LLM reply. */
sealed interface ReplyResult {
    /** The reply cannot be turned into any entry; [snippet] is the extracted JSON text, if any. */
    data class Blocking(val errors: List<String>, val snippet: String?) : ReplyResult

    /** The entries found, in reply order; [warnings] are reply-level notes, each group has its own. */
    data class Parsed(val groups: List<Group>, val warnings: List<String>) : ReplyResult
}
