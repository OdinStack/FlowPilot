package com.flowpilot.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowpilot.FlowPilotApp
import com.flowpilot.data.repository.ExecutionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LogViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LogViewModel"
    }

    private val app = application as FlowPilotApp

    private val _recentLogs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val recentLogs: StateFlow<List<ExecutionLog>> = _recentLogs.asStateFlow()

    private val _lastLog = MutableStateFlow<ExecutionLog?>(null)
    val lastLog: StateFlow<ExecutionLog?> = _lastLog.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.getRecentLogs().collect { logs ->
                _recentLogs.value = logs
                _lastLog.value = logs.firstOrNull()
                Log.d(TAG, "Loaded ${logs.size} execution logs")
            }
        }
    }
}
