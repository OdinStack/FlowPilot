package com.flowpilot.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowpilot.FlowPilotApp
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.ai.IntentResult
import com.flowpilot.data.models.Workflow
import com.flowpilot.engine.ReplayEngine
import com.flowpilot.engine.SystemMode
import com.flowpilot.engine.SystemStateMachine
import com.flowpilot.engine.TeachingCoordinator
import com.flowpilot.engine.TeachingResult
import com.flowpilot.voice.VoiceManager
import com.flowpilot.voice.VoiceState
import kotlinx.coroutines.Dispatchers
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
    val replayEngine = ReplayEngine(stateMachine = stateMachine)

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

    fun clearLastTeachingResult() {
        _lastTeachingResult.value = null
    }

    init {
        voiceManager.initialize()

        // Observe saved workflows from database
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.getAllWorkflows().collect { workflows ->
                _savedWorkflows.value = workflows
                Log.d(TAG, "Loaded ${workflows.size} workflows from database")
            }
        }

        // When teaching completes, synthesize workflow via Gemini and save to database
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
            val result = replayEngine.execute(workflow, slotValues)
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
            // No workflows saved yet — go straight to teaching
            voiceManager.speak("No workflows learned yet. I'll record your demonstration. Show me the steps and tap Done when finished.")
            teachingCoordinator.startTeaching(text)
            return
        }

        // Try to match the command to existing workflows
        stateMachine.transition(SystemMode.PROCESSING, "Checking known workflows...")

        val intentResult = app.intentProcessor.process(text, flows)

        when (intentResult) {
            is IntentResult.Matched -> {
                val flow = intentResult.flow
                val slots = intentResult.extractedSlots
                val confidence = intentResult.confidence
                Log.i(TAG, "Matched flow '${flow.name}' with confidence $confidence. Slots: $slots")

                val slotSummary = if (slots.isNotEmpty()) {
                    slots.entries.joinToString(", ") { "${it.key}=${it.value}" }
                } else "no parameters"

                voiceManager.speak("Executing ${flow.name}.")
                replayWorkflow(flow, slots)
            }

            is IntentResult.NeedsClarification -> {
                val flow = intentResult.flow
                val missing = intentResult.missingSlots
                Log.i(TAG, "Needs clarification for '${flow.name}'. Missing: $missing")

                stateMachine.transition(SystemMode.CLARIFYING, "Missing info for ${flow.name}")

                val question = app.intentProcessor.generateClarificationQuestion(flow, missing.first())
                voiceManager.speak(question)
                stateMachine.transition(SystemMode.IDLE, "Asked: $question")
                // TODO: Listen for answer and re-process
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

                if (com.flowpilot.util.Constants.GEMINI_API_KEY.isBlank() ||
                    com.flowpilot.util.Constants.GEMINI_API_KEY == "YOUR_GEMINI_API_KEY_HERE"
                ) {
                    Log.w(TAG, "Gemini API key not configured")
                    stateMachine.setError("Gemini API key missing. Set GEMINI_API_KEY in Constants.kt")
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceManager.speak("Captured ${result.actions.size} actions, but Gemini API key is missing in Constants.kt.")
                    }
                    return@launch
                }

                stateMachine.transition(SystemMode.SYNTHESIZING, "Analyzing ${result.actions.size} actions with AI...")

                // Step 1: Synthesize workflow from demonstration using Gemini
                val workflow = app.workflowSynthesizer.synthesize(result)

                if (workflow == null) {
                    Log.e(TAG, "Workflow synthesis failed")
                    stateMachine.setError("Workflow synthesis failed. Please check your API key or internet connection.")
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceManager.speak("Sorry, I couldn't understand the workflow. Please try teaching again.")
                    }
                    return@launch
                }

                // Step 2: Compute trigger embedding for fast future matching
                val embedding = app.flowMatcher.computeTriggerEmbedding(workflow.triggerUtterance)
                val workflowWithEmbedding = workflow.copy(triggerEmbedding = embedding)

                // Step 3: Save to Room database
                app.repository.saveWorkflow(workflowWithEmbedding)

                _lastSynthesizedWorkflow.value = workflowWithEmbedding

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
