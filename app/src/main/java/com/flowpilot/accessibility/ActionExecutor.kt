package com.flowpilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ActionExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    /**
     * Click a node. If the node itself isn't clickable, walk up to find a clickable ancestor.
     */
    suspend fun click(node: AccessibilityNodeInfo): Boolean {
        Log.d(TAG, "Clicking: ${node.text ?: node.contentDescription ?: node.viewIdResourceName ?: "unknown"}")

        // Try clicking the node directly
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(Constants.UI_SETTLE_DELAY_MS)
            return result
        }

        // Walk up to find clickable ancestor
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                delay(Constants.UI_SETTLE_DELAY_MS)
                return result
            }
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()

        // Fallback: tap at the center of the node's bounds using gesture
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return tapAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * Set text on an editable node.
     */
    suspend fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d(TAG, "Setting text: \"$text\" on ${node.viewIdResourceName ?: "editable field"}")

        // Focus the node first
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(300)

        // Clear existing text
        val clearBundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
        delay(200)

        // Set new text
        val textBundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textBundle)
        delay(Constants.UI_SETTLE_DELAY_MS)
        return result
    }

    /**
     * Scroll a scrollable node forward or backward.
     */
    suspend fun scroll(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        Log.d(TAG, "Scrolling ${if (forward) "forward" else "backward"}")

        // Find the scrollable node (might be the node itself or an ancestor)
        var scrollable = node
        if (!node.isScrollable) {
            scrollable = findScrollableAncestor(node) ?: run {
                Log.w(TAG, "No scrollable node found")
                return false
            }
        }

        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val result = scrollable.performAction(action)
        if (scrollable !== node) scrollable.recycle()
        delay(800) // Scroll needs more time for content to load
        return result
    }

    /**
     * Press the global back button.
     */
    suspend fun pressBack(): Boolean {
        Log.d(TAG, "Pressing back")
        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(Constants.UI_SETTLE_DELAY_MS)
        return result
    }

    /**
     * Press the global home button.
     */
    suspend fun pressHome(): Boolean {
        val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(Constants.UI_SETTLE_DELAY_MS)
        return result
    }

    /**
     * Open an app by its package name.
     */
    suspend fun openApp(packageName: String): Boolean {
        Log.d(TAG, "Opening app: $packageName")
        return try {
            val pm = service.packageManager
            var intent = pm.getLaunchIntentForPackage(packageName)

            if (intent == null) {
                val cleanName = packageName.trim().lowercase()
                val token = if (cleanName.contains('.')) {
                    cleanName.split('.').filter { it !in listOf("com", "android", "apps", "app", "google") }.lastOrNull() ?: cleanName
                } else {
                    cleanName
                }

                val installed = pm.getInstalledApplications(0)
                val candidate = installed.firstOrNull {
                    it.packageName.contains(token, ignoreCase = true)
                }
                if (candidate != null) {
                    Log.i(TAG, "Resolved '$packageName' (token '$token') to installed package '${candidate.packageName}'")
                    intent = pm.getLaunchIntentForPackage(candidate.packageName)
                }
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                service.startActivity(intent)
                delay(2000) // Wait for app to open
                true
            } else {
                Log.e(TAG, "No launch intent found for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $packageName", e)
            false
        }
    }

    /**
     * Tap at specific screen coordinates using gesture dispatch.
     */
    private suspend fun tapAtCoordinates(x: Float, y: Float): Boolean {
        Log.d(TAG, "Tapping at ($x, $y)")
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Find a scrollable ancestor of a node.
     */
    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 10) {
            if (current.isScrollable) return current
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return null
    }

    /**
     * Wait for UI to settle by checking if the screen signature stabilizes.
     */
    suspend fun waitForUISettle(
        uiTreeParser: UITreeParser,
        timeoutMs: Long = 3000
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var lastSignature = ""
        var stableCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service.rootInActiveWindow
            if (root == null) {
                delay(200)
                continue
            }
            val signature = uiTreeParser.computeScreenSignature(root)
            // Don't recycle rootInActiveWindow — it's managed by the system

            if (signature == lastSignature) {
                stableCount++
                if (stableCount >= 2) return true // Stable for 2 consecutive checks
            } else {
                stableCount = 0
                lastSignature = signature
            }
            delay(300)
        }
        return false
    }
}
