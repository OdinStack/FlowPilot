package com.flowpilot.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowpilot.FlowPilotApp
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.accessibility.SafetyDetector
import com.flowpilot.accessibility.ScreenAnalyzer
import com.flowpilot.ai.IntentResult
import com.flowpilot.data.models.Workflow
import com.flowpilot.data.repository.ExecutionLog
import com.flowpilot.engine.ClarificationManager
import com.flowpilot.engine.RecoveryManager
import com.flowpilot.engine.ReplayEngine
import com.flowpilot.engine.SystemMode
import com.flowpilot.engine.SystemStateMachine
import com.flowpilot.engine.TeachingCoordinator
import com.flowpilot.engine.TeachingResult
import com.flowpilot.voice.VoiceManager
import com.flowpilot.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReplayFailureInfo(
    val workflowName: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val stepDescription: String,
    val reason: String,
    val suggestion: String,
    val workflow: Workflow,
    val slotValues: Map<String, String>
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val app = application as FlowPilotApp

    val stateMachine = SystemStateMachine()
    val voiceManager = VoiceManager(application)
    val teachingCoordinator = TeachingCoordinator(application, stateMachine)
    private val recoveryManager = RecoveryManager(
        screenAnalyzer = ScreenAnalyzer(),
        safetyDetector = SafetyDetector(),
        geminiClient = app.geminiClient,
        serviceProvider = { FlowPilotAccessibilityService.instance }
    )
    val replayEngine = ReplayEngine(stateMachine = stateMachine, recoveryManager = recoveryManager)
    private val clarificationManager = ClarificationManager(app.intentProcessor)

    val systemState = stateMachine.state
    val voiceState = voiceManager.voiceState
    val isTeaching = teachingCoordinator.isTeaching
    val recordedActionsCount = teachingCoordinator.recordedActionsCount
    val lastCapturedSummary = teachingCoordinator.lastCapturedSummary

    private val _lastTeachingResult = MutableStateFlow<TeachingResult?>(null)
    val lastTeachingResult: StateFlow<TeachingResult?> = _lastTeachingResult.asStateFlow()

    private val _savedWorkflows = MutableStateFlow<List<Workflow>>(emptyList())
    val savedWorkflows: StateFlow<List<Workflow>> = _savedWorkflows.asStateFlow()

    private val _lastSynthesizedWorkflow = MutableStateFlow<Workflow?>(null)
    val lastSynthesizedWorkflow: StateFlow<Workflow?> = _lastSynthesizedWorkflow.asStateFlow()

    // Clarification answer bridge: both voice and text can complete this
    private var clarificationDeferred: CompletableDeferred<String?>? = null

    fun clearLastTeachingResult() {
        _lastTeachingResult.value = null
    }

    init {
        voiceManager.initialize()

        viewModelScope.launch(Dispatchers.IO) {
            app.repository.getAllWorkflows().collect { workflows ->
                _savedWorkflows.value = workflows
                Log.d(TAG, "Loaded ${workflows.size} workflows from database")
            }
        }

        viewModelScope.launch(Dispatchers.Main) {
            teachingCoordinator.teachingResults.collect { result ->
                _lastTeachingResult.value = result
                synthesizeAndSaveWorkflow(result)
            }
        }
    }

    fun onMicTapped() {
        val currentVoiceState = voiceState.value
        if (currentVoiceState is VoiceState.Listening) {
            voiceManager.stop()
            return
        }

        val deferred = clarificationDeferred
        if (deferred != null && !deferred.isCompleted) {
            // We're waiting for a clarification answer — listen and feed it to the deferred
            viewModelScope.launch(Dispatchers.Main) {
                val result = voiceManager.listen()
                if (!result.isNullOrBlank() && !deferred.isCompleted) {
                    deferred.complete(result.trim())
                }
            }
            return
        }

        viewModelScope.launch(Dispatchers.Main) {
            val recognizedText = voiceManager.listen()
            if (!recognizedText.isNullOrBlank()) {
                handleVoiceCommand(recognizedText)
            }
        }
    }

    fun stopTeaching() {
        teachingCoordinator.stopTeaching()
    }

    fun cancelTeaching() {
        teachingCoordinator.cancelTeaching()
    }

    fun deleteWorkflow(workflow: Workflow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.deleteWorkflow(workflow.id)
            _savedWorkflows.value = _savedWorkflows.value.filter { it.id != workflow.id }
            if (_lastSynthesizedWorkflow.value?.id == workflow.id) {
                _lastSynthesizedWorkflow.value = null
            }
        }
    }

    private val _replayFailure = MutableStateFlow<ReplayFailureInfo?>(null)
    val replayFailure: StateFlow<ReplayFailureInfo?> = _replayFailure.asStateFlow()

    fun dismissReplayFailure() {
        _replayFailure.value = null
    }

    fun retryFailedWorkflow() {
        val failure = _replayFailure.value ?: return
        dismissReplayFailure()
        replayWorkflow(failure.workflow, failure.slotValues)
    }

    fun replayWorkflow(workflow: Workflow, slotValues: Map<String, String> = emptyMap()) {
        val service = FlowPilotAccessibilityService.instance
        if (service == null) {
            stateMachine.setError("Accessibility Service not connected. Please enable FlowPilot in Accessibility Settings.")
            viewModelScope.launch(Dispatchers.Main) {
                voiceManager.speak("Please enable FlowPilot in Accessibility Settings first.")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val result = replayEngine.execute(workflow, slotValues)

            val stepDetails = result.stepResults.map { sr ->
                val emoji = if (sr.success) "✅" else "❌"
                val durationStr = "${"%.1f".format(sr.durationMs / 1000.0)}s"
                "$emoji Step ${sr.stepIndex + 1}: ${sr.action} — ${sr.details} ($durationStr)"
            }
            try {
                app.repository.logExecution(ExecutionLog(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    startedAt = startTime,
                    completedAt = System.currentTimeMillis(),
                    success = result.success,
                    stoppedAtStep = if (!result.success) result.stepsCompleted else null,
                    stopReason = result.stopReason,
                    stepsCompleted = result.stepsCompleted,
                    totalSteps = result.totalSteps,
                    details = stepDetails
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log execution", e)
            }

            viewModelScope.launch(Dispatchers.Main) {
                if (result.success) {
                    _replayFailure.value = null
                    if (result.stopReason != null && result.stopReason.contains("Credential", ignoreCase = true)) {
                        voiceManager.speak("I've stopped at the security screen. Please complete this step yourself.")
                    } else {
                        voiceManager.speak("Finished executing ${workflow.name} successfully.")
                    }
                } else {
                    val failedStep = workflow.steps.getOrNull(result.stepsCompleted)
                    val stepNum = result.stepsCompleted + 1
                    val stepDesc = failedStep?.description?.ifBlank { "${failedStep.type.name} step $stepNum" } ?: "Step $stepNum"

                    val suggestion = when {
                        workflow.targetAppPackage.contains("zomato", ignoreCase = true) ->
                            "In Zomato, search results display categories and dish suggestions before the full menu. Tap a dish suggestion (like 'Chicken Biryani Dish') or scroll down to find the Add button."
                        workflow.targetAppPackage.contains("amazon", ignoreCase = true) ->
                            "In Amazon, sponsored ads and filter banners appear at the top. Scroll down past sponsored items to find your product."
                        else ->
                            "Make sure the target app is showing the expected screen, or demonstrate the steps again."
                    }

                    _replayFailure.value = ReplayFailureInfo(
                        workflowName = workflow.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        stepIndex = stepNum,
                        totalSteps = workflow.steps.size,
                        stepDescription = stepDesc,
                        reason = result.stopReason ?: "Could not find or interact with the target element",
                        suggestion = suggestion,
                        workflow = workflow,
                        slotValues = slotValues
                    )

                    voiceManager.speak("Replay stopped at step $stepNum. $stepDesc could not be completed.")
                }
            }
        }
    }

    fun submitTextCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        // If we're waiting for a clarification answer, feed it to the deferred
        val deferred = clarificationDeferred
        if (deferred != null && !deferred.isCompleted) {
            voiceManager.stop() // Stop listening if active
            deferred.complete(trimmed)
            return
        }

        viewModelScope.launch(Dispatchers.Main) {
            handleVoiceCommand(trimmed)
        }
    }

    private suspend fun handleVoiceCommand(text: String) {
        if (!FlowPilotAccessibilityService.isServiceConnected.value) {
            stateMachine.setError("Accessibility Service is not connected. Please enable FlowPilot in Accessibility Settings first.")
            viewModelScope.launch(Dispatchers.Main) {
                voiceManager.speak("Please enable FlowPilot in your phone's Accessibility Settings first.")
            }
            return
        }

        stateMachine.setRecognizedText(text)
        stateMachine.transition(SystemMode.PROCESSING, "Interpreting: \"$text\"")

        val flows = _savedWorkflows.value

        if (flows.isEmpty()) {
            voiceManager.speak("No workflows learned yet. I'll record your demonstration. Show me the steps and tap Done when finished.")
            teachingCoordinator.startTeaching(text)
            return
        }

        stateMachine.transition(SystemMode.PROCESSING, "Checking known workflows...")

        val intentResult = app.intentProcessor.process(text, flows)

        when (intentResult) {
            is IntentResult.Matched -> {
                val flow = intentResult.flow
                val slots = intentResult.extractedSlots
                val confidence = intentResult.confidence
                Log.i(TAG, "Matched flow '${flow.name}' with confidence $confidence. Slots: $slots")

                voiceManager.speak("Executing ${flow.name}.")
                replayWorkflow(flow, slots)
            }

            is IntentResult.NeedsClarification -> {
                val flow = intentResult.flow
                val missing = intentResult.missingSlots
                Log.i(TAG, "Needs clarification for '${flow.name}'. Missing: $missing")

                stateMachine.transition(SystemMode.CLARIFYING, "Missing info for ${flow.name}")

                // Resolve each missing slot by asking question + waiting for voice OR text answer
                val resolved = resolveMissingSlots(flow, missing)

                Log.i(TAG, "Clarification resolved slots: $resolved")
                voiceManager.speak("Got it! Executing ${flow.name}.")
                replayWorkflow(flow, resolved)
            }

            is IntentResult.Unknown -> {
                Log.i(TAG, "No matching flow found. Starting teaching mode.")
                voiceManager.speak("I don't know how to do that yet. Show me once and I'll learn! Tap Done when finished.")
                teachingCoordinator.startTeaching(text)
            }

            is IntentResult.NoFlows -> {
                voiceManager.speak("No workflows learned yet. Show me the steps and tap Done when finished.")
                teachingCoordinator.startTeaching(text)
            }

            is IntentResult.TeachNew -> {
                voiceManager.speak("Got it! Show me the steps and tap Done when finished.")
                teachingCoordinator.startTeaching(text)
            }
        }
    }

    /**
     * Ask the user for each missing slot value. Accepts BOTH voice and typed text as answers.
     * Uses CompletableDeferred so onMicTapped() and submitTextCommand() can both provide answers.
     */
    private suspend fun resolveMissingSlots(
        flow: Workflow,
        missingSlots: List<String>
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()

        for (slotName in missingSlots) {
            // Generate question
            val question = clarificationManager.generateQuestion(flow, slotName)
            Log.i(TAG, "Asking for slot '$slotName': $question")

            stateMachine.transition(SystemMode.CLARIFYING, question)

            // Speak the question, then wait for TTS to finish
            voiceManager.speak(question)
            delay(600) // Let audio hardware switch from TTS output to mic input

            // Create a deferred that both voice and text input can complete
            val deferred = CompletableDeferred<String?>()
            clarificationDeferred = deferred

            // Start voice listening in background — it will complete the deferred when done
            val listenJob = viewModelScope.launch(Dispatchers.Main) {
                val voiceResult = voiceManager.listen()
                if (!voiceResult.isNullOrBlank() && !deferred.isCompleted) {
                    deferred.complete(voiceResult.trim())
                } else if (!deferred.isCompleted) {
                    // Voice returned nothing, but don't complete with null yet —
                    // user might still type. Wait a short time then give up.
                    delay(5000)
                    if (!deferred.isCompleted) {
                        deferred.complete(null)
                    }
                }
            }

            // Wait for answer from EITHER voice or text
            val answer = deferred.await()
            listenJob.cancel()
            voiceManager.stop()
            clarificationDeferred = null

            if (!answer.isNullOrBlank()) {
                resolved[slotName] = answer.trim()
                Log.i(TAG, "Got answer for '$slotName': '${answer.trim()}'")
            } else {
                // Use default value if available
                val defaultValue = flow.slots[slotName]?.defaultValue
                if (!defaultValue.isNullOrBlank()) {
                    resolved[slotName] = defaultValue
                    Log.i(TAG, "Using default for '$slotName': '$defaultValue'")
                } else {
                    Log.w(TAG, "No answer and no default for slot '$slotName'")
                }
            }
        }

        // Fill any remaining optional slots with their defaults
        for ((name, slot) in flow.slots) {
            if (name !in resolved && !slot.defaultValue.isNullOrBlank()) {
                resolved[name] = slot.defaultValue
            }
        }

        return resolved
    }

    private fun synthesizeAndSaveWorkflow(result: TeachingResult) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (result.actions.isEmpty()) {
                    Log.w(TAG, "Teaching finished with 0 actions")
                    stateMachine.setError("No actions were captured. Please perform the steps and tap Done.")
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceManager.speak("I didn't detect any actions. Please try teaching again.")
                    }
                    return@launch
                }

                if (!com.flowpilot.util.Constants.hasAnyAiKey()) {
                    Log.w(TAG, "No AI API key configured")
                    stateMachine.setError("AI API key missing. Set API keys in Constants.kt")
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceManager.speak("Captured ${result.actions.size} actions, but no AI API key is configured.")
                    }
                    return@launch
                }

                stateMachine.transition(SystemMode.SYNTHESIZING, "Analyzing ${result.actions.size} actions with AI...")

                val synthResult = app.workflowSynthesizer.synthesize(result)
                val workflow = synthResult.workflow

                if (workflow == null) {
                    val reason = synthResult.errorReason ?: "Could not understand demonstration"
                    Log.e(TAG, "Workflow synthesis failed: $reason")
                    stateMachine.setError("Learning failed: $reason")
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceManager.speak("Sorry, $reason. Please try teaching again.")
                    }
                    return@launch
                }

                val embedding = app.flowMatcher.computeTriggerEmbedding(workflow.triggerUtterance)
                val workflowWithEmbedding = workflow.copy(triggerEmbedding = embedding)

                app.repository.saveWorkflow(workflowWithEmbedding)

                _lastSynthesizedWorkflow.value = workflowWithEmbedding
                _savedWorkflows.value = listOf(workflowWithEmbedding) + _savedWorkflows.value.filter { it.id != workflowWithEmbedding.id }

                Log.i(TAG, "Workflow '${workflow.name}' synthesized and saved. ${workflow.steps.size} steps, ${workflow.slots.size} slots.")

                stateMachine.transition(
                    SystemMode.IDLE,
                    "✅ Learned \"${workflow.name}\" — ${workflow.steps.size} steps, ${workflow.slots.size} parameters"
                )

                viewModelScope.launch(Dispatchers.Main) {
                    voiceManager.speak(
                        "I learned the workflow ${workflow.description}. " +
                        "It has ${workflow.steps.size} steps and ${workflow.slots.size} changeable parameters. " +
                        "You can now trigger it by saying something similar."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in synthesize and save", e)
                stateMachine.setError("Error: ${e.message}")
                viewModelScope.launch(Dispatchers.Main) {
                    voiceManager.speak("Sorry, something went wrong while learning the workflow.")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}
