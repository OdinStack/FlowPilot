package com.flowpilot.data.repository

import android.util.Log
import com.flowpilot.data.db.ExecutionLogEntity
import com.flowpilot.data.db.WorkflowDao
import com.flowpilot.data.db.WorkflowEntity
import com.flowpilot.data.models.Workflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExecutionLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val success: Boolean = false,
    val stoppedAtStep: Int? = null,
    val stopReason: String? = null,
    val stepsCompleted: Int = 0,
    val totalSteps: Int = 0,
    val details: List<String> = emptyList()
)

class WorkflowRepository(private val dao: WorkflowDao) {

    companion object {
        private const val TAG = "WorkflowRepository"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    fun getAllWorkflows(): Flow<List<Workflow>> {
        return dao.getAllWorkflows().map { entities ->
            entities.mapNotNull { it.toWorkflow() }
        }
    }

    suspend fun getAllWorkflowsSync(): List<Workflow> {
        return dao.getAllWorkflowsSync().mapNotNull { it.toWorkflow() }
    }

    suspend fun getWorkflowById(id: String): Workflow? {
        return dao.getWorkflowById(id)?.toWorkflow()
    }

    suspend fun saveWorkflow(workflow: Workflow) {
        val leanWorkflow = workflow.copy(triggerEmbedding = null)
        val entity = WorkflowEntity(
            id = workflow.id,
            name = workflow.name,
            description = workflow.description,
            triggerUtterance = workflow.triggerUtterance,
            triggerEmbeddingJson = workflow.triggerEmbedding?.let {
                try { json.encodeToString(it) } catch (e: Exception) { null }
            },
            targetAppPackage = workflow.targetAppPackage,
            workflowJson = json.encodeToString(leanWorkflow),
            createdAt = workflow.createdAt
        )
        withContext(Dispatchers.IO) {
            dao.insertWorkflow(entity)
        }
        Log.i(TAG, "Saved workflow: ${workflow.name} (${workflow.id})")
    }

    suspend fun deleteWorkflow(id: String) {
        withContext(Dispatchers.IO) {
            dao.deleteWorkflowById(id)
        }
        Log.i(TAG, "Deleted workflow: $id")
    }

    suspend fun logExecution(log: ExecutionLog) {
        withContext(Dispatchers.IO) {
            dao.insertLog(log.toEntity())
        }
    }

    suspend fun getLastExecutionLog(): ExecutionLog? {
        return dao.getLastLog()?.toExecutionLog()
    }

    // Conversion helpers
    private fun WorkflowEntity.toWorkflow(): Workflow? {
        return try {
            val wf = json.decodeFromString<Workflow>(workflowJson)
            val embedding = triggerEmbeddingJson?.let {
                try { json.decodeFromString<List<Float>>(it) } catch (e: Exception) { null }
            }
            wf.copy(
                id = id,
                name = name,
                description = description,
                triggerUtterance = triggerUtterance,
                targetAppPackage = targetAppPackage,
                triggerEmbedding = embedding ?: wf.triggerEmbedding,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize workflow: $id ($name)", e)
            try {
                Workflow(
                    id = id,
                    name = name,
                    description = description,
                    triggerUtterance = triggerUtterance,
                    targetAppPackage = targetAppPackage,
                    createdAt = createdAt
                )
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun ExecutionLog.toEntity(): ExecutionLogEntity {
        return ExecutionLogEntity(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            startedAt = startedAt,
            completedAt = completedAt,
            success = success,
            stoppedAtStep = stoppedAtStep,
            stopReason = stopReason,
            stepsCompleted = stepsCompleted,
            totalSteps = totalSteps,
            detailsJson = json.encodeToString(details)
        )
    }

    private fun ExecutionLogEntity.toExecutionLog(): ExecutionLog {
        return ExecutionLog(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            startedAt = startedAt,
            completedAt = completedAt,
            success = success,
            stoppedAtStep = stoppedAtStep,
            stopReason = stopReason,
            stepsCompleted = stepsCompleted,
            totalSteps = totalSteps,
            details = try { json.decodeFromString(detailsJson) } catch (e: Exception) { emptyList() }
        )
    }
}
