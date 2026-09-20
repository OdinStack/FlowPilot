package com.flowpilot.engine

import android.util.Log
import com.flowpilot.ai.IntentProcessor
import com.flowpilot.data.models.Workflow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Generates clarification questions for missing slot values.
 * Does NOT handle voice/text input — that's the caller's responsibility.
 */
class ClarificationManager(
    private val intentProcessor: IntentProcessor
) {

    companion object {
        private const val TAG = "ClarificationManager"
        private const val QUESTION_GEN_TIMEOUT_MS = 8_000L
    }

    /**
     * Generate a natural-language question for a missing slot.
     * Uses Gemini with an 8-second timeout, falls back to template.
     */
    suspend fun generateQuestion(flow: Workflow, slotName: String): String {
        return try {
            withTimeoutOrNull(QUESTION_GEN_TIMEOUT_MS) {
                intentProcessor.generateClarificationQuestion(flow, slotName)
            } ?: run {
                Log.w(TAG, "Gemini timed out for slot '$slotName', using fallback")
                fallbackQuestion(slotName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate question for slot '$slotName'", e)
            fallbackQuestion(slotName)
        }
    }

    fun fallbackQuestion(slotName: String): String {
        return when (slotName.lowercase()) {
            "restaurant" -> "Which restaurant would you like to order from?"
            "item", "food", "dish" -> "What item would you like to order?"
            "quantity", "count", "number" -> "How many would you like?"
            "address" -> "Which address should I deliver to?"
            "search_query", "query", "search" -> "What would you like to search for?"
            "first_number", "number1", "num1" -> "What's the first number?"
            "second_number", "number2", "num2" -> "What's the second number?"
            else -> "What should the ${slotName.replace('_', ' ')} be?"
        }
    }
}
