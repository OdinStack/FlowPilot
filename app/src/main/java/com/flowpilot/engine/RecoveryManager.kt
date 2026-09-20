package com.flowpilot.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.flowpilot.accessibility.ActionExecutor
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.accessibility.SafetyDetector
import com.flowpilot.accessibility.ScreenAnalyzer
import com.flowpilot.ai.GeminiClient
import com.flowpilot.data.models.WorkflowStep
import kotlinx.coroutines.delay

/**
 * Handles recovery when a replay step fails.
 * Strategies (in order):
 *  1. Credential/payment boundary → stop and hand control to user
 *  2. Popup/dialog overlay → dismiss and retry
 *  3. Retry with delay (up to MAX_RECOVERY_ATTEMPTS)
 *  4. Genuinely stuck → generate a specific help question via Gemini
 */
class RecoveryManager(
    private val screenAnalyzer: ScreenAnalyzer,
    private val safetyDetector: SafetyDetector,
    private val geminiClient: GeminiClient,
    private val serviceProvider: () -> FlowPilotAccessibilityService?
) {

    companion object {
        private const val TAG = "RecoveryManager"
        const val MAX_RECOVERY_ATTEMPTS = 3
    }

    sealed class RecoveryAction {
        /** Dismiss popup and retry the step. */
        object DismissAndRetry : RecoveryAction()
        /** Simply retry the step (after a delay). */
        object RetryStep : RecoveryAction()
        /** Ask the user a specific question about the current state. */
        data class AskUser(val question: String) : RecoveryAction()
        /** Abort execution with a reason. */
        data class Abort(val reason: String) : RecoveryAction()
        /** Stop at credential/payment boundary. */
        object CredentialStop : RecoveryAction()
    }

    /**
     * Analyze the current screen and decide how to recover from a failed step.
     */
    suspend fun analyzeAndRecover(
        failedStep: WorkflowStep,
        attemptNumber: Int,
        slotValues: Map<String, String>
    ): RecoveryAction {
        val service = serviceProvider() ?: return RecoveryAction.Abort("Cannot access screen")
        val root = service.rootInActiveWindow ?: return RecoveryAction.Abort("Cannot access screen")

        // 1. Check safety first — always stop at credential/payment screens
        val safety = safetyDetector.check(root)
        if (safety !is SafetyDetector.SafetyResult.Safe) {
            Log.w(TAG, "Safety boundary detected during recovery: $safety")
            return RecoveryAction.CredentialStop
        }

        // 2. Check for dismissible popup
        if (screenAnalyzer.hasPopupOverlay(root)) {
            val dismissBtn = screenAnalyzer.findDismissButton(root)
            if (dismissBtn != null) {
                Log.i(TAG, "Found popup with dismiss button — will dismiss and retry")
                val executor = service.actionExecutor
                executor.click(dismissBtn)
                delay(1000)
                return RecoveryAction.RetryStep
            } else {
                Log.i(TAG, "Found popup but no dismiss button — pressing back and retrying")
                service.actionExecutor.pressBack()
                delay(500)
                return RecoveryAction.RetryStep
            }
        }

        // 3. If we've hit max attempts, generate a specific question
        if (attemptNumber >= MAX_RECOVERY_ATTEMPTS) {
            val question = generateStuckQuestion(root, failedStep, slotValues)
            Log.w(TAG, "Max retries reached. Asking user: $question")
            return RecoveryAction.AskUser(question)
        }

        // 4. Default: retry after a short delay
        Log.i(TAG, "Recovery attempt $attemptNumber — will retry step after delay")
        return RecoveryAction.RetryStep
    }

    /**
     * Generate a specific, context-aware question when the system is genuinely stuck.
     * Uses Gemini to analyze visible screen content and produce a useful question.
     */
    suspend fun generateStuckQuestion(
        root: AccessibilityNodeInfo,
        failedStep: WorkflowStep,
        slotValues: Map<String, String>
    ): String {
        val visibleTexts = collectVisibleTexts(root).take(20)
        val screenDesc = visibleTexts.joinToString(", ")

        val targetDesc = failedStep.target.semantic
            ?: failedStep.target.textContains
            ?: failedStep.target.text
            ?: failedStep.target.contentDescription
            ?: "unknown element"

        val prompt = """
Current screen has these visible texts: [$screenDesc]

I was trying to: ${failedStep.description}
Looking for element: $targetDesc
Step type: ${failedStep.type.name}
Slot values: $slotValues

What specific question should I ask the user?
""".trimIndent()

        val systemPrompt = """
You are a voice assistant that is stuck while trying to automate an Android app.
Based on the current screen content and the step you were trying to perform,
generate a SPECIFIC, HELPFUL question for the user.

DO NOT say generic things like "Something went wrong."
DO say specific things like:
- "I can't find 'Farmhouse Pizza' on the menu. Is it called something else?"
- "The app seems to be showing a login screen. Could you log in first?"
- "I see a list of restaurants but none match. Should I search for something else?"

Return ONLY the question text, nothing else.
""".trimIndent()

        return try {
            val response = geminiClient.generate(
                systemPrompt = systemPrompt,
                userPrompt = prompt
            )
            response?.trim()?.removeSurrounding("\"")
                ?: fallbackStuckQuestion(failedStep, targetDesc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate stuck question via Gemini", e)
            fallbackStuckQuestion(failedStep, targetDesc)
        }
    }

    private fun fallbackStuckQuestion(step: WorkflowStep, targetDesc: String): String {
        return "I'm having trouble finding '$targetDesc' for step '${step.description}'. " +
                "Could you help me navigate to the right screen?"
    }

    private fun collectVisibleTexts(root: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15) return
            try {
                if (node.isVisibleToUser) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    traverse(child, depth + 1)
                    child.recycle()
                }
            } catch (e: Exception) {
                // Stale node — safe to ignore
            }
        }
        traverse(root, 0)
        return texts
    }
}
