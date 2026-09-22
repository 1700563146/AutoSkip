package com.example.autoskip.model

/**
 * The rule set shipped with the app.
 *
 * These are compiled in rather than loaded from a subscription JSON, so this is
 * where GKD's rule-distribution machinery is intentionally left out.
 */
object DefaultRules {

    /**
     * View id of the test-mode overlay button. A node belonging to our own
     * package is only ever eligible for clicking when its view id ends with
     * this suffix, which keeps our own settings UI off-limits.
     */
    const val TEST_OVERLAY_VIEW_ID_SUFFIX = ":id/test_overlay_text"

    /** Package-relative form of the same id, as returned by `getResourceName`. */
    const val TEST_OVERLAY_ENTRY_NAME = "com.example.autoskip:id/test_overlay_text"

    val ALL: List<Rule> = listOf(
        Rule(
            id = "builtin_skip",
            name = "跳过",
            keywords = listOf("跳过"),
        ),
        Rule(
            id = "builtin_skip_en",
            name = "Skip (英文)",
            keywords = listOf("skip"),
        ),
        Rule(
            id = "builtin_close_ad",
            name = "关闭广告",
            keywords = listOf("关闭广告", "关闭此广告", "关闭推广"),
        ),
        Rule(
            id = "builtin_close_ad_exact",
            name = "Skip / Close Ad (精确匹配)",
            // Demonstrates the regex path: unlike `keywords`, which is a
            // contains() test, this one is anchored so it will not fire on a
            // sentence that merely mentions the word "skip".
            regex = "^(skip|close)(\\s+(ad|ads|advert))?$",
        ),
    ).filter { it.isUsable }
}
