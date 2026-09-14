package com.flowpilot.ui.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.data.models.RecordedAction
import com.flowpilot.data.models.UINode
import com.flowpilot.util.openAccessibilitySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isConnected by FlowPilotAccessibilityService.isServiceConnected.collectAsState()
    val lastAction by FlowPilotAccessibilityService.lastActionDescription.collectAsState()
    val currentSnapshot by FlowPilotAccessibilityService.currentSnapshot.collectAsState()

    var capturedNodes by remember { mutableStateOf<List<UINode>>(emptyList()) }
    var capturedPackage by remember { mutableStateOf("") }
    var capturedSignature by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    val recordedActions = remember { mutableStateListOf<RecordedAction>() }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Debug Console",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Service status
        Text(
            text = if (isConnected) "🟢 Service: Connected" else "🔴 Service: Disconnected",
            style = MaterialTheme.typography.bodyLarge
        )

        if (!isConnected) {
            Button(onClick = { context.openAccessibilitySettings() }) {
                Text("Open Accessibility Settings")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val service = FlowPilotAccessibilityService.instance ?: return@Button
                    val snapshot = service.captureCurrentScreen() ?: return@Button
                    capturedNodes = snapshot.nodes.filter { it.isVisibleToUser }
                    capturedPackage = snapshot.packageName
                    capturedSignature = snapshot.screenSignature
                },
                enabled = isConnected
            ) {
                Text("Capture Screen")
            }

            if (!isRecording) {
                Button(
                    onClick = {
                        val service = FlowPilotAccessibilityService.instance ?: return@Button
                        recordedActions.clear()
                        isRecording = true
                        service.startRecording(null)
                    },
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("Start Recording")
                }
            } else {
                Button(
                    onClick = {
                        FlowPilotAccessibilityService.instance?.stopRecording()
                        isRecording = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("⏹ Stop Recording")
                }
            }

            OutlinedButton(onClick = {
                capturedNodes = emptyList()
                recordedActions.clear()
            }) {
                Text("Clear")
            }
        }

        androidx.compose.runtime.LaunchedEffect(isRecording) {
            if (isRecording) {
                FlowPilotAccessibilityService.recordedActions.collect { action ->
                    recordedActions.add(action)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Recording status
        if (isRecording) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔴 Recording... (${recordedActions.size} actions)\nLast: $lastAction",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Results
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Show capture info
            if (capturedNodes.isNotEmpty()) {
                item {
                    Text(
                        text = "Package: $capturedPackage | Sig: $capturedSignature | Nodes: ${capturedNodes.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Show captured interactive nodes
            val interactiveNodes = capturedNodes.filter {
                it.isClickable || it.isEditable || it.isScrollable || it.text != null
            }
            items(interactiveNodes) { node ->
                NodeCard(node)
            }

            // Show recorded actions
            if (recordedActions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recorded Actions (${recordedActions.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(recordedActions.toList()) { action ->
                    ActionCard(action)
                }
            }
        }
    }
}

@Composable
fun NodeCard(node: UINode) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                node.isEditable -> MaterialTheme.colorScheme.tertiaryContainer
                node.isClickable -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Text(
            text = node.toShortString(),
            modifier = Modifier.padding(8.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}

@Composable
fun ActionCard(action: RecordedAction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = action.toShortString(),
            modifier = Modifier.padding(8.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}
