package com.example.autoskip.model

/** Which node attribute a rule's matchers are applied to. */
enum class MatchTarget {
    TEXT_ONLY,
    DESC_ONLY,
    TEXT_OR_DESC,
}

/**
 * A single matching rule.
 *
 * This is a deliberately small descendant of GKD's `RawRule`. GKD expresses its
 * matchers through a compiled selector expression language; here a rule is just
 * a list of substrings plus an optional regex, which covers the "find the skip
 * button" case without dragging in a parser.
 *
 * @param keywords substrings to look for. Any single hit is enough (OR).
 * @param regex optional extra matcher, applied in addition to [keywords].
 * @param target which of the node's attributes [keywords]/[regex] are tested against.
 * @param viewIdSuffix optional `vid` filter. When set, the node's view id must
 *   end with this string or the rule is skipped entirely.
 */
data class Rule(
    val id: String,
    val name: String,
    val keywords: List<String> = emptyList(),
    val regex: String? = null,
    val target: MatchTarget = MatchTarget.TEXT_OR_DESC,
    val viewIdSuffix: String? = null,
) {
    /**
     * A rule whose regex fails to compile degrades to keyword-only matching
     * rather than crashing the scan loop at runtime.
     */
    private val compiledRegex: Regex? = regex?.let { runCatching { Regex(it) }.getOrNull() }

    /** A rule with no usable matcher would match every node, so it is rejected up front. */
    val isUsable: Boolean get() = keywords.isNotEmpty() || compiledRegex != null

    /**
     * True when this rule fires for a node carrying the given text/description.
     * Either argument may be null.
     */
    fun matches(text: String?, desc: String?): Boolean {
        val fields = when (target) {
            MatchTarget.TEXT_ONLY -> listOfNotNull(text)
            MatchTarget.DESC_ONLY -> listOfNotNull(desc)
            MatchTarget.TEXT_OR_DESC -> listOfNotNull(text, desc)
        }
        return fields.any { it.isNotEmpty() && it.length <= MAX_CANDIDATE_LENGTH && matchesOne(it) }
    }

    private fun matchesOne(candidate: String): Boolean {
        if (keywords.any { candidate.contains(it, ignoreCase = true) }) return true
        return compiledRegex?.containsMatchIn(candidate) == true
    }

    companion object {
        /**
         * Anti-misclick guard: a short button label is what we are after. If the
         * matched attribute is a paragraph of body text that merely happens to
         * contain "跳过", clicking it would be a misfire, so long strings are
         * not considered at all.
         */
        const val MAX_CANDIDATE_LENGTH = 50
    }
}
