package com.flowpilot.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.util.Constants

class ScreenAnalyzer {

    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    /**
     * Check if current screen has a dismissible popup/overlay.
     * Returns the dismiss button node if found.
     */
    fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dismissPatterns = Constants.DISMISS_TEXT_PATTERNS
        val dismissIdPatterns = Constants.DISMISS_ID_PATTERNS

        fun searchNode(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            try {
                if (node.isClickable && node.isVisibleToUser) {
                    val text = node.text?.toString()?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val id = node.viewIdResourceName?.lowercase() ?: ""

                    if (dismissPatterns.any { pattern ->
                        text.equals(pattern, ignoreCase = true) ||
                        desc.equals(pattern, ignoreCase = true) ||
                        (pattern.length > 2 && (text.contains(pattern) || desc.contains(pattern)))
                    }) {
                        Log.d(TAG, "Found dismiss button by text/desc: text='$text', desc='$desc'")
                        return node
                    }

                    if (dismissIdPatterns.any { pattern -> id.contains(pattern) }) {
                        Log.d(TAG, "Found dismiss button by id: '$id'")
                        return node
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = searchNode(child, depth + 1)
                    if (result != null) return result
                    child.recycle()
                }
            } catch (e: Exception) {
                // Stale node safe
            }
            return null
        }

        return searchNode(root, 0)
    }

    /**
     * Check if there's a dismissible popup/dialog overlay on screen.
     * Only returns true if we both detect a dialog-like structure AND find a dismiss button,
     * to avoid false positives on normal screens with overlay/bottom_sheet views.
     */
    fun hasPopupOverlay(root: AccessibilityNodeInfo): Boolean {
        try {
            val className = root.className?.toString() ?: ""
            // Root-level dialog detection is highly reliable
            if (className.contains("Dialog", ignoreCase = true)) {
                return true
            }

            // For structural checks, only flag if a dismiss button is also present
            fun hasDialogStructure(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 15) return false
                try {
                    val id = node.viewIdResourceName?.lowercase() ?: ""
                    val cls = node.className?.toString() ?: ""
                    // Only match explicit dialog/modal IDs, not generic "overlay" or "bottom_sheet"
                    if (id.contains("dialog") || id.contains("popup") || id.contains("modal") ||
                        cls.contains("Dialog", ignoreCase = true) || cls.contains("AlertDialog", ignoreCase = true)) {
                        return true
                    }
                    for (i in 0 until node.childCount) {
                        val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                        val has = hasDialogStructure(child, depth + 1)
                        child.recycle()
                        if (has) return true
                    }
                } catch (e: Exception) {
                    // Stale node safe
                }
                return false
            }

            if (hasDialogStructure(root, 0)) {
                // Double-check: only report overlay if a dismiss button exists
                return findDismissButton(root) != null
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Detect if the current screen has a Location / Saved Address Gatekeeper modal
     * (e.g., Zomato/Swiggy/Blinkit/Zepto/Amazon "Select a saved address" or "Device location not enabled").
     */
    fun isLocationGatekeeperScreen(root: AccessibilityNodeInfo): Boolean {
        val gatekeeperPhrases = listOf(
            "select a saved address",
            "device location not",
            "search location manually",
            "select delivery location",
            "choose a delivery address",
            "where do you want your delivery",
            "enable your device location"
        )
        return containsAnyPhrase(root, gatekeeperPhrases)
    }

    /**
     * Detect if the user/agent accidentally landed inside a full-screen "Select a location"
     * manual location search sub-screen (with "Use current location").
     */
    fun isSelectLocationSubScreen(root: AccessibilityNodeInfo): Boolean {
        val subScreenPhrases = listOf(
            "select a location",
            "use current location"
        )
        return containsAnyPhrase(root, subScreenPhrases) && !containsAnyPhrase(root, listOf("select a saved address"))
    }

    /**
     * Find the clickable saved address card (e.g., "Home", "Work", "Office", or first saved address row)
     * inside a "Select a saved address" bottom sheet / modal.
     */
    fun findSavedAddressNode(
        root: AccessibilityNodeInfo,
        preferredLabel: String = "Home"
    ): AccessibilityNodeInfo? {
        val candidateLabels = listOf(
            preferredLabel.lowercase(),
            "home",
            "work",
            "office"
        ).distinct()

        // Pass 1: Look for a saved address card in the bottom half of the screen (where bottom sheets live)
        // or inside the "Select a saved address" container.
        val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()

        fun search(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            try {
                if (node.isVisibleToUser) {
                    val text = node.text?.toString()?.trim() ?: ""
                    val desc = node.contentDescription?.toString()?.trim() ?: ""
                    val combined = "$text $desc".lowercase()

                    for ((priority, label) in candidateLabels.withIndex()) {
                        // Match exact label ("Home") or card text starting with "Home\n" / "Home "
                        val isMatch = text.equals(label, ignoreCase = true) ||
                            desc.equals(label, ignoreCase = true) ||
                            combined.startsWith("$label ") ||
                            combined.startsWith("$label\n") ||
                            combined.contains("noah havens")

                        if (isMatch) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            // Prefer nodes in the lower 65% of the screen (inside the saved address sheet,
                            // NOT the small header badge at the very top left y < 300)
                            val yScore = if (bounds.centerY() > 350) 100 else 10
                            val clickableTarget = findClickableSelfOrParent(node)
                            if (clickableTarget != null) {
                                matches.add(clickableTarget to (yScore - priority * 10))
                            }
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    search(child, depth + 1)
                }
            } catch (_: Exception) {}
        }

        search(root, 0)
        return matches.maxByOrNull { it.second }?.first
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        var steps = 0
        while (current != null && steps < 6) {
            if (current.isClickable) return current
            current = current.parent
            steps++
        }
        return node
    }

    private fun containsAnyPhrase(root: AccessibilityNodeInfo, phrases: List<String>): Boolean {
        fun scan(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 22) return false
            try {
                if (node.isVisibleToUser) {
                    val text = node.text?.toString()?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val hint = node.hintText?.toString()?.lowercase() ?: ""
                    if (phrases.any { p -> text.contains(p) || desc.contains(p) || hint.contains(p) }) {
                        return true
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    if (scan(child, depth + 1)) return true
                }
            } catch (_: Exception) {}
            return false
        }
        return scan(root, 0)
    }
}
