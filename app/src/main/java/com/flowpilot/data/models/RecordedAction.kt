package com.flowpilot.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType {
    CLICK,
    LONG_CLICK,
    TYPE,
    SCROLL,
    BACK,
    SELECT,
    CHECK,
    SCREEN_TRANSITION
}

@Serializable
data class RecordedAction(
    val index: Int,
    val type: ActionType,
    val targetNode: UINode? = null,
    val data: Map<String, String> = emptyMap(),
    val beforeScreenSignature: String? = null,
    val afterScreenSignature: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String = ""
) {
    fun toShortString(): String {
        return when (type) {
            ActionType.SCREEN_TRANSITION -> {
                val win = data["newWindow"]?.substringAfterLast('.') ?: "Screen"
                val title = data["windowTitle"]?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: ""
                "[$index] SCREEN: $win$title in $packageName"
            }
            ActionType.TYPE -> {
                val target = targetNode?.toShortString() ?: "field"
                "[$index] TYPE: \"${data["text"] ?: ""}\" into $target"
            }
            ActionType.SCROLL -> {
                val target = targetNode?.toShortString() ?: "view"
                "[$index] SCROLL: ${data["direction"] ?: "DOWN"} on $target"
            }
            else -> {
                val target = targetNode?.toShortString() ?: "unknown"
                "[$index] ${type.name}: $target"
            }
        }
    }
}
