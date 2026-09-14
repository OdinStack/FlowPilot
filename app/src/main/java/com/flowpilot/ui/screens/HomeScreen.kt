package com.flowpilot.ui.screens

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.util.openAccessibilitySettings

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val isServiceConnected by FlowPilotAccessibilityService.isServiceConnected.collectAsState()

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

        Spacer(modifier = Modifier.weight(1f))

        // Mic button (placeholder — will be wired to VoiceManager in Phase 3)
        MicButton(
            isListening = false,
            onClick = { /* TODO: Wire to voice in Phase 3 */ }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Status text
        Text(
            text = if (isServiceConnected) "Tap the microphone and tell me what to do"
                   else "Enable accessibility service first",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Learned flows list (empty for now)
        Text(
            text = "No learned workflows yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ServiceStatusCard(isConnected: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
        targetValue = if (isListening) 1.15f else 1f,
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
                    .size(88.dp)
                    .scale(scale)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
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
