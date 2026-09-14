package com.flowpilot.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SystemMode {
    IDLE,           // Waiting for user interaction
    LISTENING,      // ASR is active, waiting for voice command
    PROCESSING,     // Processing voice command (LLM call)
    TEACHING,       // Recording user demonstration
    SYNTHESIZING,   // Converting recording to workflow (LLM call)
    REPLAYING,      // Executing a workflow
    CLARIFYING,     // Asking user for missing information
    PAUSED,         // Paused at credential boundary
    ERROR           // Something went wrong
}

data class SystemState(
    val mode: SystemMode = SystemMode.IDLE,
    val message: String = "Tap the microphone and tell me what to do",
    val currentFlowName: String? = null,
    val currentStepIndex: Int = -1,
    val totalSteps: Int = 0,
    val error: String? = null,
    val lastRecognizedText: String? = null,
    val actionsRecordedCount: Int = 0
)

class SystemStateMachine {
    private val _state = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    fun transition(mode: SystemMode, message: String = "") {
        _state.update { current ->
            current.copy(
                mode = mode,
                message = if (message.isNotBlank()) message else defaultMessageFor(mode),
                error = if (mode == SystemMode.ERROR) message else null
            )
        }
    }

    fun updateActionCount(count: Int, lastActionDesc: String? = null) {
        _state.update { current ->
            current.copy(
                actionsRecordedCount = count,
                message = if (lastActionDesc != null) "🔴 Recording ($count): $lastActionDesc" else current.message
            )
        }
    }

    fun updateStep(index: Int, total: Int, stepDesc: String = "") {
        _state.update { current ->
            current.copy(
                currentStepIndex = index,
                totalSteps = total,
                message = "Step ${index + 1}/$total: $stepDesc"
            )
        }
    }

    fun setRecognizedText(text: String) {
        _state.update { current ->
            current.copy(lastRecognizedText = text)
        }
    }

    fun setError(error: String) {
        _state.update { current ->
            current.copy(
                mode = SystemMode.ERROR,
                error = error,
                message = "❌ $error"
            )
        }
    }

    fun reset() {
        _state.value = SystemState()
    }

    private fun defaultMessageFor(mode: SystemMode): String = when (mode) {
        SystemMode.IDLE -> "Tap the microphone and tell me what to do"
        SystemMode.LISTENING -> "Listening..."
        SystemMode.PROCESSING -> "Understanding your command..."
        SystemMode.TEACHING -> "🔴 Recording — show me the steps"
        SystemMode.SYNTHESIZING -> "Learning your workflow..."
        SystemMode.REPLAYING -> "Replaying workflow..."
        SystemMode.CLARIFYING -> "Waiting for clarification..."
        SystemMode.PAUSED -> "🔒 Security screen reached — your turn!"
        SystemMode.ERROR -> "Something went wrong"
    }
}
