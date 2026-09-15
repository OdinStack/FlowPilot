package com.flowpilot.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY createdAt DESC")
    fun getAllWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getWorkflowById(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflows")
    suspend fun getAllWorkflowsSync(): List<WorkflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkflow(workflow: WorkflowEntity)

    @Delete
    suspend fun deleteWorkflow(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteWorkflowById(id: String)

    @Query("SELECT * FROM execution_logs ORDER BY startedAt DESC LIMIT 10")
    fun getRecentLogs(): Flow<List<ExecutionLogEntity>>

    @Insert
    suspend fun insertLog(log: ExecutionLogEntity)

    @Query("SELECT * FROM execution_logs ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLastLog(): ExecutionLogEntity?
}
