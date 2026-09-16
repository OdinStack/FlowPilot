package com.flowpilot.ai

import android.util.Log
import com.flowpilot.data.models.*
import com.flowpilot.engine.TeachingResult
import kotlinx.serialization.json.*

class WorkflowSynthesizer(private val gemini: GeminiClient) {

    companion object {
        private const val TAG = "WorkflowSynthesizer"

        private const val SYNTHESIS_SYSTEM_PROMPT = """
You are a workflow synthesis engine. Given a user's voice command and their demonstrated UI actions on an Android app, you must produce a generalized, reusable workflow in JSON format.

RULES:
1. Identify PARAMETERS (slots): Values mentioned in the voice command that also appear in the demonstrated actions are parameters. They can change in future invocations.
2. Identify FIXED STEPS: Actions that are structural (navigation, clicking buttons like "Add to Cart", going to cart) are fixed.
3. For each step, describe the TARGET semantically — by what it IS, not where it IS. Include multiple matching strategies.
4. Mark credential/payment boundaries where the workflow should stop.
5. Generate a human-readable flow name and description.
6. Determine which parameters are required vs optional.

OUTPUT FORMAT (JSON):
{
  "flow_name": "short_snake_case_name",
  "description": "Human readable description",
  "trigger_utterance": "the original voice command",
  "target_app_package": "com.example.app",
  "slots": {
    "slot_name": {
      "type": "string",
      "default_value": "value from demonstration",
      "is_required": true,
      "is_variable": true,
      "description": "what this parameter represents"
    }
  },
  "steps": [
    {
      "index": 0,
      "type": "CLICK",
      "description": "human readable description of this step",
      "target": {
        "text": "exact or partial text to match",
        "text_contains": "substring to match",
        "resource_id": "resource ID if available",
        "class_name": "widget class name",
        "content_description": "accessibility description",
        "hint_text": "hint text for input fields",
        "is_editable": false,
        "is_scrollable": false,
        "context_text_contains": "text of nearby elements for disambiguation",
        "semantic": "human description of what this element is"
      },
      "value": "text to type (for TYPE actions), use {slot_name} for parameters",
      "scroll_to_find": false,
      "is_credential_boundary": false,
      "expected_result": "what should happen after this action"
    }
  ]
}

IMPORTANT:
- Use {slot_name} syntax for parameter values in step targets and values
- When an input value (such as a multi-digit number '10' or entered text) is a slot parameter, represent it as a SINGLE step with target.text = "{slot_name}" (or value = "{slot_name}"). DO NOT split it into separate steps for individual digits or characters. The replay engine handles sequential multi-digit typing automatically.
- For parameterized steps (where target.text or value uses {slot_name}), DO NOT include a digit-specific or literal-specific resource_id (like digit_5); omit resource_id so the parameter value dynamically resolves.
- For buttons that appear multiple times, ALWAYS include context_text_contains to disambiguate
- Mark the last step as is_credential_boundary: true if it leads to payment/login
- Don't include app-opening as a step if the user was already in the app
- Include scroll_to_find: true for steps where the target might be below the visible area
- type field MUST be one of: OPEN_APP, CLICK, TYPE, SCROLL, FIND_AND_CLICK, CONDITIONAL, BACK
"""
    }

    suspend fun synthesize(teaching: TeachingResult): Workflow? {
        val actionsDescription = formatActionsForLLM(teaching.actions)
        val prompt = buildSynthesisPrompt(teaching.utterance, actionsDescription, teaching.targetPackage)

        Log.d(TAG, "Synthesizing workflow for: \"${teaching.utterance}\"")
        Log.d(TAG, "Actions to synthesize:\n$actionsDescription")

        val response = gemini.generate(
            systemPrompt = SYNTHESIS_SYSTEM_PROMPT,
            userPrompt = prompt,
            jsonMode = true
        )

        if (response == null) {
            Log.e(TAG, "Gemini returned null for synthesis")
            return null
        }

        Log.d(TAG, "Raw synthesis response: $response")
        return parseWorkflowFromJson(response, teaching.targetPackage)
    }

    private fun formatActionsForLLM(actions: List<RecordedAction>): String {
        return actions.mapIndexed { i, action ->
            val target = action.targetNode
            buildString {
                append("Step $i: ${action.type.name}")
                if (target != null) {
                    append(" on [")
                    target.text?.let { append("text=\"$it\", ") }
                    target.contentDescription?.let { append("desc=\"$it\", ") }
                    target.resourceId?.let { append("id=\"$it\", ") }
                    target.hintText?.let { append("hint=\"$it\", ") }
                    append("class=${target.className}")
                    if (target.isEditable) append(", editable")
                    if (target.isScrollable) append(", scrollable")
                    if (target.isClickable) append(", clickable")
                    append("]")
                }
                if (action.type == ActionType.TYPE) {
                    append(" text=\"${action.data["text"] ?: ""}\"")
                }
                if (action.type == ActionType.SCROLL) {
                    append(" direction=${action.data["direction"] ?: "DOWN"}")
                }
                append(" (package: ${action.packageName})")
            }
        }.joinToString("\n")
    }

    private fun buildSynthesisPrompt(utterance: String, actionsDescription: String, targetPackage: String): String {
        return """
VOICE COMMAND: "$utterance"

TARGET APP PACKAGE: $targetPackage

DEMONSTRATED ACTIONS:
$actionsDescription

Analyze these actions and produce a generalized workflow JSON. Identify which values from the voice command are parameters (slots) that could change in future uses.
""".trimIndent()
    }

    private fun parseWorkflowFromJson(jsonStr: String, fallbackPackage: String): Workflow? {
        return try {
            val cleanJson = jsonStr.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = json.parseToJsonElement(cleanJson).jsonObject

            val flowName = obj["flow_name"]?.jsonPrimitive?.contentOrNull ?: "unnamed_flow"
            val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val trigger = obj["trigger_utterance"]?.jsonPrimitive?.contentOrNull ?: ""
            val appPackage = obj["target_app_package"]?.jsonPrimitive?.contentOrNull ?: fallbackPackage

            val slots = parseSlots(obj["slots"])
            val steps = parseSteps(obj["steps"])

            Workflow(
                name = flowName,
                description = description,
                triggerUtterance = trigger,
                targetAppPackage = appPackage,
                slots = slots,
                steps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse workflow JSON", e)
            null
        }
    }

    private fun parseSlots(element: JsonElement?): Map<String, Slot> {
        if (element == null || element !is JsonObject) return emptyMap()
        val result = mutableMapOf<String, Slot>()
        for ((name, slotJson) in element.jsonObject) {
            try {
                val obj = slotJson.jsonObject
                result[name] = Slot(
                    type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "string",
                    defaultValue = obj["default_value"]?.jsonPrimitive?.contentOrNull,
                    isRequired = obj["is_required"]?.jsonPrimitive?.booleanOrNull ?: false,
                    isVariable = obj["is_variable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse slot: $name", e)
            }
        }
        return result
    }

    private fun parseSteps(element: JsonElement?): List<WorkflowStep> {
        if (element == null || element !is JsonArray) return emptyList()
        return element.jsonArray.mapIndexedNotNull { idx, stepJson ->
            try {
                val obj = stepJson.jsonObject
                val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull ?: "CLICK"
                val stepType = try {
                    StepType.valueOf(typeStr.uppercase())
                } catch (e: Exception) {
                    StepType.CLICK
                }

                val targetObj = obj["target"]?.jsonObject
                val target = if (targetObj != null) {
                    TargetSpec(
                        text = targetObj["text"]?.jsonPrimitive?.contentOrNull,
                        textContains = targetObj["text_contains"]?.jsonPrimitive?.contentOrNull,
                        resourceId = targetObj["resource_id"]?.jsonPrimitive?.contentOrNull,
                        className = targetObj["class_name"]?.jsonPrimitive?.contentOrNull,
                        contentDescription = targetObj["content_description"]?.jsonPrimitive?.contentOrNull,
                        hintText = targetObj["hint_text"]?.jsonPrimitive?.contentOrNull,
                        isEditable = targetObj["is_editable"]?.jsonPrimitive?.booleanOrNull,
                        isScrollable = targetObj["is_scrollable"]?.jsonPrimitive?.booleanOrNull,
                        contextTextContains = targetObj["context_text_contains"]?.jsonPrimitive?.contentOrNull,
                        semantic = targetObj["semantic"]?.jsonPrimitive?.contentOrNull
                    )
                } else TargetSpec()

                WorkflowStep(
                    index = obj["index"]?.jsonPrimitive?.intOrNull ?: idx,
                    type = stepType,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    target = target,
                    value = obj["value"]?.jsonPrimitive?.contentOrNull,
                    scrollToFind = obj["scroll_to_find"]?.jsonPrimitive?.booleanOrNull ?: false,
                    isCredentialBoundary = obj["is_credential_boundary"]?.jsonPrimitive?.booleanOrNull ?: false,
                    expectedResult = obj["expected_result"]?.jsonPrimitive?.contentOrNull
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse step at index $idx", e)
                null
            }
        }
    }
}
