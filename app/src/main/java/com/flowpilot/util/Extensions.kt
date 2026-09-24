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

/**
 * Normalizes spoken number words, conversational filler phrases, and math symbols
 * into standard digits and operators (e.g., "seven" -> "7", "it's 4" -> "4", "plus" -> "+").
 */
fun String.normalizeNumberWords(): String {
    val trimmed = this.trim().lowercase()
    // Isolated speech-to-text homophones for single numbers
    when (trimmed) {
        "to", "too" -> return "2"
        "for" -> return "4"
        "ate" -> return "8"
        "won" -> return "1"
        "oh" -> return "0"
    }

    val wordToDigit = listOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16",
        "seventeen" to "17", "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "thirty" to "30", "forty" to "40",
        "fifty" to "50", "sixty" to "60", "seventy" to "70",
        "eighty" to "80", "ninety" to "90", "hundred" to "100",
        "plus" to "+", "add" to "+", "addition" to "+",
        "minus" to "-", "subtract" to "-", "subtraction" to "-",
        "times" to "*", "multiply" to "*", "multiplied by" to "*",
        "divide" to "/", "divided by" to "/",
        "equals" to "=", "equal" to "="
    )
    var result = this.trim()
    for ((word, digit) in wordToDigit) {
        result = result.replace(Regex("(?i)\\b$word\\b"), digit)
    }
    // Remove filler phrases like "it's 7" or "the number is 4" or "first is 5"
    result = result.replace(
        Regex("(?i)\\b(it's|it is|the number is|make it|use|number|first is|second is|first number is|second number is|take|put|give me|enter)\\b"),
        ""
    ).trim()
    // Strip trailing punctuation often inserted by speech recognizers (e.g. "7." -> "7")
    result = result.trimEnd('.', '!', '?', ',', ' ')
    return result
}

/**
 * Extracts the arithmetic operation from a voice command.
 * Returns canonical operation name: "add", "subtract", "multiply", "divide", or null if not detected.
 */
fun String.extractOperation(): String? {
    val lower = this.lowercase()
    // Order matters: check more specific words first to avoid false positives
    return when {
        lower.contains("multiply") || lower.contains("times") || lower.contains("multiplication") ||
        lower.contains("product") || lower.contains("into") -> "multiply"
        lower.contains("divide") || lower.contains("division") || lower.contains("divided") -> "divide"
        lower.contains("subtract") || lower.contains("minus") || lower.contains("subtraction") ||
        lower.contains("difference") -> "subtract"
        lower.contains("add") || lower.contains("plus") || lower.contains("addition") ||
        lower.contains("sum") -> "add"
        else -> null
    }
}

/**
 * Maps an operation name to a calculator operator button's (resourceId suffix, contentDescription).
 * Returns null if the operation is not recognized.
 */
fun operationToCalculatorTarget(operation: String): Pair<String, String>? {
    return when (operation.lowercase()) {
        "add", "plus", "addition", "sum", "+" -> "op_add" to "Add"
        "subtract", "minus", "subtraction", "difference", "-", "−" -> "op_sub" to "Subtract"
        "multiply", "times", "multiplication", "product", "into", "*", "×" -> "op_mul" to "Multiply"
        "divide", "division", "divided", "/", "÷" -> "op_div" to "Divide"
        else -> null
    }
}
