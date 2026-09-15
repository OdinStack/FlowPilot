package com.flowpilot.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val triggerUtterance: String,
    val triggerEmbeddingJson: String?,
    val targetAppPackage: String,
    val workflowJson: String,
    val createdAt: Long
)
