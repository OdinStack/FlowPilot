package com.flowpilot.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    data class Partial(val text: String) : VoiceState()
    object Processing : VoiceState()
    data class Result(val text: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

class VoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
            if (isTtsReady) {
                tts?.language = Locale.US
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.w(TAG, "TTS initialization failed with status: $status")
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition is NOT available on this device")
            _voiceState.value = VoiceState.Error("Speech recognition not available")
        }
    }

    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _voiceState.value = VoiceState.Error("Speech recognition not available")
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }

            _voiceState.value = VoiceState.Listening

            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning previous recognizer", e)
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = VoiceState.Listening
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _voiceState.value = VoiceState.Processing
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        _voiceState.value = VoiceState.Partial(partial)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    Log.i(TAG, "Speech result: \"$text\"")
                    if (!text.isNullOrBlank()) {
                        _voiceState.value = VoiceState.Result(text)
                    } else {
                        _voiceState.value = VoiceState.Idle
                    }

                    if (cont.isActive) cont.resume(text)
                    try { recognizer.destroy() } catch (_: Exception) {}
                }

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Please speak again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio permission needed"
                        else -> "Speech error: $error"
                    }
                    Log.w(TAG, "Speech recognition error ($error): $msg")
                    _voiceState.value = VoiceState.Error(msg)
                    if (cont.isActive) cont.resume(null)
                    try { recognizer.destroy() } catch (_: Exception) {}
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                try {
                    recognizer.cancel()
                    recognizer.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error cancelling recognizer", e)
                }
                _voiceState.value = VoiceState.Idle
            }
        }
    }

    suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready, skipping speak: \"$text\"")
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }

        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }

            override fun onError(id: String?) {
                if (id == utteranceId && cont.isActive) cont.resume(Unit)
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        tts?.stop()
        _voiceState.value = VoiceState.Idle
    }

    fun destroy() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
