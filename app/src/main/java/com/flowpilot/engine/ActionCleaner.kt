package com.flowpilot.engine

import com.flowpilot.data.models.ActionType
import com.flowpilot.data.models.RecordedAction

class ActionCleaner {

    companion object {
        /**
         * Maximum actions to send to Gemini for synthesis.
         * More than this overflows the model's effective context and causes timeouts.
         */
        private const val MAX_ACTIONS_FOR_SYNTHESIS = 25
    }

    /**
     * Clean raw recorded actions:
     * 1. Filter by target package (remove actions in launcher/other apps)
     * 2. Merge rapid duplicate clicks (same element clicked within 350ms)
     * 3. Merge consecutive text inputs on the same field into the final text value
     * 4. Remove scroll-undo pairs (scroll down then immediately up)
     * 5. Collapse consecutive scrolls in the same direction into a single scroll
     * 6. Cap total actions at MAX_ACTIONS_FOR_SYNTHESIS
     * 7. Re-index remaining actions
     */
    fun clean(
        actions: List<RecordedAction>,
        targetPackage: String
    ): List<RecordedAction> {
        if (actions.isEmpty()) return emptyList()

        val cleaned = actions
            .filterByPackage(targetPackage)
            .mergeDuplicateClicks()
            .mergeTextInputs()
            .removeScrollUndos()
            .collapseConsecutiveScrolls()
            .capActions()
            .reindex()

        return if (cleaned.isNotEmpty()) {
            cleaned
        } else {
            actions.filter { !com.flowpilot.util.Constants.isSystemOrLauncherPackage(it.packageName) }
                .mapIndexed { idx, action -> action.copy(index = idx) }
        }
    }

    private fun List<RecordedAction>.filterByPackage(pkg: String): List<RecordedAction> {
        val nonSystem = filter { !com.flowpilot.util.Constants.isSystemOrLauncherPackage(it.packageName) }
        if (pkg.isBlank()) return nonSystem

        val token = if (pkg.contains('.')) {
            pkg.split('.').filter { it !in listOf("com", "android", "apps", "app", "google", "application") }.lastOrNull() ?: pkg
        } else pkg

        val filtered = nonSystem.filter { action ->
            action.packageName.contains(token, ignoreCase = true) ||
            action.type == ActionType.TYPE ||
            action.packageName.contains("keyboard", ignoreCase = true) ||
            action.packageName.contains("inputmethod", ignoreCase = true)
        }
        return if (filtered.isNotEmpty()) filtered else nonSystem
    }

    private fun List<RecordedAction>.mergeDuplicateClicks(): List<RecordedAction> {
        val result = mutableListOf<RecordedAction>()
        for (action in this) {
            val last = result.lastOrNull()
            if (last != null &&
                action.type == ActionType.CLICK &&
                last.type == ActionType.CLICK &&
                action.timestamp - last.timestamp < 350L &&
                isSameTarget(action, last)
            ) {
                result[result.lastIndex] = action
            } else {
                result.add(action)
            }
        }
        return result
    }

    private fun List<RecordedAction>.mergeTextInputs(): List<RecordedAction> {
        val result = mutableListOf<RecordedAction>()
        for (action in this) {
            val last = result.lastOrNull()
            if (last != null &&
                action.type == ActionType.TYPE &&
                last.type == ActionType.TYPE &&
                isSameTarget(action, last)
            ) {
                result[result.lastIndex] = action
            } else {
                result.add(action)
            }
        }
        return result
    }

    private fun List<RecordedAction>.removeScrollUndos(): List<RecordedAction> {
        val result = mutableListOf<RecordedAction>()
        for (action in this) {
            val last = result.lastOrNull()
            if (last != null &&
                action.type == ActionType.SCROLL &&
                last.type == ActionType.SCROLL &&
                action.timestamp - last.timestamp < 1500L &&
                isOppositeScroll(action, last)
            ) {
                result.removeAt(result.lastIndex)
            } else {
                result.add(action)
            }
        }
        return result
    }

    /**
     * Collapse consecutive scrolls in the same direction into a single scroll.
     * e.g., 5 consecutive "scroll DOWN" actions become 1 scroll DOWN.
     */
    private fun List<RecordedAction>.collapseConsecutiveScrolls(): List<RecordedAction> {
        val result = mutableListOf<RecordedAction>()
        for (action in this) {
            val last = result.lastOrNull()
            if (last != null &&
                action.type == ActionType.SCROLL &&
                last.type == ActionType.SCROLL &&
                isSameScrollDirection(action, last)
            ) {
                // Keep just one scroll in this direction — skip the duplicate
                continue
            } else {
                result.add(action)
            }
        }
        return result
    }

    /**
     * If there are still too many actions after cleaning, keep the most important ones:
     * - Always keep the first 5 (app opening, initial navigation)
     * - Always keep the last 5 (final actions, confirming)
     * - Sample from the middle
     */
    private fun List<RecordedAction>.capActions(): List<RecordedAction> {
        if (size <= MAX_ACTIONS_FOR_SYNTHESIS) return this

        val head = take(8)
        val tail = takeLast(8)
        val middle = drop(8).dropLast(8)

        // Sample from middle: keep every Nth action
        val remaining = MAX_ACTIONS_FOR_SYNTHESIS - head.size - tail.size
        val step = if (remaining > 0 && middle.isNotEmpty()) {
            (middle.size.toFloat() / remaining).coerceAtLeast(1f)
        } else 1f

        val sampledMiddle = if (remaining > 0 && middle.isNotEmpty()) {
            val indices = (0 until remaining).map { (it * step).toInt().coerceAtMost(middle.lastIndex) }.distinct()
            indices.map { middle[it] }
        } else emptyList()

        return head + sampledMiddle + tail
    }

    private fun List<RecordedAction>.reindex(): List<RecordedAction> {
        return mapIndexed { idx, action -> action.copy(index = idx) }
    }

    private fun isSameTarget(a: RecordedAction, b: RecordedAction): Boolean {
        val nodeA = a.targetNode ?: return false
        val nodeB = b.targetNode ?: return false

        if (nodeA.resourceId != null && nodeB.resourceId != null) {
            return nodeA.resourceId == nodeB.resourceId
        }
        if (!nodeA.text.isNullOrBlank() && !nodeB.text.isNullOrBlank()) {
            return nodeA.text == nodeB.text
        }
        if (!nodeA.contentDescription.isNullOrBlank() && !nodeB.contentDescription.isNullOrBlank()) {
            return nodeA.contentDescription == nodeB.contentDescription
        }
        return nodeA.className == nodeB.className &&
                Math.abs(nodeA.bounds.centerX - nodeB.bounds.centerX) < 30 &&
                Math.abs(nodeA.bounds.centerY - nodeB.bounds.centerY) < 30
    }

    private fun isOppositeScroll(a: RecordedAction, b: RecordedAction): Boolean {
        val dirA = a.data["direction"] ?: return false
        val dirB = b.data["direction"] ?: return false
        return (dirA == "DOWN" && dirB == "UP") || (dirA == "UP" && dirB == "DOWN")
    }

    private fun isSameScrollDirection(a: RecordedAction, b: RecordedAction): Boolean {
        val dirA = a.data["direction"] ?: "DOWN"
        val dirB = b.data["direction"] ?: "DOWN"
        return dirA == dirB
    }
}
