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
     * Check if there's a popup/dialog overlay on screen.
     */
    fun hasPopupOverlay(root: AccessibilityNodeInfo): Boolean {
        try {
            val className = root.className?.toString() ?: ""
            if (className.contains("Dialog", ignoreCase = true) ||
                className.contains("BottomSheet", ignoreCase = true)) {
                return true
            }

            fun check(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 25) return false
                try {
                    val id = node.viewIdResourceName?.lowercase() ?: ""
                    if (id.contains("dialog") || id.contains("popup") || id.contains("modal") ||
                        id.contains("overlay") || id.contains("bottom_sheet")) {
                        return true
                    }
                    for (i in 0 until node.childCount) {
                        val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                        val has = check(child, depth + 1)
                        child.recycle()
                        if (has) return true
                    }
                } catch (e: Exception) {
                    // Stale node safe
                }
                return false
            }

            return check(root, 0)
        } catch (e: Exception) {
            return false
        }
    }
}
