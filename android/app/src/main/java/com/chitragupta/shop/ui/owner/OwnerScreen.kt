package com.chitragupta.shop.ui.owner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.chitragupta.shop.ui.components.copyText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.Chef
import com.chitragupta.shop.data.ChefConfig
import com.chitragupta.shop.data.Config
import com.chitragupta.shop.data.MenuItem
import com.chitragupta.shop.ui.components.BannerMessage
import com.chitragupta.shop.ui.components.ButtonKind
import com.chitragupta.shop.ui.components.EmptyState
import com.chitragupta.shop.ui.components.ChitraguptaButton
import com.chitragupta.shop.ui.components.ChitraguptaCard
import com.chitragupta.shop.ui.components.LoadingBlock
import com.chitragupta.shop.ui.components.MicButton
import com.chitragupta.shop.ui.components.StatusChip
import com.chitragupta.shop.ui.components.Tone
import com.chitragupta.shop.ui.fmtQty
import com.chitragupta.shop.ui.theme.Palette
import com.chitragupta.shop.ui.theme.Spacing

private enum class Tab(val label: String) { Today("Today"), Menu("Menu"), Team("Team"), Shop("Shop") }

@Composable
fun OwnerScreen(graph: AppGraph, cfg: Config, onDisconnect: () -> Unit, onConfigChanged: () -> Unit) {
    val vm: OwnerViewModel = viewModel(factory = viewModelFactory { initializer { OwnerViewModel(graph) } })
    val s by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(cfg.menu) { vm.loadMenu(cfg.menu.items) }
    LaunchedEffect(tab) { when (tab) { Tab.Today -> vm.loadToday(); Tab.Menu -> vm.reloadMenu(); Tab.Team -> vm.loadTeam(); Tab.Shop -> vm.loadShop() } }
    LaunchedEffect(s.savedTick) { if (s.savedTick > 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }

    Column(Modifier.fillMaxSize()) {
        s.error?.let { BannerMessage(it, Tone.Error, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs), "OK", vm::dismissError) }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Today -> TodayTab(vm, s)
                Tab.Menu -> MenuTab(vm, s, onConfigChanged)
                Tab.Team -> TeamTab(vm, s, cfg)
                Tab.Shop -> ShopTab(vm, s, cfg, graph.session.current.serverUrl, onDisconnect, onConfigChanged)
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            Tab.entries.forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label, style = MaterialTheme.typography.bodySmall) },
                    icon = { Icon(when (t) { Tab.Today -> Icons.Filled.Home; Tab.Menu -> Icons.Filled.Inventory2; Tab.Team -> Icons.Filled.Groups; Tab.Shop -> Icons.Filled.Storefront }, null) })
            }
        }
    }
}

// ---------------------------------------------------------------- Today
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayTab(vm: OwnerViewModel, s: OwnerState) {
    val ctx = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.micTapped(true) {} }
    val hasMic = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val f = s.facts
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        item { Text("Today", style = MaterialTheme.typography.titleLarge) }
        if (f == null && s.todayLoading) item { LoadingBlock(lines = 4) }
        if (f != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    StatTile("Sales", "₹${f.today.revenue.toLong()}", compare(f.today.revenue, f.yesterday.revenue), Modifier.weight(1f))
                    StatTile("Orders", "${f.today.orders}", compare(f.today.orders.toDouble(), f.yesterday.orders.toDouble()), Modifier.weight(1f))
                }
            }
            if (f.today.items.isNotEmpty()) item {
                ChitraguptaCard(Modifier.fillMaxWidth()) {
                    Text("Top items today", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.s))
                    f.today.items.take(3).forEach { Text("${it.name}  ·  ${fmtQty(it.qty, "piece")}", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        s.briefing?.let { b ->
            item {
                ChitraguptaCard(Modifier.fillMaxWidth(), container = Palette.Navy) {
                    Text("Chitragupta says", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(Spacing.s))
                    Text(b.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
            if (b.tips.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("Suggestions", style = MaterialTheme.typography.titleMedium)
                    b.tips.forEach { StatusChipText(it) }
                }
            }
        }
        item { Text("Ask Chitragupta", style = MaterialTheme.typography.titleMedium) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf("How much did I sell today?", "What should I prepare tomorrow?", "Which item sells most?").forEach { q ->
                    SuggestionChip(onClick = { vm.ask(q) }, label = { Text(q, style = MaterialTheme.typography.bodySmall) })
                }
            }
        }
        s.copilotAnswer?.let { a ->
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    s.copilotHeard?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ChitraguptaCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.primaryContainer) { Text(a, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(s.copilotQ, vm::setQuestion, Modifier.weight(1f), placeholder = { Text("Ask about your shop") }, singleLine = true, shape = MaterialTheme.shapes.small,
                    trailingIcon = { IconButton({ vm.ask() }, enabled = s.copilotQ.isNotBlank() && !s.copilotBusy) { Icon(Icons.AutoMirrored.Filled.Send, "Ask") } })
                MicButton(s.mic, { vm.micTapped(hasMic()) { askMic.launch(Manifest.permission.RECORD_AUDIO) } }, size = 48.dp)
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }
}

@Composable
private fun StatusChipText(text: String) {
    ChitraguptaCard(Modifier.fillMaxWidth(), padding = Spacing.m) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

private fun compare(now: Double, before: Double): Pair<String, Tone>? {
    if (before <= 0) return null
    val pct = ((now - before) / before * 100).toInt()
    return when {
        pct > 0 -> "+$pct% vs yesterday" to Tone.Success
        pct < 0 -> "$pct% vs yesterday" to Tone.Warning
        else -> "Same as yesterday" to Tone.Neutral
    }
}

@Composable
private fun StatTile(label: String, value: String, delta: Pair<String, Tone>?, modifier: Modifier = Modifier) {
    ChitraguptaCard(modifier) {
        Text(value, style = MaterialTheme.typography.displayMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (delta != null) { Spacer(Modifier.height(Spacing.s)); StatusChip(delta.first, delta.second) }
    }
}

// ---------------------------------------------------------------- Menu
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MenuTab(vm: OwnerViewModel, s: OwnerState, onConfigChanged: () -> Unit) {
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Text("Menu", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ChitraguptaButton("Add item", { creating = true }, Modifier.height(48.dp), icon = Icons.Filled.Add)
        }
        if (s.menu.isEmpty()) EmptyState("No items yet", "Add your first item to start taking orders.", icon = Icons.Filled.Inventory2)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            items(s.menu, key = { it.id }) { m ->
                ChitraguptaCard(Modifier.fillMaxWidth(), onClick = { editing = m }, padding = Spacing.m) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            Text("₹${m.price} / ${m.unit}" + (m.stock?.let { "  ·  ${fmtQty(it, m.unit)} left" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(!m.soldOut, { vm.toggleSoldOut(m, !it) })
                            Text(if (m.soldOut) "Sold out" else "Available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (editing != null || creating) {
        ModalBottomSheet(onDismissRequest = { editing = null; creating = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ItemEditor(editing, s.saving, onSave = { vm.saveItem(it, creating, onConfigChanged); editing = null; creating = false },
                onDelete = editing?.let { e -> { vm.deleteItem(e, onConfigChanged); editing = null } })
        }
    }
}

@Composable
private fun ItemEditor(item: MenuItem?, saving: Boolean, onSave: (MenuItem) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var price by remember { mutableStateOf(item?.price?.toString() ?: "") }
    var unit by remember { mutableStateOf(item?.unit ?: "plate") }
    var step by remember { mutableStateOf(item?.step?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "1") }
    var stock by remember { mutableStateOf(item?.stock?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var aliases by remember { mutableStateOf(item?.aliases?.joinToString(", ") ?: "") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(if (item == null) "New item" else "Edit item", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = MaterialTheme.shapes.small)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(price, { price = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Price ₹") }, singleLine = true, shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(unit, { unit = it }, Modifier.weight(1f), label = { Text("Unit") }, singleLine = true, shape = MaterialTheme.shapes.small)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(step, { step = it }, Modifier.weight(1f), label = { Text("Step") }, singleLine = true, shape = MaterialTheme.shapes.small, keyboardOptions = num)
            OutlinedTextField(stock, { stock = it }, Modifier.weight(1f), label = { Text("Stock (optional)") }, singleLine = true, shape = MaterialTheme.shapes.small, keyboardOptions = num)
        }
        OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth(), label = { Text("Other names customers say") }, supportingText = { Text("Comma separated, e.g. chawal, चावल") }, shape = MaterialTheme.shapes.small)
        Spacer(Modifier.height(Spacing.s))
        ChitraguptaButton("Save", {
            onSave(MenuItem(item?.id ?: "", name.trim(), price.toIntOrNull() ?: 0, unit.trim().ifBlank { "piece" }, step.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }, stock = stock.toDoubleOrNull(), soldOut = item?.soldOut ?: false))
        }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && price.isNotBlank(), loading = saving)
        if (onDelete != null) ChitraguptaButton("Delete item", onDelete, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
    }
}

// ---------------------------------------------------------------- Team
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TeamTab(vm: OwnerViewModel, s: OwnerState, cfg: Config) {
    val words = cfg.profile.words
    val team = s.team
    var editing by remember { mutableStateOf<Chef?>(null) }
    var creating by remember { mutableStateOf(false) }
    if (team == null) { LoadingBlock(lines = 4); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(words.setup, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ChitraguptaButton("Add ${words.staff.lowercase()}", { creating = true }, Modifier.height(48.dp), icon = Icons.Filled.Add)
            }
        }
        if (cfg.profile.fulfil != "pack") item {
            Text("How tasks are split", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilterChip(team.mode == "station", { vm.setMode("station") }, label = { Text("By dish") })
                FilterChip(team.mode == "order", { vm.setMode("order") }, label = { Text("Whole orders") })
            }
        }
        if (team.chefs.isEmpty()) item { EmptyState("No ${words.staffPl.lowercase()} yet", "Add the people who work in the ${words.station.lowercase()}. They pick their name on their phone.", icon = Icons.Filled.Groups) }
        items(team.chefs, key = { it.id }) { c ->
            ChitraguptaCard(Modifier.fillMaxWidth(), onClick = { editing = c }, padding = Spacing.m) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(langName(c.lang) + (c.task?.let { "  ·  on #${it.number}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(c.online, { vm.setOnline(c.id, it) })
                }
            }
        }
    }
    if (editing != null || creating) {
        ModalBottomSheet(onDismissRequest = { editing = null; creating = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ChefEditor(editing, cfg, team.mode == "station" && cfg.profile.fulfil != "pack", s.saving,
                onSave = { vm.saveChef(it); editing = null; creating = false },
                onDelete = editing?.let { e -> { vm.deleteChef(e.id); editing = null } })
        }
    }
}

private fun langName(l: String) = when (l) { "hi-IN" -> "Hindi"; "te-IN" -> "Telugu"; else -> "English" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChefEditor(chef: Chef?, cfg: Config, showSkills: Boolean, saving: Boolean, onSave: (ChefConfig) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(chef?.name ?: "") }
    var lang by remember { mutableStateOf(chef?.lang ?: "en-IN") }
    var skills by remember { mutableStateOf(chef?.skills?.toSet() ?: emptySet()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(if (chef == null) "Add ${cfg.profile.words.staff.lowercase()}" else "Edit ${chef.name}", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = MaterialTheme.shapes.small)
        Text("Speaks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf("en-IN", "hi-IN", "te-IN").forEach { l -> FilterChip(lang == l, { lang = l }, label = { Text(langName(l)) }) }
        }
        if (showSkills) {
            Text("Can make", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                cfg.menu.items.forEach { m -> FilterChip(m.id in skills, { skills = if (m.id in skills) skills - m.id else skills + m.id }, label = { Text(m.name) }) }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        ChitraguptaButton("Save", { onSave(ChefConfig(chef?.id, name.trim(), lang, if (showSkills) skills.toList() else chef?.skills.orEmpty(), chef?.also.orEmpty(), chef?.helpAny ?: true)) },
            Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = saving)
        if (onDelete != null) ChitraguptaButton("Remove", onDelete, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
    }
}

// ---------------------------------------------------------------- Shop
@Composable
private fun ShopTab(vm: OwnerViewModel, s: OwnerState, cfg: Config, serverUrl: String, onDisconnect: () -> Unit, onConfigChanged: () -> Unit) {
        val ctx = LocalContext.current
    var confirmType by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val info = s.shop
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text(info?.name ?: cfg.menu.shop, style = MaterialTheme.typography.titleLarge)
        if (info?.code != null) {
            ChitraguptaCard(Modifier.fillMaxWidth(), container = Palette.Navy) {
                Text("SHOP CODE", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                Text(info.code, style = MaterialTheme.typography.displayMedium, color = Color.White)
                Spacer(Modifier.height(Spacing.s))
                Text("Enter this on the counter and kitchen phones.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                ChitraguptaButton("Copy", { copyText(ctx, info.code) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Filled.ContentCopy)
                ChitraguptaButton("Share", {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join ${info.name} on Chitragupta for Shops. Shop code: ${info.code}") }, "Share shop code"))
                }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Filled.Share)
            }
        } else if (info != null) {
            BannerMessage("This server runs a single shop, so there's no shop code.", Tone.Info)
        }
        info?.phone?.let { ph ->
            ChitraguptaCard(Modifier.fillMaxWidth()) {
                Text("📞 Phone orders", style = MaterialTheme.typography.titleMedium)
                Text("Callers who reach this number are answered by an AI assistant that takes their order for pickup. Orders show on the counter and kitchen marked 📞, pay at pickup.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.s))
                Text("Assistant number", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ph.number, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Spacing.s))
                Text("Send your unanswered calls to it by dialing a code from the shop SIM:", style = MaterialTheme.typography.bodyMedium)
                listOf("Turn on (no answer)" to ph.on, "Also when busy" to ph.busy, "Turn off forwarding" to ph.off).forEach { (label, code) ->
                    Spacer(Modifier.height(Spacing.xs))
                    ChitraguptaButton("$label  $code", {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(code))))
                    }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                }
                Text("Forwarding charges follow your SIM plan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Shop type", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            cfg.profile.shops.forEach { t ->
                FilterChip(cfg.profile.id == t.id, { if (cfg.profile.id != t.id) confirmType = t.id }, label = { Text(t.label) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("Server: $serverUrl", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChitraguptaButton("Disconnect this phone", { confirmLeave = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
    confirmType?.let { id ->
        AlertDialog(onDismissRequest = { confirmType = null }, title = { Text("Switch shop type?") },
            text = { Text("This changes the menu and how orders and payments work. It can't be done while orders are in progress.") },
            confirmButton = { TextButton({ vm.switchType(id, onConfigChanged); confirmType = null }) { Text("Switch") } },
            dismissButton = { TextButton({ confirmType = null }) { Text("Cancel") } })
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Disconnect?") },
        text = { Text("You'll need the shop code (and owner PIN) to come back.") },
        confirmButton = { TextButton({ confirmLeave = false; onDisconnect() }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Cancel") } })
}
