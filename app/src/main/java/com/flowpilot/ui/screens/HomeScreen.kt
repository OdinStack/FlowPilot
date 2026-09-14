package com.flowpilot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.util.openAccessibilitySettings
import com.flowpilot.voice.VoiceState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val isServiceConnected by FlowPilotAccessibilityService.isServiceConnected.collectAsState()
    val systemState by viewModel.systemState.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val isTeaching by viewModel.isTeaching.collectAsState()
    val actionsCount by viewModel.recordedActionsCount.collectAsState()
    val lastSummary by viewModel.lastCapturedSummary.collectAsState()
    val lastTeachingResult by viewModel.lastTeachingResult.collectAsState()

    val isListening = voiceState is VoiceState.Listening

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "FlowPilot",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Teach once. Replay anything.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Service status card
        ServiceStatusCard(isConnected = isServiceConnected)

        Spacer(modifier = Modifier.height(16.dp))

        // Center Area: Teaching Card or Microphone
        if (isTeaching) {
            TeachingActiveCard(
                actionCount = actionsCount,
                lastAction = lastSummary,
                onDone = { viewModel.stopTeaching() },
                onCancel = { viewModel.cancelTeaching() }
            )
        } else {
            MicButton(
                isListening = isListening,
                onClick = { viewModel.onMicTapped() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dynamic status text
        val displayText = when (val vs = voiceState) {
            is VoiceState.Listening -> "Listening... Speak your command"
            is VoiceState.Partial -> "“${vs.text}”"
            is VoiceState.Processing -> "Processing speech..."
            is VoiceState.Result -> "“${vs.text}”"
            is VoiceState.Error -> "⚠️ ${vs.message}"
            is VoiceState.Idle -> systemState.message
        }

        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isListening) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = if (voiceState is VoiceState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Learned flow list or placeholder
        val result = lastTeachingResult
        if (result != null) {
            LearnedFlowCard(
                result = result,
                onDismiss = { viewModel.clearLastTeachingResult() },
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "No learned workflows yet. Tap the mic to teach one!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun LearnedFlowCard(
    result: com.flowpilot.engine.TeachingResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "✨ Learned Workflow",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "“${result.utterance}”",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "App: ${result.targetPackage} • ${result.actions.size} cleaned actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(result.actions.size) { idx ->
                    val action = result.actions[idx]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            text = action.toShortString(),
                            modifier = Modifier.padding(6.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TeachingActiveCard(
    actionCount: Int,
    lastAction: String,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🔴 Teaching in Progress",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$actionCount actions captured",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            if (lastAction.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    )
                ) {
                    Text(
                        text = lastAction,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel")
                }

                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Done")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Done (Save)")
                }
            }
        }
    }
}

@Composable
fun ServiceStatusCard(isConnected: Boolean) {
    val context = LocalContext.current
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.errorContainer,
        label = "statusColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isConnected) {
                    Modifier.clickable {
                        context.openAccessibilitySettings()
                    }
                } else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Text(
            text = if (isConnected) "✅ Accessibility Service connected"
                   else "❌ Accessibility Service not connected — tap to enable",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun MicButton(
    isListening: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scaleAnim"
    )

    Box(contentAlignment = Alignment.Center) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .background(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        shape = CircleShape
                    )
            )
        }
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            containerColor = if (isListening) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.primary,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Microphone",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
