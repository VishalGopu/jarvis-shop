package com.chitragupta.shop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class MicState { Idle, Listening, Processing }

@Composable
fun MicButton(state: MicState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val t = rememberInfiniteTransition(label = "mic")
    val pulse by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val desc = when (state) { MicState.Idle -> "Tap to speak"; MicState.Listening -> "Listening, tap to stop"; MicState.Processing -> "Working on it" }
    Box(modifier.size(size * 1.5f).semantics { contentDescription = "Microphone"; stateDescription = desc }, contentAlignment = Alignment.Center) {
        if (state == MicState.Listening) {
            Box(Modifier.size(size).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
        }
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(if (state == MicState.Listening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                .clickable(enabled = state != MicState.Processing, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                MicState.Processing -> CircularProgressIndicator(Modifier.size(size / 2), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                MicState.Listening -> Icon(Icons.Filled.Stop, null, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSecondary)
                MicState.Idle -> Icon(Icons.Filled.Mic, null, Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
