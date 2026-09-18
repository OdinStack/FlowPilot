package com.flowpilot.engine

import com.flowpilot.data.models.ActionType
import com.flowpilot.data.models.RecordedAction

class ActionCleaner {

    /**
     * Clean raw recorded actions:
     * 1. Filter by target package (remove actions in launcher/other apps)
     * 2. Merge rapid duplicate clicks (same element clicked within 350ms)
     * 3. Merge consecutive text inputs on the same field into the final text value
     * 4. Remove scroll-undo pairs (scroll down then immediately up)
     * 5. Re-index remaining actions
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
                // Keep only the latest click
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
                // Keep the latest typed text
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
                // Cancel out the undo scroll
                result.removeAt(result.lastIndex)
            } else {
                result.add(action)
            }
        }
        return result
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
}
