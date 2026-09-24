package com.flowpilot.ai

import android.util.Log
import com.flowpilot.data.models.Workflow
import kotlinx.serialization.json.*

sealed class IntentResult {
    data class Matched(
        val flow: Workflow,
        val extractedSlots: Map<String, String>,
        val confidence: Float
    ) : IntentResult()

    object Unknown : IntentResult()
    object NoFlows : IntentResult()

    data class NeedsClarification(
        val flow: Workflow,
        val missingSlots: List<String>
    ) : IntentResult()

    data class TeachNew(val utterance: String) : IntentResult()
}

class IntentProcessor(private val gemini: GeminiClient) {

    companion object {
        private const val TAG = "IntentProcessor"

        private const val INTENT_MATCHING_SYSTEM_PROMPT = """
You are an intent matching and slot extraction system. Given a user's voice command and a list of previously learned workflows, determine:
1. Does the command match any known workflow?
2. If yes, which one?
3. What are the slot/parameter values from the command?
4. Are any required slots missing?

OUTPUT FORMAT (JSON):
{
  "matched": true/false,
  "matched_flow_name": "flow name or null",
  "confidence": 0.0-1.0,
  "extracted_slots": {
    "slot_name": "extracted value"
  },
  "missing_required_slots": ["slot_name1"],
  "reasoning": "brief explanation of your matching decision"
}

RULES:
- Match based on SEMANTIC MEANING, not exact words
- "Get me a margherita from dominos" matches "Order a Margherita pizza from Domino's on Zomato"
- "Book a cab" does NOT match "Order pizza"
- If multiple flows could match, pick the best one and explain why
- If no flow matches, set matched=false
- If a required slot is not mentioned in the command, add it to missing_required_slots
- If the user command expresses the general intent of a workflow even without parameters (e.g. 'calculate' or 'add' matches 'add two numbers on calculator', 'order food' matches 'order on zomato'), set matched=true, set confidence=0.85, leave extracted_slots empty for what wasn't mentioned, and list the missing slots in missing_required_slots so the assistant can prompt for them.
- QUANTITY: If the user says a number before a noun (e.g. "two pizzas", "3 shirts"), extract it as quantity slot. "Order two Margheritas" → extracted_slots: {"quantity": "2", "item": "Margherita"}
- ADDRESS: If the user mentions "deliver to Work/Home/Office" or "to my work/home address", extract as address slot.
- OPERATION: For calculator workflows, if the user says "multiply/subtract/divide/add", extract as operation slot.
"""

        private const val SLOT_EXTRACTION_SYSTEM_PROMPT = """
You are a slot extraction system. Given a voice command and a workflow definition with named slots, extract the values for each slot from the command.

OUTPUT FORMAT (JSON):
{
  "slots": {
    "slot_name": "extracted_value"
  },
  "missing_slots": ["slot_names_not_found_in_command"]
}

RULES:
- Extract values as they appear in the command (preserve the user's wording)
- If a slot is not mentioned, add its name to missing_slots
- Handle synonyms and variations: "margarita" = "Margherita", "dominos" = "Domino's"
- Numbers: "two" = "2", "three" = "3"
- If quantity is not mentioned, assume 1
- QUANTITY: Look for number words before nouns: "two pizzas" → quantity=2, "3 shirts" → quantity=3
- ADDRESS: "deliver to work" → address="Work", "to my home" → address="Home"
- OPERATION: "multiply" → operation="multiply", "subtract" → operation="subtract", "divide" → operation="divide", "add/plus/sum" → operation="add"
"""

        private const val CLARIFICATION_SYSTEM_PROMPT = """
You are a voice assistant that needs to ask the user for missing information.
Generate a natural, conversational question to ask for a specific missing slot value.
Keep questions short and clear. Return only the question text, nothing else.
"""
    }

    /**
     * Process a voice command: determine intent, match to a flow, extract slots.
     */
    suspend fun process(
        command: String,
        storedFlows: List<Workflow>
    ): IntentResult {
        if (storedFlows.isEmpty()) {
            return IntentResult.NoFlows
        }

        // Step 1: Try embedding similarity for fast matching
        val commandEmbedding = gemini.embed(command)
        if (commandEmbedding != null) {
            val matches = storedFlows.mapNotNull { flow ->
                val flowEmbedding = flow.triggerEmbedding?.toFloatArray()
                if (flowEmbedding != null) {
                    val similarity = gemini.cosineSimilarity(commandEmbedding, flowEmbedding)
                    FlowMatch(flow, similarity)
                } else null
            }.sortedByDescending { it.similarity }

            val bestMatch = matches.firstOrNull()
            Log.d(TAG, "Best embedding match: ${bestMatch?.flow?.name} (${bestMatch?.similarity})")

            if (bestMatch != null && bestMatch.similarity > 0.88f) {
                // High confidence — extract slots directly
                val slots = extractSlots(command, bestMatch.flow)
                val missingRequired = bestMatch.flow.slots
                    .filter { it.value.isRequired && !slots.containsKey(it.key) }
                    .keys.toList()

                return if (missingRequired.isEmpty()) {
                    IntentResult.Matched(bestMatch.flow, slots, bestMatch.similarity)
                } else {
                    IntentResult.NeedsClarification(bestMatch.flow, missingRequired)
                }
            }

            if (bestMatch != null && bestMatch.similarity < 0.45f) {
                return IntentResult.Unknown
            }
        }

        // Step 2: Ambiguous or no embedding — use LLM for full matching
        return matchWithLLM(command, storedFlows)
    }

    /**
     * Use LLM for intent matching when embedding similarity is ambiguous.
     */
    private suspend fun matchWithLLM(
        command: String,
        flows: List<Workflow>
    ): IntentResult {
        val flowsDescription = flows.joinToString("\n---\n") { flow ->
            buildString {
                append("Flow: ${flow.name}\n")
                append("Trigger: \"${flow.triggerUtterance}\"\n")
                append("Description: ${flow.description}\n")
                append("App: ${flow.targetAppPackage}\n")
                if (flow.slots.isNotEmpty()) {
                    append("Slots:\n")
                    flow.slots.forEach { (name, slot) ->
                        append("  - $name (${slot.type}): ${slot.description}. Default: ${slot.defaultValue}. Required: ${slot.isRequired}\n")
                    }
                }
            }
        }

        val prompt = """
USER COMMAND: "$command"

KNOWN WORKFLOWS:
$flowsDescription

Which workflow does this command match? Extract the slot values.
""".trimIndent()

        val response = gemini.generate(
            systemPrompt = INTENT_MATCHING_SYSTEM_PROMPT,
            userPrompt = prompt,
            jsonMode = true
        )

        if (response == null) {
            Log.w(TAG, "LLM matching returned null")
            return IntentResult.Unknown
        }

        return parseLLMMatchResult(response, flows)
    }

    /**
     * Extract slot values from a command for a matched flow.
     */
    private suspend fun extractSlots(
        command: String,
        flow: Workflow
    ): Map<String, String> {
        if (flow.slots.isEmpty()) return emptyMap()

        val slotsDescription = flow.slots.entries.joinToString("\n") { (name, slot) ->
            "- $name (${slot.type}): ${slot.description}. Default: ${slot.defaultValue}. Required: ${slot.isRequired}."
        }

        val prompt = """
COMMAND: "$command"

WORKFLOW: ${flow.name}
SLOTS:
$slotsDescription

Extract the slot values from the command.
""".trimIndent()

        val response = gemini.generate(
            systemPrompt = SLOT_EXTRACTION_SYSTEM_PROMPT,
            userPrompt = prompt,
            jsonMode = true
        )

        return response?.let { parseSlotResponse(it) } ?: emptyMap()
    }

    /**
     * Generate a natural language question for a missing slot.
     */
    suspend fun generateClarificationQuestion(
        flow: Workflow,
        missingSlotName: String
    ): String {
        val slot = flow.slots[missingSlotName] ?: return "What value should I use for $missingSlotName?"
        val prompt = """
The user wants to execute the "${flow.name}" workflow (${flow.description}).

The following required information is missing: $missingSlotName
This slot represents: ${slot.description}
In the original demonstration, the value was: ${slot.defaultValue}

Generate a natural question to ask the user for this value.
Just return the question text.
""".trimIndent()

        return gemini.generate(
            systemPrompt = CLARIFICATION_SYSTEM_PROMPT,
            userPrompt = prompt
        ) ?: "What value should I use for $missingSlotName?"
    }

    private fun parseLLMMatchResult(jsonStr: String, flows: List<Workflow>): IntentResult {
        return try {
            val cleanJson = jsonStr.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = json.parseToJsonElement(cleanJson).jsonObject

            val matched = obj["matched"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!matched) return IntentResult.Unknown

            val flowName = obj["matched_flow_name"]?.jsonPrimitive?.contentOrNull ?: return IntentResult.Unknown
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.5f

            val flow = flows.find { it.name == flowName } ?: return IntentResult.Unknown

            val extractedSlots = mutableMapOf<String, String>()
            obj["extracted_slots"]?.jsonObject?.forEach { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { extractedSlots[key] = it }
            }

            val missingSlots = obj["missing_required_slots"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            if (missingSlots.isNotEmpty()) {
                IntentResult.NeedsClarification(flow, missingSlots)
            } else {
                IntentResult.Matched(flow, extractedSlots, confidence)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM match result", e)
            IntentResult.Unknown
        }
    }

    private fun parseSlotResponse(jsonStr: String): Map<String, String> {
        return try {
            val cleanJson = jsonStr.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = json.parseToJsonElement(cleanJson).jsonObject
            val slotsObj = obj["slots"]?.jsonObject ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            slotsObj.forEach { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { result[key] = it }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse slot response", e)
            emptyMap()
        }
    }

    private data class FlowMatch(val flow: Workflow, val similarity: Float)
}
