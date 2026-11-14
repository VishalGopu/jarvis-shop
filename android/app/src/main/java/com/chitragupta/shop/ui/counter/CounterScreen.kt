package com.chitragupta.shop.ui.counter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.Config
import com.chitragupta.shop.data.Line
import com.chitragupta.shop.data.MenuItem
import com.chitragupta.shop.data.Order
import com.chitragupta.shop.ui.components.BannerMessage
import com.chitragupta.shop.ui.components.ButtonKind
import com.chitragupta.shop.ui.components.EmptyState
import com.chitragupta.shop.ui.components.ChitraguptaButton
import com.chitragupta.shop.ui.components.ChitraguptaCard
import com.chitragupta.shop.ui.components.MicButton
import com.chitragupta.shop.ui.components.MicState
import com.chitragupta.shop.ui.components.StatusChip
import com.chitragupta.shop.ui.components.Tone
import com.chitragupta.shop.ui.components.UpiQr
import com.chitragupta.shop.ui.fmtQty
import com.chitragupta.shop.ui.theme.Palette
import com.chitragupta.shop.ui.theme.Spacing
import com.chitragupta.shop.ui.unitLabel

@Composable
fun CounterScreen(graph: AppGraph, cfg: Config) {
    val vm: CounterViewModel = viewModel(factory = viewModelFactory { initializer { CounterViewModel(graph) } })
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val profile = cfg.profile
    var micDenied by remember { mutableStateOf(false) }

    LaunchedEffect(cfg) { vm.setConfig(cfg) }
    DisposableEffect(Unit) { vm.start(); onDispose { vm.stopListening() } }
    LaunchedEffect(s.successTick) { if (s.successTick > 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micDenied = !ok
        if (ok) vm.micTapped(true) {}
    }
    val hasMic = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    Column(Modifier.fillMaxSize()) {
        if (!s.connected) BannerMessage("Offline. Trying to reconnect...", Tone.Warning, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs))
        if (s.readyOrders.isNotEmpty()) ReadyStrip(s.readyOrders, profile.payAfter) { vm.openHandover(it) }
        s.error?.let { BannerMessage(it, Tone.Error, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs), "OK", vm::dismissError) }
        if (micDenied) BannerMessage("Microphone is off. Allow it in Settings to speak orders.", Tone.Warning,
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs), "Settings") {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.lines.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    s.reply?.let { Bubble(it, s.heard, onReplay = null, modifier = Modifier.padding(horizontal = Spacing.l)) }
                    EmptyState("Tap the mic and say the order", "In English, Hindi, Telugu or a mix. Or tap an item below.", icon = Icons.Filled.Mic)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    s.reply?.let { item { Bubble(it, s.heard, null) } }
                    items(s.lines, key = { it.id }) { line -> CartLine(line, onInc = { vm.inc(line) }, onDec = { vm.dec(line) }, onRemove = { vm.remove(line) }) }
                }
            }
        }

        if (!s.textMode) QuickAdd(cfg.menu.items.filter { !it.soldOut }, vm::add)
        if (s.textMode) TextInput(onSend = vm::sendText)
        TotalBar(s.total, s.count, profile.payAfter)
        Dock(s, profile.payAfter, onKeyboard = vm::toggleTextMode,
            onMic = { vm.micTapped(hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO) } },
            onCheckout = vm::checkout, onClear = vm::clear)
    }

    s.sheet?.let { sheet -> CounterSheet(sheet, cfg.mock, profile.payAfter, vm) }
}

@Composable
private fun ReadyStrip(orders: List<Order>, payAfter: Boolean, onClick: (Order) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.l, vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(orders, key = { it.id }) { o ->
            val needsPay = (payAfter || o.payLater) && o.paidAt == null
            AssistChip(onClick = { onClick(o) },
                label = { Text("#${o.number}${if (o.isPhone) " 📞" else ""} ready" + if (needsPay) " · collect ₹${o.total}" else "", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, null, tint = if (needsPay) Palette.Warning else Palette.Success) })
        }
    }
}

@Composable
private fun Bubble(reply: String, heard: String?, onReplay: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (!heard.isNullOrBlank()) Text("\"$heard\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(reply, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (onReplay != null) IconButton(onReplay) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Replay") }
        }
    }
}

@Composable
private fun CartLine(line: Line, onInc: () -> Unit, onDec: () -> Unit, onRemove: () -> Unit) {
    ChitraguptaCard(Modifier.fillMaxWidth(), padding = Spacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleMedium)
                val sub = buildString { append("₹${line.price}${unitLabel(line.unit)}"); if (!line.note.isNullOrBlank()) append(" · ${line.note}") }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Stepper(fmtQty(line.qty, line.unit), onInc, onDec)
            Spacer(Modifier.width(Spacing.m))
            Text("₹${line.amount}", Modifier.width(64.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
            IconButton(onRemove, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Remove ${line.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Stepper(label: String, onInc: () -> Unit, onDec: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onDec, Modifier.size(40.dp)) { Icon(Icons.Filled.Remove, "Less") }
        Text(label, Modifier.widthIn(min = 40.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        IconButton(onInc, Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "More") }
    }
}

@Composable
private fun QuickAdd(items: List<MenuItem>, onAdd: (MenuItem) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.l, vertical = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(items, key = { it.id }) { m ->
            AssistChip(onClick = { onAdd(m) }, label = { Text("${m.name} ₹${m.price}", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun TextInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs), placeholder = { Text("Type the order") },
        singleLine = true, shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend(text); text = "" }),
        trailingIcon = { IconButton({ onSend(text); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } })
}

@Composable
private fun TotalBar(total: Int, count: Int, payAfter: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l).clip(MaterialTheme.shapes.medium).background(Palette.Navy).padding(Spacing.l),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (count == 0) "No items yet" else "$count item${if (count > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            Text("₹$total", style = MaterialTheme.typography.displayMedium, color = Color.White)
        }
        if (payAfter) StatusChip("Pay after", Tone.Info)
    }
}

@Composable
private fun Dock(s: CounterState, payAfter: Boolean, onKeyboard: () -> Unit, onMic: () -> Unit, onCheckout: () -> Unit, onClear: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        IconButton(onKeyboard, Modifier.size(48.dp).align(Alignment.CenterStart)) { Icon(Icons.Filled.Keyboard, "Type instead of speaking") }
        MicButton(s.mic, onMic, Modifier.align(Alignment.Center))
        ChitraguptaButton(if (payAfter) "Send" else "Checkout", onCheckout, Modifier.width(132.dp).align(Alignment.CenterEnd), enabled = s.lines.isNotEmpty(), loading = s.checkingOut)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CounterSheet(sheet: Sheet, mock: Boolean, payAfter: Boolean, vm: CounterViewModel) {
    ModalBottomSheet(onDismissRequest = vm::closeSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            when (sheet) {
                is Sheet.Pay -> PaySheet(sheet, mock, vm)
                is Sheet.Sent -> {
                    SuccessMark()
                    Text("Order #${sheet.order.number}", style = MaterialTheme.typography.displayMedium)
                    Text("Sent to the team. Call the number when it's ready.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    ChitraguptaButton("Done", vm::closeSheet, Modifier.fillMaxWidth())
                }
                is Sheet.Handover -> HandoverSheet(sheet, payAfter || sheet.order.payLater, vm)
            }
        }
    }
}

@Composable
private fun SuccessMark() {
    Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Success), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, Modifier.size(40.dp), tint = Color.White)
    }
}

@Composable
private fun PaySheet(p: Sheet.Pay, mock: Boolean, vm: CounterViewModel) {
    if (p.paid) {
        SuccessMark()
        Text("₹${p.order.total} received", style = MaterialTheme.typography.displayMedium)
        StatusChip("Verified by server", Tone.Success, icon = Icons.Filled.CheckCircle)
        Text("Order #${p.order.number}", style = MaterialTheme.typography.titleLarge)
        if (p.pin != null) {
            Text("Pickup code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.pin, style = MaterialTheme.typography.displayMedium)
        }
        when (p.order.status) {
            "ready" -> StatusChip("Ready: collect at the counter", Tone.Success, icon = Icons.Filled.CheckCircle)
            "delivered" -> StatusChip("Collected", Tone.Success, icon = Icons.Filled.CheckCircle)
            "scheduled" -> StatusChip("Booked for later", Tone.Info)
            else -> StatusChip("Being prepared", Tone.Info)
        }
        Text("The kitchen has your order. This updates when it's ready.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChitraguptaButton("Done", vm::closeSheet, Modifier.fillMaxWidth())
        return
    }
    Text("Order #${p.order.number}", style = MaterialTheme.typography.titleLarge)
    Text("₹${p.order.total}", style = MaterialTheme.typography.displayMedium)
    p.qr?.let { UpiQr(it, Modifier.fillMaxWidth(0.7f)) }
    StatusChip("Waiting for payment", Tone.Warning)
    Text("Scan with Paytm or any UPI app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (mock) ChitraguptaButton("Simulate payment (demo)", vm::mockPay, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        ChitraguptaButton("Edit order", vm::cancelPayment, Modifier.weight(1f), kind = ButtonKind.Secondary)
        ChitraguptaButton("Cancel", vm::cancelPayment, Modifier.weight(1f), kind = ButtonKind.Tertiary)
    }
}

@Composable
private fun HandoverSheet(h: Sheet.Handover, payAfter: Boolean, vm: CounterViewModel) {
    Text("Order #${h.order.number} is ready", style = MaterialTheme.typography.titleLarge)
    h.order.lines.forEach { Text("${fmtQty(it.qty, it.unit)} ${it.name}", style = MaterialTheme.typography.bodyLarge) }
    if (h.error != null) BannerMessage(h.error, Tone.Error)
    if (h.order.paidAt == null) {
        Text("₹${h.order.total} to collect", style = MaterialTheme.typography.displayMedium)
        ChitraguptaButton("Show payment QR", vm::billWithQr, Modifier.fillMaxWidth())
        ChitraguptaButton("Cash received", vm::billWithCash, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    } else {
        var pin by remember { mutableStateOf("") }
        StatusChip("Paid", Tone.Success, icon = Icons.Filled.CheckCircle)
        // Pay-after shops have no pickup code (the customer paid at the counter), so it's one tap.
        if (payAfter) { ChitraguptaButton("Handed over", { vm.deliver(null, true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Success, loading = h.busy); return }
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Pickup code (from customer)") }, singleLine = true,
            shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
        ChitraguptaButton("Hand over", { vm.deliver(pin, false) }, Modifier.fillMaxWidth(), kind = ButtonKind.Success, enabled = pin.length >= 3, loading = h.busy)
        ChitraguptaButton("Hand over without code", { vm.deliver(null, true) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
    }
}
