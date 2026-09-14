package com.flowpilot.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val stateMachine = SystemStateMachine()
    val voiceManager = VoiceManager(application)
    val teachingCoordinator = TeachingCoordinator(application, stateMachine)

    val systemState = stateMachine.state
    val voiceState = voiceManager.voiceState
    val isTeaching = teachingCoordinator.isTeaching
    val recordedActionsCount = teachingCoordinator.recordedActionsCount
    val lastCapturedSummary = teachingCoordinator.lastCapturedSummary

    private val _lastTeachingResult = MutableStateFlow<TeachingResult?>(null)
    val lastTeachingResult: StateFlow<TeachingResult?> = _lastTeachingResult.asStateFlow()

    fun clearLastTeachingResult() {
        _lastTeachingResult.value = null
    }

    init {
        voiceManager.initialize()
        viewModelScope.launch(Dispatchers.Main) {
            teachingCoordinator.teachingResults.collect { result ->
                _lastTeachingResult.value = result
                val speechMsg = "I learned ${result.actions.size} actions for ${result.targetPackage.substringAfterLast('.')}. Workflow saved."
                voiceManager.speak(speechMsg)
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

    private suspend fun handleVoiceCommand(text: String) {
        stateMachine.setRecognizedText(text)
        stateMachine.transition(SystemMode.PROCESSING, "Interpreting: \"$text\"")

        // In Phase 2 & 3: Direct to teaching mode
        voiceManager.speak("Got it! Open your app and perform the task. Tap Done when finished.")
        teachingCoordinator.startTeaching(text)
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}
