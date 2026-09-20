package com.flowpilot.engine

import android.util.Log
import com.flowpilot.ai.IntentProcessor
import com.flowpilot.data.models.Workflow
import com.flowpilot.voice.VoiceManager

/**
 * Handles multi-slot clarification: asks the user for each missing slot value
 * one at a time via voice, falling back to defaults if no answer is given.
 */
class ClarificationManager(
    private val intentProcessor: IntentProcessor,
    private val voiceManager: VoiceManager
) {

    companion object {
        private const val TAG = "ClarificationManager"
    }

    /**
     * Ask the user for each missing slot value and return the fully resolved slot map.
     *
     * @param flow The matched workflow.
     * @param missingSlots Names of slots that need values.
     * @param existingSlots Slots already extracted from the original command.
     * @return Complete slot map with all missing values filled in.
     */
    suspend fun resolveSlots(
        flow: Workflow,
        missingSlots: List<String>,
        existingSlots: Map<String, String>
    ): Map<String, String> {
        val resolved = existingSlots.toMutableMap()

        for (slotName in missingSlots) {
            if (resolved.containsKey(slotName)) continue

            // Generate a natural question for this slot
            val question = try {
                intentProcessor.generateClarificationQuestion(flow, slotName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate question for slot '$slotName'", e)
                fallbackQuestion(slotName)
            }

            Log.i(TAG, "Asking for slot '$slotName': $question")
            voiceManager.speak(question)

            // Listen for the user's answer
            val answer = voiceManager.listen()

            if (!answer.isNullOrBlank()) {
                resolved[slotName] = answer.trim()
                Log.i(TAG, "Got answer for '$slotName': '${answer.trim()}'")
            } else {
                // Use default value if available
                val slot = flow.slots[slotName]
                val defaultValue = slot?.defaultValue
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

    private fun fallbackQuestion(slotName: String): String {
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
