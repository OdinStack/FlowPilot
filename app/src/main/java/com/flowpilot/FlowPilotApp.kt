package com.flowpilot

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        var currentActivity: Activity? = null
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

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
        })
    }
}
