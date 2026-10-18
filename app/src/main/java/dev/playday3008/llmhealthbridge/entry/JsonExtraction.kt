package dev.playday3008.llmhealthbridge.entry

private val FENCE = Regex("```([A-Za-z0-9_-]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

/**
 * Extracts the source text of one JSON object from an LLM reply.
 *
 * Candidates are tried in order: the first fence tagged `json` (case-insensitive), the first
 * fenced block whose trimmed content starts with `{`, then the whole text. From a candidate the
 * object opened by its first `{` is taken; when that object never closes, the rest of the
 * candidate is returned, so a cut-off reply is not mistaken for one of its inner objects (see
 * [isClosedObject]). Returns null when no `{` is found. The result is not validated as JSON.
 */
fun extractJsonObject(text: String): String? = locate(text)?.let {
    if (it.end >=
        0
    ) {
        it.source.substring(it.from, it.end + 1)
    } else {
        it.source.substring(it.from).trimEnd()
    }
}

/**
 * True when the object [extractJsonObject] takes from [text] is an element of a JSON array: it is
 * preceded by `[` or followed by `,` or `]`. That is a reply copied from the middle, or a bare
 * array of entries, of which the object is only one part.
 */
fun isArrayElement(text: String): Boolean {
    val found = locate(text)?.takeIf { it.end >= 0 } ?: return false
    val after = found.source.substring(found.end + 1).trimStart()
    return found.source.substring(0, found.from).trimEnd().endsWith('[') ||
        after.startsWith(',') ||
        after.startsWith(']')
}

/** The object opened at [from] in [source], closing at [end], or -1 when it never closes. */
private class Found(val source: String, val from: Int, val end: Int)

private fun locate(text: String): Found? {
    val fences = FENCE.findAll(text).toList()
    val tagged = fences.firstOrNull { it.groupValues[1].equals("json", ignoreCase = true) }
    val untagged = fences.firstOrNull { it.groupValues[2].trimStart().startsWith("{") }
    return listOfNotNull(tagged, untagged)
        .firstNotNullOfOrNull { firstObject(it.groupValues[2]) }
        ?: firstObject(text)
}

private fun firstObject(s: String): Found? {
    val from = s.indexOf('{')
    return if (from < 0) null else Found(s, from, balancedEnd(s, from))
}

/** True when [json], as returned by [extractJsonObject], is one complete `{...}` object. */
fun isClosedObject(json: String): Boolean = balancedEnd(json, 0) == json.length - 1

/** Index of the brace closing the object opened at [start], or -1 if it never closes. */
private fun balancedEnd(s: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until s.length) {
        val c = s[i]
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return i
        }
    }
    return -1
}
