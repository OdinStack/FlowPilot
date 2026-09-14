package com.flowpilot.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Check if an accessibility service is enabled.
 */
fun Context.isAccessibilityServiceEnabled(serviceClass: Class<out AccessibilityService>): Boolean {
    val expectedComponentName = "${packageName}/${serviceClass.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}

/**
 * Open the accessibility settings screen.
 */
fun Context.openAccessibilitySettings() {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

/**
 * Safely get text from an AccessibilityNodeInfo.
 */
fun AccessibilityNodeInfo.safeText(): String? = text?.toString()?.takeIf { it.isNotBlank() }

/**
 * Safely get content description.
 */
fun AccessibilityNodeInfo.safeContentDescription(): String? =
    contentDescription?.toString()?.takeIf { it.isNotBlank() }

/**
 * Safely get resource ID (short form without package prefix).
 */
fun AccessibilityNodeInfo.safeResourceId(): String? = viewIdResourceName?.takeIf { it.isNotBlank() }

/**
 * Safely get the short resource ID (just the ID part after /).
 */
fun AccessibilityNodeInfo.shortResourceId(): String? =
    viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/**
 * Get a human-readable description of a node.
 */
fun AccessibilityNodeInfo.describe(): String {
    val parts = mutableListOf<String>()
    safeText()?.let { parts.add("text=\"$it\"") }
    safeContentDescription()?.let { parts.add("desc=\"$it\"") }
    shortResourceId()?.let { parts.add("id=\"$it\"") }
    if (isClickable) parts.add("clickable")
    if (isEditable) parts.add("editable")
    if (isScrollable) parts.add("scrollable")
    val cls = className?.toString()?.substringAfterLast('.') ?: "View"
    return "$cls[${parts.joinToString(", ")}]"
}

/**
 * Fuzzy string contains - strips non-alphanumeric and compares lowercased.
 */
fun String.fuzzyContains(other: String): Boolean {
    val a = this.lowercase().replace(Regex("[^a-z0-9]"), "")
    val b = other.lowercase().replace(Regex("[^a-z0-9]"), "")
    return a.contains(b) || b.contains(a)
}
