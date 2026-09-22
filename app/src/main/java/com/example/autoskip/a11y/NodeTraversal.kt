package com.example.autoskip.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autoskip.model.Rule

/**
 * A rule hit together with everything needed to click it.
 *
 * The node references here are only valid for the scan that produced them.
 * Nothing retains a [Match] across accessibility events: nodes go stale, and
 * since API 33 `recycle()` is a no-op so there is no manual lifetime to manage
 * either.
 */
class Match(
    val rule: Rule,
    /** The text/description that actually matched, for logging. */
    val label: String,
    val clickTarget: AccessibilityNodeInfo,
    val bounds: Rect,
    val viewId: String?,
    val ownerPackage: String?,
    /**
     * False when no clickable node was found in the ancestor chain, in which
     * case only a coordinate gesture can reach the target.
     */
    val nodeIsClickable: Boolean,
)

/**
 * Breadth-first walk over the window's node tree, collecting rule matches.
 */
object NodeTraversal {

    /** GKD caps children per node too; some WebViews report absurd counts. */
    private const val MAX_CHILDREN_PER_NODE = 512

    /**
     * How far up from a matched label to look for the thing that is actually
     * clickable. Ad layouts routinely put the "跳过" text in a non-clickable
     * TextView inside a clickable FrameLayout.
     */
    private const val MAX_CLICKABLE_ANCESTOR_HOPS = 5

    /**
     * @param deadlineUptimeMs `SystemClock.uptimeMillis()` value after which the
     *   scan gives up. A huge tree is not worth stalling the worker thread for.
     * @param maxNodes hard cap on visited nodes, independent of the deadline.
     */
    fun collect(
        roots: List<AccessibilityNodeInfo>,
        rules: List<Rule>,
        maxNodes: Int,
        deadlineUptimeMs: Long,
    ): List<Match> {
        val matches = mutableListOf<Match>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        // Cycles are rare but real (GKD hit them too), and a cycle here would
        // mean an unbounded walk. AccessibilityNodeInfo equality is value-based
        // on the underlying node, so a set is enough to break them.
        val seen = HashSet<AccessibilityNodeInfo>()
        var visited = 0

        roots.forEach { queue.addLast(it) }

        while (queue.isNotEmpty()) {
            if (visited >= maxNodes) break
            if (SystemClock.uptimeMillis() > deadlineUptimeMs) break

            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            visited++

            // Invisible nodes still get walked: a container can be marked
            // invisible while its children are not.
            if (node.isVisibleToUser) {
                matchNode(node, rules)?.let { matches += it }
            }

            val childCount = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) queue.addLast(child)
            }
        }

        return matches
    }

    private fun matchNode(node: AccessibilityNodeInfo, rules: List<Rule>): Match? {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (text.isNullOrEmpty() && desc.isNullOrEmpty()) return null

        val viewId = node.viewIdResourceName

        for (rule in rules) {
            if (!rule.isUsable) continue

            val suffix = rule.viewIdSuffix
            if (suffix != null && (viewId == null || !viewId.endsWith(suffix))) continue
            if (!rule.matches(text, desc)) continue

            val clickable = findClickableTarget(node)
            val target = clickable ?: node
            val bounds = Rect().also { target.getBoundsInScreen(it) }

            return Match(
                rule = rule,
                label = text ?: desc.orEmpty(),
                clickTarget = target,
                bounds = bounds,
                viewId = viewId,
                ownerPackage = node.packageName?.toString(),
                nodeIsClickable = clickable != null,
            )
        }

        return null
    }

    /** The node itself when clickable, otherwise its nearest clickable ancestor. */
    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isVisibleToUser) return node

        var current: AccessibilityNodeInfo = node
        repeat(MAX_CLICKABLE_ANCESTOR_HOPS) {
            current = current.parent ?: return null
            if (current.isClickable && current.isVisibleToUser) return current
        }
        return null
    }
}
