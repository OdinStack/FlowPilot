package com.flowpilot.ai

import android.util.Log
import com.flowpilot.data.models.Workflow

class FlowMatcher(private val gemini: GeminiClient) {

    companion object {
        private const val TAG = "FlowMatcher"
    }

    /**
     * Pre-compute and return the embedding for a workflow's trigger utterance.
     */
    suspend fun computeTriggerEmbedding(utterance: String): List<Float>? {
        val embedding = gemini.embed(utterance) ?: return null
        return embedding.toList()
    }

    /**
     * Quick similarity check between a command and all stored flows.
     * Returns flows sorted by similarity score (descending).
     */
    suspend fun rankFlows(
        command: String,
        flows: List<Workflow>
    ): List<Pair<Workflow, Float>> {
        val commandEmb = gemini.embed(command) ?: return emptyList()

        return flows.mapNotNull { flow ->
            val flowEmb = flow.triggerEmbedding?.toFloatArray() ?: return@mapNotNull null
            val similarity = gemini.cosineSimilarity(commandEmb, flowEmb)
            Log.d(TAG, "Flow '${flow.name}' similarity: $similarity")
            flow to similarity
        }.sortedByDescending { it.second }
    }
}
