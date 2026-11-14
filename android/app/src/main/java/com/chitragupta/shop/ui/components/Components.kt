package com.chitragupta.shop.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chitragupta.shop.ui.theme.Palette
import com.chitragupta.shop.ui.theme.Spacing
import com.chitragupta.shop.ui.theme.status
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

enum class ButtonKind { Primary, Secondary, Tertiary, Success, Danger }

@Composable
fun ChitraguptaButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null,
) {
    val shape = MaterialTheme.shapes.small
    val m = modifier.height(56.dp)
    val content: @Composable () -> Unit = {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = LocalContentColorCompat.current())
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(Spacing.s)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    val on = enabled && !loading
    when (kind) {
        ButtonKind.Primary -> Button(onClick, m, on, shape = shape, content = { content() })
        ButtonKind.Success -> Button(onClick, m, on, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Success, contentColor = Palette.Navy), content = { content() })
        ButtonKind.Danger -> Button(onClick, m, on, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), content = { content() })
        ButtonKind.Secondary -> OutlinedButton(onClick, m, on, shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = { content() })
        ButtonKind.Tertiary -> TextButton(onClick, m, on, shape = shape, content = { content() })
    }
}

/** Tiny helper: the spinner inside a button takes the button's content colour. */
object LocalContentColorCompat { @Composable fun current(): Color = androidx.compose.material3.LocalContentColor.current }

@Composable
fun ChitraguptaCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, container: Color = MaterialTheme.colorScheme.surface,
    padding: Dp = Spacing.l, content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) Card(onClick, modifier, shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = inner)
    else Card(modifier, shape = MaterialTheme.shapes.medium, colors = colors, border = border, content = inner)
}

enum class Tone { Neutral, Success, Warning, Error, Info }

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Success -> st.successContainer to st.onSuccessContainer
        Tone.Warning -> st.warningContainer to st.onWarningContainer
        Tone.Error -> cs.errorContainer to cs.onErrorContainer
        Tone.Info -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Neutral -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Row(modifier.clip(CircleShape).background(bg).padding(horizontal = Spacing.m, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp), tint = fg); Spacer(Modifier.width(Spacing.xs)) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
    }
}

@Composable
fun BannerMessage(text: String, tone: Tone, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val st = MaterialTheme.status; val cs = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (tone) {
        Tone.Success -> Triple(st.successContainer, st.onSuccessContainer, Icons.Filled.CheckCircle)
        Tone.Warning -> Triple(st.warningContainer, st.onWarningContainer, Icons.Filled.WarningAmber)
        Tone.Error -> Triple(cs.errorContainer, cs.onErrorContainer, Icons.Filled.ErrorOutline)
        else -> Triple(cs.primaryContainer, cs.onPrimaryContainer, Icons.Filled.Info)
    }
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(bg).padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.m))
        Text(text, Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyLarge)
        if (action != null && onAction != null) TextButton(onAction) { Text(action, color = fg, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (icon != null) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(Spacing.s))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(Spacing.s)); action() }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier, lines: Int = 3) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        repeat(lines) {
            Box(Modifier.fillMaxWidth(if (it == lines - 1) 0.6f else 1f).height(24.dp).alpha(a).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant))
        }
    }
}

@Composable
fun AmountText(amount: Int, modifier: Modifier = Modifier, big: Boolean = true, color: Color = Color.Unspecified) =
    Text("₹$amount", modifier, color = color, style = if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleMedium)

@Composable
fun StepIndicator(step: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        repeat(total) {
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                .background(if (it < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** Our own mark: a shopping bag with a voice waveform inside. */
@Composable
fun LogoMark(modifier: Modifier = Modifier.size(96.dp), bag: Color = Palette.Navy, wave: Color = Palette.Sky, bg: Color = Color.Transparent) {
    Canvas(modifier) {
        val s = size.minDimension
        val u = s / 96f
        drawRoundRect(bg, size = Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24 * u))
        // bag body
        drawRoundRect(bag, Offset(20 * u, 34 * u), Size(56 * u, 48 * u), androidx.compose.ui.geometry.CornerRadius(10 * u))
        // handle
        val handle = Path().apply { moveTo(34 * u, 40 * u); cubicTo(34 * u, 14 * u, 62 * u, 14 * u, 62 * u, 40 * u) }
        drawPath(handle, bag, style = Stroke(width = 6 * u, cap = StrokeCap.Round))
        // waveform bars
        val bars = listOf(52f to 10f, 46f to 22f, 40f to 34f, 46f to 22f, 52f to 10f)
        bars.forEachIndexed { i, (_, h) ->
            val x = (31 + i * 8.5f) * u
            drawLine(wave, Offset(x, (58 - h / 2) * u), Offset(x, (58 + h / 2) * u), strokeWidth = 4.5f * u, cap = StrokeCap.Round)
        }
    }
}

fun Modifier.softShadow(radius: Dp = 16.dp, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)) =
    this.shadow(radius, shape, ambientColor = Palette.Navy.copy(alpha = 0.12f), spotColor = Palette.Navy.copy(alpha = 0.12f))

fun Modifier.selectableRow(selected: Boolean, onClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape) = this
    .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Sky else Color.Transparent, shape)
    .clickable(role = Role.RadioButton, onClick = onClick)
