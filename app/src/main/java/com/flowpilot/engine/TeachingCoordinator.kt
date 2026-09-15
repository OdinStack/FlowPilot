package com.flowpilot.engine

import android.content.Context
import android.util.Log
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.data.models.ActionType
import com.flowpilot.data.models.RecordedAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class TeachingResult(
    val utterance: String,
    val actions: List<RecordedAction>,
    val targetPackage: String,
    val timestamp: Long
)

class TeachingCoordinator(
    private val context: Context,
    private val stateMachine: SystemStateMachine,
    private val actionCleaner: ActionCleaner = ActionCleaner()
) {
    companion object {
        private const val TAG = "TeachingCoordinator"
        var activeInstance: TeachingCoordinator? = null
            private set
    }

    init {
        activeInstance = this
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var actionCollectionJob: Job? = null
    private val rawActions = mutableListOf<RecordedAction>()
    private var currentUtterance: String = ""
    private var detectedTargetPackage: String? = null

    private val _isTeaching = MutableStateFlow(false)
    val isTeaching: StateFlow<Boolean> = _isTeaching.asStateFlow()

    private val _lastCapturedSummary = MutableStateFlow("")
    val lastCapturedSummary: StateFlow<String> = _lastCapturedSummary.asStateFlow()

    private val _recordedActionsCount = MutableStateFlow(0)
    val recordedActionsCount: StateFlow<Int> = _recordedActionsCount.asStateFlow()

    private val _teachingResults = kotlinx.coroutines.flow.MutableSharedFlow<TeachingResult>(extraBufferCapacity = 1)
    val teachingResults: kotlinx.coroutines.flow.SharedFlow<TeachingResult> = _teachingResults

    private val notificationManager = TeachingNotificationManager(context)

    fun startTeaching(utterance: String) {
        Log.i(TAG, "Starting teaching for: \"$utterance\"")
        currentUtterance = utterance
        rawActions.clear()
        detectedTargetPackage = null
        _isTeaching.value = true
        _recordedActionsCount.value = 0
        _lastCapturedSummary.value = ""

        stateMachine.transition(SystemMode.TEACHING, "🔴 Recording — perform the steps in your target app")
        notificationManager.showRecordingNotification(utterance, 0)

        val service = FlowPilotAccessibilityService.instance
        if (service != null) {
            service.startRecording(null)
        } else {
            Log.w(TAG, "AccessibilityService not connected yet")
        }

        actionCollectionJob?.cancel()
        actionCollectionJob = scope.launch {
            FlowPilotAccessibilityService.recordedActions.collect { action ->
                rawActions.add(action)
                _recordedActionsCount.value = rawActions.size

                if (detectedTargetPackage == null &&
                    action.packageName.isNotBlank() &&
                    !com.flowpilot.util.Constants.isSystemOrLauncherPackage(action.packageName) &&
                    action.packageName != context.packageName
                ) {
                    detectedTargetPackage = action.packageName
                    Log.i(TAG, "Teaching locked to target package: $detectedTargetPackage")
                }

                val summary = action.toShortString()
                _lastCapturedSummary.value = summary
                stateMachine.updateActionCount(rawActions.size, summary)
                notificationManager.updateNotification(rawActions.size, summary)
            }
        }
    }

    fun stopTeaching(): TeachingResult {
        Log.i(TAG, "Stopping teaching. Captured ${rawActions.size} actions.")
        _isTeaching.value = false
        actionCollectionJob?.cancel()
        notificationManager.dismissNotification()

        val service = FlowPilotAccessibilityService.instance
        val finalActions = service?.stopRecording() ?: rawActions.toList()

        val targetPkg = finalActions
            .filter { it.type == ActionType.CLICK || it.type == ActionType.TYPE }
            .map { it.packageName }
            .filter { !com.flowpilot.util.Constants.isSystemOrLauncherPackage(it) && it != context.packageName }
            .groupBy { it }
            .maxByOrNull { it.value.size }?.key
            ?: detectedTargetPackage
            ?: finalActions.firstOrNull {
                !com.flowpilot.util.Constants.isSystemOrLauncherPackage(it.packageName) &&
                it.packageName != context.packageName
            }?.packageName
            ?: ""

        val cleanedActions = actionCleaner.clean(finalActions, targetPkg)
        Log.i(TAG, "Cleaned actions: ${cleanedActions.size} (raw: ${finalActions.size}) for $targetPkg")

        stateMachine.transition(
            SystemMode.SYNTHESIZING,
            "Captured ${cleanedActions.size} actions for ${targetPkg.substringAfterLast('.')}"
        )

        val result = TeachingResult(
            utterance = currentUtterance,
            actions = cleanedActions,
            targetPackage = targetPkg,
            timestamp = System.currentTimeMillis()
        )
        _teachingResults.tryEmit(result)
        return result
    }

    fun cancelTeaching() {
        Log.i(TAG, "Cancelled teaching")
        _isTeaching.value = false
        actionCollectionJob?.cancel()
        notificationManager.dismissNotification()
        FlowPilotAccessibilityService.instance?.stopRecording()
        rawActions.clear()
        stateMachine.reset()
    }
}
