package com.chitragupta.shop.ui.kitchen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.Chef
import com.chitragupta.shop.data.Config
import com.chitragupta.shop.data.KLine
import com.chitragupta.shop.data.Order
import com.chitragupta.shop.ui.components.BannerMessage
import com.chitragupta.shop.ui.components.ButtonKind
import com.chitragupta.shop.ui.components.EmptyState
import com.chitragupta.shop.ui.components.ChitraguptaButton
import com.chitragupta.shop.ui.components.ChitraguptaCard
import com.chitragupta.shop.ui.components.LoadingBlock
import com.chitragupta.shop.ui.components.MicButton
import com.chitragupta.shop.ui.components.StatusChip
import com.chitragupta.shop.ui.components.Tone
import com.chitragupta.shop.ui.clockTime
import com.chitragupta.shop.ui.fmtQty
import com.chitragupta.shop.ui.minutesSince
import com.chitragupta.shop.ui.theme.Palette
import com.chitragupta.shop.ui.theme.Spacing

@Composable
fun KitchenScreen(graph: AppGraph, cfg: Config) {
    val vm: KitchenViewModel = viewModel(factory = viewModelFactory { initializer { KitchenViewModel(graph) } })
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val words = cfg.profile.words
    DisposableEffect(Unit) { vm.start(); onDispose { vm.stop() } }
    LaunchedEffect(s.taskTick) { if (s.taskTick > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    LaunchedEffect(s.doneTick) { if (s.doneTick > 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.micTapped(true) {} }
    val hasMic = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

    val k = s.kitchen
    Column(Modifier.fillMaxSize()) {
        if (!s.connected) BannerMessage("Offline. Trying to reconnect...", Tone.Warning, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs))
        s.error?.let { BannerMessage(it, Tone.Error, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs), "OK", vm::dismissError) }
        when {
            k == null && s.loading -> LoadingBlock(lines = 4)
            k == null -> EmptyState("Couldn't load ${words.station.lowercase()}", s.error ?: "", icon = Icons.Filled.SwapHoriz) { ChitraguptaButton("Try again", vm::refresh) }
            else -> {
                val me = k.chefs.firstOrNull { it.id == s.me }
                if (k.chefs.isNotEmpty() && me == null && s.me != ALL_ORDERS) ChefPicker(k.chefs, words.staff, vm)
                else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { WorkList(me, k.orders.filter { it.status == "cooking" }, k.upcoming, words.working, words.station, vm) }
                    if (me != null && me.online) {
                        Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) { MicButton(s.mic, { vm.micTapped(hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO) } }, size = 56.dp) }
                            ChitraguptaButton("Done", vm::done, Modifier.weight(1f), kind = ButtonKind.Success, enabled = me.task != null, icon = Icons.Filled.CheckCircle)
                        }
                        s.heard?.let { Text("Heard: \"$it\"", Modifier.padding(horizontal = Spacing.l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChefPicker(chefs: List<Chef>, staff: String, vm: KitchenViewModel) {
    LazyColumn(contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        item {
            Text("Who are you?", style = MaterialTheme.typography.titleLarge)
            Text("Pick your name to get your tasks on this phone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.s))
        }
        items(chefs, key = { it.id }) { c ->
            ChitraguptaCard(Modifier.fillMaxWidth(), onClick = { vm.chooseMe(c.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(staff, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(if (c.task != null) "Busy" else if (c.online) "Online" else "Offline", if (c.task != null) Tone.Warning else if (c.online) Tone.Success else Tone.Neutral)
                }
            }
        }
        item { ChitraguptaButton("Just show all orders", { vm.chooseMe(ALL_ORDERS) }, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary) }
    }
}

@Composable
private fun WorkList(me: Chef?, cooking: List<Order>, upcoming: List<Order>, working: String, station: String, vm: KitchenViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (me != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(me.name, style = MaterialTheme.typography.titleLarge)
                        Text(if (me.online) "On shift" else "Off shift", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(me.online, vm::setOnline)
                }
            }
            item {
                val t = me.task
                when {
                    !me.online -> EmptyState("You're off shift", "Turn on the switch to start getting tasks.", icon = Icons.Filled.Groups)
                    t == null -> EmptyState("No tasks", "You're all caught up.", icon = Icons.Filled.CheckCircle)
                    else -> TaskCard(t.number, t.table, t.since, t.items, working, vm)
                }
            }
            if (me.queued.isNotEmpty()) item {
                Text("Next for you", style = MaterialTheme.typography.titleMedium)
                me.queued.forEach { q -> Text("#${q.number}${if (q.from.isNotBlank()) " from ${q.from}" else ""}: " + q.items.joinToString { "${fmtQty(it.qty, it.unit)} ${it.name}" }, style = MaterialTheme.typography.bodyLarge) }
            }
            item { Row { ChitraguptaButton("Not me", { vm.chooseMe(null) }, kind = ButtonKind.Tertiary); Spacer(Modifier.width(Spacing.s)); if (me.task != null) ChitraguptaButton("Pass on", vm::transfer, kind = ButtonKind.Tertiary) } }
        }
        if (upcoming.isNotEmpty()) {
            item { Text("📞 Upcoming phone orders", style = MaterialTheme.typography.titleMedium) }
            items(upcoming, key = { "up_" + it.id }) { o ->
                ChitraguptaCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("#${o.number}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        StatusChip("Due ${o.pickupAt?.let(::clockTime) ?: "later"}", Tone.Info)
                    }
                    Text(o.lines.joinToString { "${fmtQty(it.qty, it.unit)} ${it.name}" }, style = MaterialTheme.typography.bodyLarge)
                    o.startAt?.let { Text("Starts at ${clockTime(it)}; you'll be reminded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { Text("All orders in progress", style = MaterialTheme.typography.titleMedium) }
        if (cooking.isEmpty()) item { Text("Nothing is being $working right now.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(cooking, key = { it.id }) { o ->
            ChitraguptaCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${o.number}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    o.table?.takeIf { it.isNotBlank() }?.let { StatusChip("Table $it", Tone.Neutral); Spacer(Modifier.width(Spacing.s)) }
                    if (o.isPhone) { StatusChip("📞 Due ${o.pickupAt?.let(::clockTime) ?: "soon"}", Tone.Info); Spacer(Modifier.width(Spacing.s)) }
                    ChitraguptaButton("Ready", { vm.orderReady(o.number) }, Modifier.height(48.dp), kind = ButtonKind.Success)
                }
                Spacer(Modifier.height(Spacing.s))
                o.lines.forEach { l ->
                    Text(if (l.unavailable) "${fmtQty(l.qty, l.unit)} ${l.name} (unavailable)" else "${fmtQty(l.qty, l.unit)} ${l.name}${l.note?.let { " ($it)" } ?: ""}",
                        style = MaterialTheme.typography.bodyLarge, color = if (l.unavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TaskCard(number: Int, table: String?, since: Long, items: List<KLine>, working: String, vm: KitchenViewModel) {
    ChitraguptaCard(Modifier.fillMaxWidth(), container = Palette.Navy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$number", style = MaterialTheme.typography.displayMedium, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.weight(1f))
            StatusChip("${minutesSince(since)} min", Tone.Info)
        }
        Spacer(Modifier.height(Spacing.s))
        if (!table.isNullOrBlank()) Text("Table $table", style = MaterialTheme.typography.bodyLarge, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(Spacing.m))
        items.forEach { l ->
            Text("${fmtQty(l.qty, l.unit)}  ${l.name}${l.note?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""}",
                style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}
