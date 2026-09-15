package com.flowpilot.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val startedAt: Long,
    val completedAt: Long?,
    val success: Boolean,
    val stoppedAtStep: Int?,
    val stopReason: String?,
    val stepsCompleted: Int,
    val totalSteps: Int,
    val detailsJson: String
)
