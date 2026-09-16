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

        val best = scored.firstOrNull()?.takeIf { it.score >= Constants.NODE_MATCH_THRESHOLD }
        if (best != null) {
            Log.d(TAG, "Matched best node: score=${best.score}, details=${best.matchDetails}")
        } else {
            Log.d(TAG, "No node matched threshold (${Constants.NODE_MATCH_THRESHOLD}) for spec: $resolvedSpec")
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

        return spec.copy(
            text = spec.text?.resolveSlot(),
            textContains = spec.textContains?.resolveSlot(),
            contentDescription = spec.contentDescription?.resolveSlot(),
            contextTextContains = spec.contextTextContains?.resolveSlot()
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
                score += 0.15f
            }
        }

        // 2. Text match (weight: 0.30)
        if (spec.text != null || spec.textContains != null) {
            totalWeight += 0.30f
            val nodeText = try { node.text?.toString() ?: "" } catch (e: Exception) { "" }

            if (spec.text != null) {
                when {
                    nodeText.equals(spec.text, ignoreCase = true) -> score += 0.30f
                    nodeText.contains(spec.text, ignoreCase = true) -> score += 0.25f
                    nodeText.fuzzyContains(spec.text) -> score += 0.15f
                }
            }
            if (spec.textContains != null) {
                when {
                    nodeText.contains(spec.textContains, ignoreCase = true) -> score += 0.30f
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
