package com.flowpilot.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.data.models.BoundsRect
import com.flowpilot.data.models.UINode
import com.flowpilot.data.models.UISnapshot
import java.util.UUID

class UITreeParser {

    /**
     * Capture a full snapshot of the current UI tree.
     */
    fun captureSnapshot(root: AccessibilityNodeInfo): UISnapshot {
        val nodes = mutableListOf<UINode>()
        val idMap = mutableMapOf<AccessibilityNodeInfo, String>()
        val visibleTexts = mutableListOf<String>()
        var interactiveCount = 0

        fun traverse(
            node: AccessibilityNodeInfo,
            depth: Int,
            parentId: String?,
            nodeId: String = UUID.randomUUID().toString()
        ) {
            idMap[node] = nodeId

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (node.isVisibleToUser) {
                text?.takeIf { it.isNotBlank() }?.let { visibleTexts.add(it) }
                desc?.takeIf { it.isNotBlank() && text == null }?.let { visibleTexts.add(it) }
            }
            if (node.isClickable || node.isEditable || node.isScrollable) {
                interactiveCount++
            }

            // Pre-generate child IDs so parent's childIds matches child's id
            val childCount = node.childCount
            val childIds = (0 until childCount).map { UUID.randomUUID().toString() }

            val uiNode = UINode(
                id = nodeId,
                className = node.className?.toString() ?: "unknown",
                text = text,
                contentDescription = desc,
                resourceId = node.viewIdResourceName,
                hintText = node.hintText?.toString(),
                bounds = BoundsRect.fromAndroidRect(bounds),
                isClickable = node.isClickable,
                isEnabled = node.isEnabled,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isSelected = node.isSelected,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                isVisibleToUser = node.isVisibleToUser,
                isFocused = node.isFocused,
                isFocusable = node.isFocusable,
                inputType = if (node.isEditable) node.inputType else null,
                depth = depth,
                parentId = parentId,
                childIds = childIds,
                packageName = node.packageName?.toString() ?: ""
            )
            nodes.add(uiNode)

            // Recurse into children with matching IDs
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1, nodeId, childIds[i])
                child.recycle()
            }
        }

        traverse(root, 0, null)

        val rootId = idMap[root] ?: ""
        val signature = computeScreenSignature(root)

        return UISnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = root.packageName?.toString() ?: "",
            nodes = nodes,
            rootNodeId = rootId,
            screenSignature = signature,
            interactiveNodeCount = interactiveCount,
            visibleTextSummary = visibleTexts.take(30)
        )
    }

    /**
     * Compute a structural signature for quick screen comparison.
     * Same signature = likely same screen.
     */
    fun computeScreenSignature(root: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        parts.add(root.packageName?.toString() ?: "unknown")

        // Collect key structural info: first few visible texts, interactive element count
        val keyTexts = mutableListOf<String>()
        var clickableCount = 0
        var editableCount = 0

        fun quickScan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 3) return // Only scan top levels for speed
            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                if (text != null && text.isNotBlank() && keyTexts.size < 5) {
                    keyTexts.add(text.take(30))
                }
                if (node.isClickable) clickableCount++
                if (node.isEditable) editableCount++
            }
            for (i in 0 until minOf(node.childCount, 10)) {
                val child = node.getChild(i) ?: continue
                quickScan(child, depth + 1)
                child.recycle()
            }
        }
        quickScan(root, 0)

        parts.addAll(keyTexts.sorted())
        parts.add("c=$clickableCount")
        parts.add("e=$editableCount")

        return parts.joinToString("|").hashCode().toString(16)
    }

    /**
     * Get context texts from a node's parent and siblings.
     * Used for understanding what an element is associated with.
     */
    fun getContextTexts(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()

        val parent = node.parent ?: return texts
        parent.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        parent.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            sibling.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            sibling.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

            // One level deeper for richer context
            for (j in 0 until minOf(sibling.childCount, 5)) {
                val grandchild = sibling.getChild(j) ?: continue
                grandchild.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                grandchild.recycle()
            }
            sibling.recycle()
        }

        // Walk up one more level
        val grandparent = parent.parent
        if (grandparent != null) {
            grandparent.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            for (i in 0 until minOf(grandparent.childCount, 5)) {
                val uncle = grandparent.getChild(i) ?: continue
                uncle.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                uncle.recycle()
            }
            grandparent.recycle()
        }
        parent.recycle()

        return texts.distinct()
    }

    /**
     * Build a UINode from an AccessibilityNodeInfo for recording purposes.
     * Does NOT recycle the node.
     */
    fun buildUINode(node: AccessibilityNodeInfo, depth: Int = 0): UINode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val contextTexts = getContextTexts(node)

        return UINode(
            id = UUID.randomUUID().toString(),
            className = node.className?.toString() ?: "unknown",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            hintText = node.hintText?.toString(),
            bounds = BoundsRect.fromAndroidRect(bounds),
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isSelected = node.isSelected,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isVisibleToUser = node.isVisibleToUser,
            isFocused = node.isFocused,
            isFocusable = node.isFocusable,
            inputType = if (node.isEditable) node.inputType else null,
            depth = depth,
            packageName = node.packageName?.toString() ?: ""
        )
    }
}
