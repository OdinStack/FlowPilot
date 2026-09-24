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
}
