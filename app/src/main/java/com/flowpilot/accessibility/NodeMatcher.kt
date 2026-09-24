package com.flowpilot.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.data.models.TargetSpec
import com.flowpilot.util.Constants
import com.flowpilot.util.fuzzyContains

class NodeMatcher {

    companion object {
        private const val TAG = "NodeMatcher"
    }

    data class MatchResult(
        val node: AccessibilityNodeInfo,
        val score: Float,
        val matchDetails: String
    )

    /**
     * Find the best matching node in the accessibility tree for a target spec.
     * @param root The root of the current UI tree
     * @param spec The target specification from the workflow step
     * @param slotValues Current slot values (for substituting {slot_name} in specs)
     * @return Best match, or null if nothing matches above threshold
     */
    fun findBestMatch(
        root: AccessibilityNodeInfo,
        spec: TargetSpec,
        slotValues: Map<String, String> = emptyMap()
    ): MatchResult? {
        val resolvedSpec = resolveSlots(spec, slotValues)
        val allNodes = collectAllNodes(root)

        val scored = allNodes.mapNotNull { node ->
            try {
                val score = scoreNode(node, resolvedSpec)
                if (score > 0.3f) {
                    MatchResult(node, score, describeMatch(node, resolvedSpec))
                } else null
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.score }

        // Use the configured threshold from Constants
        val threshold = Constants.NODE_MATCH_THRESHOLD
        val best = scored.firstOrNull()?.takeIf { it.score >= threshold }
        if (best != null) {
            Log.d(TAG, "Matched best node: score=${best.score}, details=${best.matchDetails}")
        } else {
            val topScore = scored.firstOrNull()?.score
            Log.d(TAG, "No node matched threshold ($threshold) for spec: $resolvedSpec (top score: $topScore)")
        }
        return best
    }

    /**
     * Find ALL matching nodes (for cases where we need to disambiguate).
     */
    fun findAllMatches(
        root: AccessibilityNodeInfo,
        spec: TargetSpec,
        slotValues: Map<String, String> = emptyMap()
    ): List<MatchResult> {
        val resolvedSpec = resolveSlots(spec, slotValues)
        val allNodes = collectAllNodes(root)

        return allNodes.mapNotNull { node ->
            try {
                val score = scoreNode(node, resolvedSpec)
                if (score > 0.3f) {
                    MatchResult(node, score, describeMatch(node, resolvedSpec))
                } else null
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.score }
    }

    /**
     * Replace {slot_name} placeholders with actual values.
     */
    private fun resolveSlots(spec: TargetSpec, slots: Map<String, String>): TargetSpec {
        if (slots.isEmpty()) return spec

        fun String.resolveSlot(): String {
            var result = this
            for ((key, value) in slots) {
                result = result.replace("{$key}", value, ignoreCase = true)
            }
            return result
        }

        val resolvedText = spec.text?.resolveSlot()
        var resolvedResId = spec.resourceId?.resolveSlot()

        // If the text was parameterized and changed, adjust or strip any digit-specific resourceId
        // so a hardcoded digit_5 doesn't lock onto button 5 when the slot value is 10 or 7.
        if (spec.text != null && spec.text.contains('{') && resolvedText != null) {
            val oldId = resolvedResId ?: ""
            if (oldId.contains("digit_") || oldId.contains("btn_")) {
                if (resolvedText.length == 1 && resolvedText[0].isDigit()) {
                    resolvedResId = oldId.replace(Regex("digit_\\d"), "digit_${resolvedText}")
                        .replace(Regex("btn_\\d"), "btn_${resolvedText}")
                } else {
                    // Multi-digit or non-digit: clear resourceId so sequential keypad or text matching takes over
                    resolvedResId = null
                }
            }
        }

        return spec.copy(
            text = resolvedText,
            textContains = spec.textContains?.resolveSlot(),
            contentDescription = spec.contentDescription?.resolveSlot(),
            contextTextContains = spec.contextTextContains?.resolveSlot(),
            hintText = spec.hintText?.resolveSlot(),
            resourceId = resolvedResId,
            semantic = spec.semantic?.resolveSlot()
        )
    }

    /**
     * Score a node against the target spec. Returns 0.0 - 1.0.
     */
    private fun scoreNode(node: AccessibilityNodeInfo, spec: TargetSpec): Float {
        try {
            if (!node.isVisibleToUser) return 0f
            if (!node.isEnabled) return 0f
        } catch (e: Exception) {
            return 0f
        }

        val nodeText = try { node.text?.toString() ?: "" } catch (e: Exception) { "" }
        val nodeDesc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }
        val rawNodeId = try { node.viewIdResourceName ?: "" } catch (e: Exception) { "" }
        val nodeResId = rawNodeId.substringAfterLast('/')

        // Fast-path exact resource ID match (ONLY when spec.text is null or does not conflict with node text)
        if (spec.resourceId != null) {
            val specId = spec.resourceId
            val idMatches = rawNodeId.equals(specId, ignoreCase = true) ||
                (specId.isNotEmpty() && rawNodeId.endsWith("/" + specId.substringAfterLast('/')))
            if (idMatches) {
                val textConsistent = spec.text == null ||
                    nodeText.isBlank() ||
                    matchesSymbolOrSynonym(spec.text, nodeText) ||
                    matchesSymbolOrSynonym(spec.text, nodeDesc)
                if (textConsistent) {
                    return if (node.isClickable) 0.95f else 0.85f
                }
            }
        }

        // Fast-path direct text / contentDescription / symbol match for buttons and controls
        if (spec.text != null) {
            if (matchesSymbolOrSynonym(spec.text, nodeText) ||
                matchesSymbolOrSynonym(spec.text, nodeDesc) ||
                matchesSymbolOrSynonym(spec.text, nodeResId)) {
                return if (node.isClickable) 0.95f else 0.85f
            }
        }
        if (spec.contentDescription != null) {
            if (matchesSymbolOrSynonym(spec.contentDescription, nodeDesc) ||
                matchesSymbolOrSynonym(spec.contentDescription, nodeText) ||
                matchesSymbolOrSynonym(spec.contentDescription, nodeResId)) {
                return if (node.isClickable) 0.92f else 0.80f
            }
        }

        var totalWeight = 0f
        var score = 0f

        // 1. Resource ID match (weight: 0.25)
        if (spec.resourceId != null) {
            totalWeight += 0.25f
            val nodeId = try { node.viewIdResourceName ?: "" } catch (e: Exception) { "" }
            val specId = spec.resourceId
            if (nodeId.equals(specId, ignoreCase = true)) {
                score += 0.25f
            } else if (specId.isNotEmpty() && nodeId.contains(specId.substringAfterLast('/'), ignoreCase = true)) {
                score += 0.20f
            }
        }

        // 2. Text match (weight: 0.35)
        if (spec.text != null || spec.textContains != null) {
            totalWeight += 0.35f

            if (spec.text != null) {
                when {
                    nodeText.equals(spec.text, ignoreCase = true) -> score += 0.35f
                    nodeText.contains(spec.text, ignoreCase = true) -> score += 0.25f
                    nodeText.fuzzyContains(spec.text) -> score += 0.15f
                }
            }
            if (spec.textContains != null) {
                when {
                    nodeText.contains(spec.textContains, ignoreCase = true) -> score += 0.35f
                    nodeText.fuzzyContains(spec.textContains) -> score += 0.15f
                }
            }
        }

        // 3. Content description match (weight: 0.15)
        if (spec.contentDescription != null) {
            totalWeight += 0.15f
            val nodeDesc = try { node.contentDescription?.toString() ?: "" } catch (e: Exception) { "" }
            if (nodeDesc.contains(spec.contentDescription, ignoreCase = true)) {
                score += 0.15f
            } else if (nodeDesc.fuzzyContains(spec.contentDescription)) {
                score += 0.10f
            }
        }

        // 4. Class name match (weight: 0.05)
        if (spec.className != null) {
            totalWeight += 0.05f
            val nodeClass = try { node.className?.toString() ?: "" } catch (e: Exception) { "" }
            if (nodeClass.equals(spec.className, ignoreCase = true) ||
                nodeClass.substringAfterLast('.').equals(spec.className.substringAfterLast('.'), ignoreCase = true)) {
                score += 0.05f
            }
        }

        // 5. Editability match (weight: 0.10)
        if (spec.isEditable != null) {
            totalWeight += 0.10f
            val isEditable = try { node.isEditable } catch (e: Exception) { false }
            if (isEditable == spec.isEditable) {
                score += 0.10f
            }
        }

        // 6. Hint text match (weight: 0.10)
        if (spec.hintText != null) {
            totalWeight += 0.10f
            val nodeHint = try { node.hintText?.toString() ?: "" } catch (e: Exception) { "" }
            if (nodeHint.contains(spec.hintText, ignoreCase = true)) {
                score += 0.10f
            }
        }

        // 7. Context match — CRITICAL for disambiguating identical elements (weight: 0.25)
        if (spec.contextTextContains != null) {
            totalWeight += 0.25f
            val contextTexts = getContextTexts(node)
            val contextString = contextTexts.joinToString(" ").lowercase()
            val target = spec.contextTextContains.lowercase()

            when {
                contextString.contains(target) -> score += 0.25f
                contextString.fuzzyContains(target) -> score += 0.15f
            }
        }

        return if (totalWeight > 0f) score / totalWeight else 0f
    }

    /**
     * Get text from parent and sibling nodes for context.
     */
    private fun getContextTexts(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        try {
            val parent = node.parent ?: return texts
            parent.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            parent.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue
                try {
                    sibling.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    sibling.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

                    for (j in 0 until minOf(sibling.childCount, 4)) {
                        val grandchild = sibling.getChild(j) ?: continue
                        try {
                            grandchild.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                        } finally {
                            grandchild.recycle()
                        }
                    }
                } finally {
                    sibling.recycle()
                }
            }

            val grandparent = parent.parent
            if (grandparent != null) {
                try {
                    grandparent.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    for (i in 0 until minOf(grandparent.childCount, 4)) {
                        val uncle = grandparent.getChild(i) ?: continue
                        try {
                            uncle.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                        } finally {
                            uncle.recycle()
                        }
                    }
                } finally {
                    grandparent.recycle()
                }
            }

            parent.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Error collecting context texts", e)
        }
        return texts.distinct()
    }

    /**
     * Match a spec string against a candidate string, handling math symbols, synonyms,
     * and OEM-specific naming (e.g. "+" vs "Add" vs "op_add").
     */
    private fun matchesSymbolOrSynonym(specText: String, candidate: String): Boolean {
        if (candidate.isBlank() || specText.isBlank()) return false
        if (candidate.equals(specText, ignoreCase = true)) return true

        val s = specText.trim().lowercase()
        val c = candidate.trim().lowercase()

        if (s == c) return true

        return when (s) {
            "+", "plus", "add", "addition", "op_add" ->
                c in listOf("+", "plus", "add", "addition", "op_add") || c.contains("add") || c.contains("plus")
            "-", "−", "minus", "sub", "subtract", "subtraction", "op_sub" ->
                c in listOf("-", "−", "minus", "sub", "subtract", "subtraction", "op_sub") || c.contains("sub") || c.contains("minus")
            "*", "×", "x", "mul", "multiply", "multiplication", "times", "op_mul" ->
                c in listOf("*", "×", "x", "mul", "multiply", "multiplication", "times", "op_mul") || c.contains("mul") || c.contains("times")
            "/", "÷", "div", "divide", "division", "op_div" ->
                c in listOf("/", "÷", "div", "divide", "division", "op_div") || c.contains("div")
            "=", "equals", "equal", "result", "calculate", "eq" ->
                c in listOf("=", "equals", "equal", "result", "calculate", "eq") || c.contains("equal")
            ".", "point", "dot", "decimal", "dec_point" ->
                c in listOf(".", "point", "dot", "decimal", "dec_point") || c.contains("point") || c.contains("dot")
            "c", "ac", "clear", "all clear", "clr" ->
                c in listOf("c", "ac", "clear", "all clear", "clr") || c.contains("clear")
            "del", "delete", "backspace" ->
                c in listOf("del", "delete", "backspace") || c.contains("del") || c.contains("backspace")
            "0", "zero", "digit_0" -> c in listOf("0", "zero", "digit_0")
            "1", "one", "digit_1" -> c in listOf("1", "one", "digit_1")
            "2", "two", "digit_2" -> c in listOf("2", "two", "digit_2")
            "3", "three", "digit_3" -> c in listOf("3", "three", "digit_3")
            "4", "four", "digit_4" -> c in listOf("4", "four", "digit_4")
            "5", "five", "digit_5" -> c in listOf("5", "five", "digit_5")
            "6", "six", "digit_6" -> c in listOf("6", "six", "digit_6")
            "7", "seven", "digit_7" -> c in listOf("7", "seven", "digit_7")
            "8", "eight", "digit_8" -> c in listOf("8", "eight", "digit_8")
            "9", "nine", "digit_9" -> c in listOf("9", "nine", "digit_9")
            else -> false
        }
    }

    /**
     * Collect all nodes in the tree (flattened).
     */
    private fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            nodes.add(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                traverse(child, depth + 1)
            }
        }
        traverse(root, 0)
        return nodes
    }

    /**
     * Human-readable description of what matched.
     */
    private fun describeMatch(node: AccessibilityNodeInfo, spec: TargetSpec): String {
        val parts = mutableListOf<String>()
        try {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) parts.add("text=\"$text\"")

            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (desc != null) parts.add("desc=\"$desc\"")

            val resId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
            if (resId != null) parts.add("id=\"${resId.substringAfterLast('/')}\"")

            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            parts.add("class=$cls")
        } catch (e: Exception) {
            parts.add("node(stale)")
        }
        return parts.joinToString(", ")
    }
}
