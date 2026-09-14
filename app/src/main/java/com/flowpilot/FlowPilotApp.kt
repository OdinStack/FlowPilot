package com.flowpilot

import android.app.Application

class FlowPilotApp : Application() {

    companion object {
        lateinit var instance: FlowPilotApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
