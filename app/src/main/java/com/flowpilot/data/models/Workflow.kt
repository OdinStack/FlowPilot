package com.flowpilot.data.models

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val triggerUtterance: String,
    val triggerEmbedding: List<Float>? = null,
    val targetAppPackage: String,
    val slots: Map<String, Slot> = emptyMap(),
    val steps: List<WorkflowStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Slot(
    val type: String = "string",
    val defaultValue: String? = null,
    val isRequired: Boolean = false,
    val isVariable: Boolean = true,
    val description: String = "",
    val usedInSteps: List<Int> = emptyList()
)

@Serializable
data class WorkflowStep(
    val index: Int,
    val type: StepType,
    val description: String = "",
    val target: TargetSpec = TargetSpec(),
    val value: String? = null,
    val scrollToFind: Boolean = false,
    val isCredentialBoundary: Boolean = false,
    val expectedResult: String? = null
)

@Serializable
enum class StepType {
    OPEN_APP, CLICK, TYPE, SCROLL, FIND_AND_CLICK, CONDITIONAL, BACK
}

@Serializable
data class TargetSpec(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val isEditable: Boolean? = null,
    val isScrollable: Boolean? = null,
    val contextTextContains: String? = null,
    val semantic: String? = null
)
