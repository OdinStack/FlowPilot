package com.flowpilot.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkflowEntity::class, ExecutionLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FlowPilotDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao

    companion object {
        @Volatile
        private var INSTANCE: FlowPilotDatabase? = null

        fun getInstance(context: Context): FlowPilotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowPilotDatabase::class.java,
                    "flowpilot.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
