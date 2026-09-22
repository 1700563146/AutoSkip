package com.example.autoskip.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a [Match] into an actual tap.
 *
 * Two strategies, in order:
 *  1. Ask the node to click itself. Cheap, exact, and no coordinates involved.
 *  2. Dispatch a real touch gesture at the node's centre. Needed when the
 *     matched text sits in a container that never advertises `clickable`.
 */
class GestureTapper(private val service: AccessibilityService) {

    /**
     * @param onResult invoked exactly once — possibly later, on the main thread,
     *   for the gesture path — with whether the tap landed and how it was made.
     */
    fun tap(match: Match, onResult: (success: Boolean, how: String) -> Unit) {
        val settled = AtomicBoolean(false)

        fun finish(success: Boolean, how: String) {
            if (settled.compareAndSet(false, true)) onResult(success, how)
        }

        if (match.nodeIsClickable &&
            match.clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            finish(true, "节点 ACTION_CLICK")
            return
        }

        val x = match.bounds.exactCenterX()
        val y = match.bounds.exactCenterY()
        val path = Path().apply { moveTo(x, y) }
        val duration = maxOf(ViewConfiguration.getTapTimeout(), MIN_TAP_MS).toLong()

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        // A non-null callback matters: on API 31+ the system silently drops a
        // gesture while the user is touching the screen, and passing null (as
        // GKD does) makes that failure invisible.
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    finish(true, "坐标手势 (${x.toInt()}, ${y.toInt()})")
                }

                override fun onCancelled(description: GestureDescription?) {
                    finish(false, "手势被系统取消，通常是用户正在触摸屏幕")
                }
            },
            null,
        )

        if (!accepted) finish(false, "dispatchGesture 被系统拒绝")
    }

    private companion object {
        /** Below this a stroke is rejected as degenerate on some devices. */
        const val MIN_TAP_MS = 50
    }
}
