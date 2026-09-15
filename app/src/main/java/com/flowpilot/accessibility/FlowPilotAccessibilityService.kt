package com.flowpilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.data.models.ActionType
import com.flowpilot.data.models.RecordedAction
import com.flowpilot.data.models.UISnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlowPilotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FlowPilotA11y"

        var instance: FlowPilotAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentSnapshot = MutableStateFlow<UISnapshot?>(null)
        val currentSnapshot: StateFlow<UISnapshot?> = _currentSnapshot.asStateFlow()

        private val _recordedActions = MutableSharedFlow<RecordedAction>(extraBufferCapacity = 64)
        val recordedActions: SharedFlow<RecordedAction> = _recordedActions.asSharedFlow()

        private val _lastActionDescription = MutableStateFlow("")
        val lastActionDescription: StateFlow<String> = _lastActionDescription.asStateFlow()
    }

    val uiTreeParser = UITreeParser()
    val actionExecutor: ActionExecutor by lazy { ActionExecutor(this) }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Recording state
    private var isRecording = false
    private var recordingPackage: String? = null
    private var actionIndex = 0
    private val recordedActionsList = mutableListOf<RecordedAction>()

    // Text input debouncing
    private data class PendingText(
        val targetNode: com.flowpilot.data.models.UINode,
        var text: String,
        val timestamp: Long,
        val packageName: String
    )
    private var pendingTextInput: PendingText? = null

    // Track last screen signature for detecting transitions
    private var lastScreenSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 50
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure serviceInfo", e)
        }
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackage = event.packageName?.toString() ?: return

        // Always update snapshot on window changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            updateSnapshot()
        }

        // Recording mode: capture user actions
        if (isRecording) {
            // Ignore events from FlowPilot itself so clicking buttons inside FlowPilot isn't recorded as part of the flow
            if (eventPackage == packageName && recordingPackage != packageName) {
                return
            }

            // If a specific target package was locked, filter out events from other apps
            if (recordingPackage != null && eventPackage != recordingPackage) {
                return
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleLongClick(event)
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
                AccessibilityEvent.TYPE_VIEW_SELECTED -> handleSelection(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        _isServiceConnected.value = false
        scope.cancel()
        Log.i(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    // ─── PUBLIC API ───────────────────────────────────────────────────────

    /**
     * Start recording user actions for teaching mode.
     * @param targetPackage Package name of the app being taught (filter other apps), or null to auto-lock to the first external app
     */
    fun startRecording(targetPackage: String? = null) {
        Log.i(TAG, "Started recording for package: ${targetPackage ?: "auto-detect"}")
        isRecording = true
        recordingPackage = targetPackage
        actionIndex = 0
        recordedActionsList.clear()
        pendingTextInput = null
        lastScreenSignature = null
    }

    /**
     * Stop recording and return the captured actions.
     */
    fun stopRecording(): List<RecordedAction> {
        Log.i(TAG, "Stopped recording. ${recordedActionsList.size} actions captured.")
        flushPendingText()
        isRecording = false
        recordingPackage = null
        val result = recordedActionsList.toList()
        recordedActionsList.clear()
        return result
    }

    /**
     * Check if recording is currently active.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Capture a snapshot of the current UI tree.
     */
    fun captureCurrentScreen(): UISnapshot? {
        val root = rootInActiveWindow ?: return null
        return try {
            uiTreeParser.captureSnapshot(root)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen", e)
            null
        }
        // Note: do NOT recycle rootInActiveWindow
    }

    /**
     * Find all nodes matching a text string in the current tree.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesRecursive(root, results) { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        }
        return results
    }

    /**
     * Find a node by its resource ID.
     */
    fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val results = root.findAccessibilityNodeInfosByViewId(resourceId)
        return results.firstOrNull()
    }

    // ─── PRIVATE EVENT HANDLERS ──────────────────────────────────────────

    private fun createFallbackNode(
        event: AccessibilityEvent,
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isScrollable: Boolean = false
    ): com.flowpilot.data.models.UINode {
        val text = event.text?.joinToString("")?.takeIf { it.isNotBlank() }
        val desc = event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val className = event.className?.toString() ?: "android.view.View"
        return com.flowpilot.data.models.UINode(
            id = java.util.UUID.randomUUID().toString(),
            className = className,
            text = text,
            contentDescription = desc,
            bounds = com.flowpilot.data.models.BoundsRect(0, 0, 0, 0),
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            packageName = event.packageName?.toString() ?: ""
        )
    }

    private fun handleClick(event: AccessibilityEvent) {
        flushPendingText()

        val source = event.source
        val targetNode = if (source != null) {
            val node = uiTreeParser.buildUINode(source)
            source.recycle()
            node
        } else {
            createFallbackNode(event, isClickable = true)
        }

        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.CLICK,
            targetNode = targetNode,
            beforeScreenSignature = lastScreenSignature,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )

        recordAction(action)
        scheduleSignatureUpdate()
    }

    private fun handleLongClick(event: AccessibilityEvent) {
        flushPendingText()

        val source = event.source
        val targetNode = if (source != null) {
            val node = uiTreeParser.buildUINode(source)
            source.recycle()
            node
        } else {
            createFallbackNode(event, isClickable = true)
        }

        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.LONG_CLICK,
            targetNode = targetNode,
            beforeScreenSignature = lastScreenSignature,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )

        recordAction(action)
        scheduleSignatureUpdate()
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val newText = event.text?.joinToString("") ?: ""
        val source = event.source
        val targetNode = if (source != null) {
            val node = uiTreeParser.buildUINode(source)
            source.recycle()
            node
        } else {
            createFallbackNode(event, isEditable = true)
        }

        // Debounce: update the pending text rather than recording every keystroke
        pendingTextInput = PendingText(
            targetNode = targetNode,
            text = newText,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )
    }

    private fun handleScroll(event: AccessibilityEvent) {
        val direction = if (event.scrollY > 0 || event.fromIndex < event.toIndex) "DOWN" else "UP"

        val source = event.source
        val targetNode = if (source != null) {
            val node = uiTreeParser.buildUINode(source)
            source.recycle()
            node
        } else {
            createFallbackNode(event, isScrollable = true)
        }

        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.SCROLL,
            targetNode = targetNode,
            data = mapOf("direction" to direction),
            beforeScreenSignature = lastScreenSignature,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )

        recordAction(action)
        scheduleSignatureUpdate()
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        flushPendingText()
        updateSnapshot()

        // Record as a screen transition marker
        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.SCREEN_TRANSITION,
            data = mapOf(
                "newWindow" to (event.className?.toString() ?: "unknown"),
                "windowTitle" to (event.text?.joinToString("") ?: "")
            ),
            beforeScreenSignature = lastScreenSignature,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )

        recordAction(action)
    }

    private fun handleSelection(event: AccessibilityEvent) {
        val source = event.source
        val targetNode = if (source != null) {
            val node = uiTreeParser.buildUINode(source)
            source.recycle()
            node
        } else {
            createFallbackNode(event)
        }

        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.SELECT,
            targetNode = targetNode,
            timestamp = System.currentTimeMillis(),
            packageName = event.packageName?.toString() ?: ""
        )

        recordAction(action)
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────

    private fun flushPendingText() {
        val pending = pendingTextInput ?: return
        pendingTextInput = null

        if (pending.text.isBlank()) return

        val action = RecordedAction(
            index = actionIndex++,
            type = ActionType.TYPE,
            targetNode = pending.targetNode,
            data = mapOf("text" to pending.text),
            beforeScreenSignature = lastScreenSignature,
            timestamp = pending.timestamp,
            packageName = pending.packageName
        )

        recordAction(action)
    }

    private fun recordAction(action: RecordedAction) {
        recordedActionsList.add(action)
        scope.launch {
            _recordedActions.emit(action)
            _lastActionDescription.value = action.toShortString()
        }
        Log.d(TAG, "Recorded: ${action.toShortString()}")
    }

    private fun updateSnapshot() {
        scope.launch {
            val snapshot = captureCurrentScreen()
            if (snapshot != null) {
                _currentSnapshot.value = snapshot
                lastScreenSignature = snapshot.screenSignature
            }
        }
    }

    private fun scheduleSignatureUpdate() {
        handler.postDelayed({
            scope.launch {
                val root = rootInActiveWindow ?: return@launch
                lastScreenSignature = uiTreeParser.computeScreenSignature(root)
            }
        }, 500)
    }

    private fun findNodesRecursive(
        root: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (predicate(root)) {
            results.add(AccessibilityNodeInfo.obtain(root))
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesRecursive(child, results, predicate)
            child.recycle()
        }
    }
}
