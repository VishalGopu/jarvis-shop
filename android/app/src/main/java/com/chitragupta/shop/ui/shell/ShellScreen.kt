package com.chitragupta.shop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chitragupta.shop.AppGraph
import com.chitragupta.shop.data.Role
import com.chitragupta.shop.ui.components.BannerMessage
import com.chitragupta.shop.ui.components.EmptyState
import com.chitragupta.shop.ui.components.ChitraguptaButton
import com.chitragupta.shop.ui.components.LoadingBlock
import com.chitragupta.shop.ui.components.PinDots
import com.chitragupta.shop.ui.components.PinPad
import com.chitragupta.shop.ui.components.Tone
import com.chitragupta.shop.ui.counter.CounterScreen
import com.chitragupta.shop.ui.kitchen.KitchenScreen
import com.chitragupta.shop.ui.owner.OwnerScreen
import com.chitragupta.shop.ui.theme.Spacing

fun Role.icon() = when (this) { Role.Counter -> Icons.Filled.PointOfSale; Role.Kitchen -> Icons.Filled.Kitchen; Role.Owner -> Icons.Filled.Insights }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(graph: AppGraph) {
    val vm: ShellViewModel = viewModel(factory = viewModelFactory { initializer { ShellViewModel(graph) } })
    val st by vm.state.collectAsState()
    val session by graph.session.state.collectAsState()
    val unlocked by graph.session.ownerUnlocked.collectAsState()
    val role = session.role ?: Role.Counter
    val ownerLocked = role == Role.Owner && session.multi && !unlocked

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopCorner(session.shopName, role, session.multi, st.switching) { vm.switchTo(it) }
        st.message?.let { BannerMessage(it, Tone.Error, Modifier.padding(horizontal = Spacing.l), "OK", vm::dismissMessage) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val cfg = st.config
            when {
                ownerLocked -> OwnerLock(st.pinError, st.switching) { vm.submitOwnerPin(it) }
                cfg == null && st.configError != null ->
                    EmptyState("Can't reach the shop", st.configError ?: "", icon = Icons.Filled.SwapHoriz) {
                        ChitraguptaButton("Try again", vm::loadConfig)
                        ChitraguptaButton("Change shop", vm::signOut, kind = com.chitragupta.shop.ui.components.ButtonKind.Tertiary)
                    }
                cfg == null -> LoadingBlock(lines = 5)
                else -> when (role) {
                    Role.Counter -> CounterScreen(graph, cfg)
                    Role.Kitchen -> KitchenScreen(graph, cfg)
                    Role.Owner -> OwnerScreen(graph, cfg, onDisconnect = vm::signOut, onConfigChanged = vm::loadConfig)
                }
            }
        }
    }
    if (st.askOwnerPin) {
        ModalBottomSheet(onDismissRequest = vm::dismissPin) { OwnerPinSheet(st.pinError, st.switching) { vm.submitOwnerPin(it) } }
    }
}

/** The "hat" switcher: a small, quiet corner control so a counter person can step into the kitchen without it getting in the way. */
@Composable
private fun TopCorner(shop: String, current: Role, multi: Boolean, busy: Boolean, onSwitch: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = Spacing.l, end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(shop.ifBlank { "Chitragupta for Shops" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(current.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton({ open = true }, Modifier.size(40.dp).alpha(if (busy) 0.4f else 0.65f).semantics { contentDescription = "Switch to Counter, Kitchen or Owner" }) {
                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(open, { open = false }) {
                Role.entries.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r.label + if (r == current) "  ✓" else "") },
                        leadingIcon = { Icon(r.icon(), null) },
                        onClick = { open = false; onSwitch(r) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnerLock(error: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        OwnerPinSheet(error, busy, onSubmit)
    }
}

@Composable
private fun OwnerPinSheet(error: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text("Owner PIN", style = MaterialTheme.typography.titleLarge)
        Text("Sales, menu and settings are protected.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PinDots(6, pin.length)
        if (error != null) BannerMessage(error, Tone.Error)
        PinPad(pin, { pin = it })
        ChitraguptaButton("Unlock", { onSubmit(pin); pin = "" }, Modifier.fillMaxWidth(), enabled = pin.length >= 4, loading = busy)
        Spacer(Modifier.height(Spacing.l))
    }
}
