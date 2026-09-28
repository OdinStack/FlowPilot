package com.flowpilot.accessibility

import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.util.Constants

class SafetyDetector {

    companion object {
        private const val TAG = "SafetyDetector"
    }

    sealed class SafetyResult {
        object Safe : SafetyResult()
        data class Credential(val reason: String) : SafetyResult()
        data class Payment(val reason: String) : SafetyResult()
    }

    /**
     * Check if the current screen is a credential or payment screen.
     * Called BEFORE every replay step.
     */
    fun check(root: AccessibilityNodeInfo): SafetyResult {
        try {
            val packageName = root.packageName?.toString() ?: ""

            // Check 1: Payment app package
            if (packageName in Constants.PAYMENT_PACKAGES) {
                Log.w(TAG, "Payment app detected: $packageName")
                return SafetyResult.Payment("Payment app detected: $packageName")
            }

            // Check 2: Password input fields (MOST RELIABLE)
            val hasPass = hasPasswordField(root)
            if (hasPass) {
                Log.w(TAG, "Password input field detected")
                return SafetyResult.Credential("Password input field detected")
            }

            // Check 3: Text pattern matching
            val allTexts = collectInteractiveTexts(root).map { it.lowercase() }

            val credentialHits = allTexts.count { text ->
                Constants.CREDENTIAL_TEXT_PATTERNS.any { pattern -> text.contains(pattern) }
            }
            if (credentialHits >= 2) {
                Log.w(TAG, "Multiple credential indicators found ($credentialHits hits)")
                return SafetyResult.Credential("Multiple credential indicators found")
            }

            val isShoppingApp = packageName.contains("amazon", true) || 
                                packageName.contains("flipkart", true) || 
                                packageName.contains("myntra", true) || 
                                packageName.contains("zomato", true) || 
                                packageName.contains("swiggy", true)

            if (!(isShoppingApp && !hasPass)) {
                val paymentHits = allTexts.count { text ->
                    Constants.PAYMENT_TEXT_PATTERNS.any { pattern -> text.contains(pattern) }
                }
                if (paymentHits >= 3) {
                    Log.w(TAG, "Payment screen detected ($paymentHits hits)")
                    return SafetyResult.Payment("Payment screen detected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in safety check", e)
        }

        return SafetyResult.Safe
    }

    private fun hasPasswordField(root: AccessibilityNodeInfo): Boolean {
        fun checkNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 25) return false
            try {
                // Only check VISIBLE nodes — Amazon/Flipkart have hidden WebView login forms
                if (!node.isVisibleToUser) return false
                if (node.isPassword) return true

                if (node.isEditable) {
                    val inputType = node.inputType
                    val passwordMask = (
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD or
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    )
                    if (inputType and passwordMask != 0) return true

                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    if (resId.contains("password") || resId.contains("pin") || resId.contains("otp") || resId.contains("cvv")) {
                        return true
                    }

                    val hint = node.hintText?.toString()?.lowercase() ?: ""
                    if (hint.contains("password") || hint.contains("pin") || hint.contains("otp") || hint.contains("cvv")) {
                        return true
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val isPass = checkNode(child, depth + 1)
                    child.recycle()
                    if (isPass) return true
                }
            } catch (e: Exception) {
                // Stale node safe
            }
            return false
        }
        return checkNode(root, 0)
    }

    private fun collectInteractiveTexts(root: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo, depth: Int, isInteractiveContext: Boolean) {
            if (depth > 25) return
            try {
                val currentInteractive = isInteractiveContext || node.isEditable || 
                    (node.isClickable && (node.className?.contains("EditText") == true || node.className?.contains("Button") == true || node.inputType != 0))
                
                if (currentInteractive) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    traverse(child, depth + 1, currentInteractive)
                    child.recycle()
                }
            } catch (e: Exception) {
                // Stale node safe
            }
        }
        traverse(root, 0, false)
        return texts
    }
}
