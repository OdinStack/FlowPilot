package com.flowpilot

import android.app.Application
import com.flowpilot.ai.FlowMatcher
import com.flowpilot.ai.GeminiClient
import com.flowpilot.ai.IntentProcessor
import com.flowpilot.ai.WorkflowSynthesizer
import com.flowpilot.data.db.FlowPilotDatabase
import com.flowpilot.data.repository.WorkflowRepository
import com.flowpilot.util.Constants

class FlowPilotApp : Application() {

    companion object {
        lateinit var instance: FlowPilotApp
            private set
    }

    val database by lazy { FlowPilotDatabase.getInstance(this) }
    val repository by lazy { WorkflowRepository(database.workflowDao()) }
    val geminiClient by lazy { GeminiClient(Constants.GEMINI_API_KEY) }
    val workflowSynthesizer by lazy { WorkflowSynthesizer(geminiClient) }
    val intentProcessor by lazy { IntentProcessor(geminiClient) }
    val flowMatcher by lazy { FlowMatcher(geminiClient) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
