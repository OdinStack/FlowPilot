package com.flowpilot.data.models

import android.graphics.Rect
import kotlinx.serialization.Serializable

@Serializable
data class BoundsRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = top - bottom

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromAndroidRect(rect: Rect): BoundsRect =
            BoundsRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

@Serializable
data class UINode(
    val id: String,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val hintText: String? = null,
    val bounds: BoundsRect,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val isFocused: Boolean = false,
    val isFocusable: Boolean = false,
    val inputType: Int? = null,
    val depth: Int = 0,
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    val packageName: String = ""
) {
    fun toShortString(): String {
        val parts = mutableListOf<String>()
        text?.let { parts.add("text=\"$it\"") }
        contentDescription?.let { parts.add("desc=\"$it\"") }
        resourceId?.let { parts.add("id=\"${it.substringAfterLast('/')}\"") }
        if (isClickable) parts.add("clickable")
        if (isEditable) parts.add("editable")
        if (isScrollable) parts.add("scrollable")
        val cls = className.substringAfterLast('.')
        return "$cls[${parts.joinToString(", ")}]"
    }
}

@Serializable
data class UISnapshot(
    val timestamp: Long,
    val packageName: String,
    val nodes: List<UINode>,
    val rootNodeId: String,
    val screenSignature: String,
    val interactiveNodeCount: Int = 0,
    val visibleTextSummary: List<String> = emptyList()
)
