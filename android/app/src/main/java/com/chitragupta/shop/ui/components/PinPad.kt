package com.chitragupta.shop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chitragupta.shop.ui.theme.Spacing

@Composable
fun PinDots(length: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = "$filled of $length digits entered" }, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        repeat(length) {
            Box(Modifier.size(16.dp).clip(CircleShape)
                .background(if (it < filled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** Number pad that edits [value] (digits only, up to [max]). */
@Composable
fun PinPad(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, max: Int = 6) {
    val haptic = LocalHapticFeedback.current
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape)
                        .clickable(enabled = k.isNotEmpty()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(if (k == "⌫") value.dropLast(1) else if (value.length < max) value + k else value)
                        }, contentAlignment = Alignment.Center) {
                        when (k) {
                            "" -> {}
                            "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                            else -> Text(k, style = MaterialTheme.typography.displayMedium)
                        }
                    }
                }
            }
        }
    }
}
