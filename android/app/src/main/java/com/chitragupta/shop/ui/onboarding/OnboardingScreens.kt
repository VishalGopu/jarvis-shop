package com.chitragupta.shop.ui.onboarding

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.chitragupta.shop.ui.components.copyText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chitragupta.shop.data.Role
import com.chitragupta.shop.ui.components.BannerMessage
import com.chitragupta.shop.ui.components.ButtonKind
import com.chitragupta.shop.ui.components.ChitraguptaButton
import com.chitragupta.shop.ui.components.ChitraguptaCard
import com.chitragupta.shop.ui.components.LogoMark
import com.chitragupta.shop.ui.components.PinDots
import com.chitragupta.shop.ui.components.PinPad
import com.chitragupta.shop.ui.components.StatusChip
import com.chitragupta.shop.ui.components.StepIndicator
import com.chitragupta.shop.ui.components.Tone
import com.chitragupta.shop.ui.theme.Palette
import com.chitragupta.shop.ui.theme.Spacing
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.ui.platform.LocalContext as Ctx
import android.content.Intent

/** Common page frame: back arrow, optional step indicator, scrolling body, button docked at the bottom (thumb zone). */
@Composable
private fun OnboardingPage(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, step: Pair<Int, Int>? = null,
    bottom: @Composable () -> Unit, body: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } else Spacer(Modifier.size(48.dp))
            if (step != null) StepIndicator(step.first, step.second, Modifier.weight(1f).padding(end = 56.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.l))
            body()
            Spacer(Modifier.height(Spacing.l))
        }
        Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) { bottom() }
    }
}

@Composable
fun WelcomeScreen(onStart: () -> Unit, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Navy).windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.xl)) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            LogoMark(Modifier.size(112.dp), bag = Color.White, wave = Palette.Sky)
            Spacer(Modifier.height(Spacing.xl))
            Text("Chitragupta for Shops", style = MaterialTheme.typography.displayMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.s))
            Text("Speak the order. We do the rest.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        }
        ChitraguptaButton("Get started", onStart, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.s))
        ChitraguptaButton("Scan shop QR", onScan, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary, icon = Icons.Filled.QrCodeScanner)
    }
}
private typealias Color = androidx.compose.ui.graphics.Color

@Composable
fun ConnectScreen(vm: OnboardingViewModel, onBack: () -> Unit, onScan: () -> Unit, onConnected: () -> Unit) {
    val s by vm.state.collectAsState()
    OnboardingPage("Connect to your shop server", "Enter the address shown where your Chitragupta server runs.", onBack, 1 to 4,
        bottom = {
            ChitraguptaButton("Continue", { vm.checkServer(onConnected) }, Modifier.fillMaxWidth(),
                enabled = s.serverInput.isNotBlank(), loading = s.check is ServerCheck.Checking)
            ChitraguptaButton("Scan QR instead", onScan, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary, icon = Icons.Filled.QrCodeScanner)
        }) {
        OutlinedTextField(
            s.serverInput, vm::setServer, Modifier.fillMaxWidth(), label = { Text("Server address") },
            placeholder = { Text("192.168.1.5:3000") }, singleLine = true, shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(Spacing.s))
        when (val c = s.check) {
            is ServerCheck.Ok -> StatusChip("Connected", Tone.Success, icon = Icons.Filled.Check)
            is ServerCheck.Failed -> BannerMessage(c.message, Tone.Error)
            else -> Text("Your phone and the server must be on the same Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RolePickScreen(vm: OnboardingViewModel, onBack: () -> Unit) {
    OnboardingPage("What will this phone do?", "You can switch any time from inside the app.", onBack, 2 to 2, bottom = {}) {
        RoleCards { vm.enterSingle(it) }
    }
}

@Composable
private fun RoleCards(selected: Role? = null, onPick: (Role) -> Unit) {
    val items = listOf(
        Triple(Role.Counter, Icons.Filled.PointOfSale, "Take orders by voice and collect payment"),
        Triple(Role.Kitchen, Icons.Filled.Kitchen, "See tasks and mark them done"),
        Triple(Role.Owner, Icons.Filled.Insights, "Sales, menu, team and shop settings"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        items.forEach { (role, icon, desc) -> ChoiceCard(role.label, desc, icon, role == selected) { onPick(role) } }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    ChitraguptaCard(Modifier.fillMaxWidth(), onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.Navy), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White)
            }
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun JoinOrCreateScreen(onBack: () -> Unit, onJoin: () -> Unit, onCreate: () -> Unit) {
    OnboardingPage("Set up this phone", "Join a shop that already exists, or create a new one.", onBack, 2 to 4, bottom = {}) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ChoiceCard("Join a shop", "Enter the shop code from the owner", Icons.Filled.Groups, false, onJoin)
            ChoiceCard("Create a shop", "Pick a type, name it, set an owner PIN", Icons.Filled.AddBusiness, false, onCreate)
        }
    }
}

@Composable
fun JoinScreen(vm: OnboardingViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val needsPin = s.role == Role.Owner
    OnboardingPage("Join your shop", "Ask the owner for the shop code (Owner > Shop).", onBack, 3 to 4,
        bottom = {
            if (s.error != null) BannerMessage(s.error!!, Tone.Error)
            ChitraguptaButton("Join", vm::join, Modifier.fillMaxWidth(), loading = s.busy,
                enabled = s.codeValid && s.role != null && (!needsPin || s.pin.length >= 4))
        }) {
        OutlinedTextField(
            s.code, vm::setCode, Modifier.fillMaxWidth(), label = { Text("Shop code") }, singleLine = true, shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
        )
        Spacer(Modifier.height(Spacing.m))
        Text("This phone is the", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RoleCards(s.role) { vm.setRole(it) }
        if (needsPin) {
            Spacer(Modifier.height(Spacing.l))
            Text("Owner PIN", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                PinDots(6, s.pin.length)
                PinPad(s.pin, vm::setPin)
            }
        }
    }
}

@Composable
fun CreateTypeScreen(vm: OnboardingViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val s by vm.state.collectAsState()
    val types = s.mode?.types.orEmpty()
    OnboardingPage("What kind of shop is it?", "This sets how ordering, payment and the kitchen work.", onBack, 3 to 4,
        bottom = { ChitraguptaButton("Next", onNext, Modifier.fillMaxWidth(), enabled = s.shopType != null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            types.forEach { t ->
                val (icon, blurb) = when (t.id) {
                    "canteen" -> Icons.Filled.Storefront to "Customers pay first, then the kitchen cooks"
                    "restaurant" -> Icons.Filled.Kitchen to "Orders go to the kitchen, pay after"
                    "kirana" -> Icons.Filled.PointOfSale to "Pickers pack the order, pay when it's ready"
                    else -> Icons.Filled.Storefront to ""
                }
                ChoiceCard(t.label, blurb, icon, s.shopType == t.id) { vm.setType(t.id) }
            }
        }
    }
}

@Composable
fun CreateDetailsScreen(vm: OnboardingViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    OnboardingPage("Name it and set a PIN", "The owner PIN protects sales and settings.", onBack, 4 to 4,
        bottom = {
            if (s.error != null) BannerMessage(s.error!!, Tone.Error)
            ChitraguptaButton("Create shop", vm::create, Modifier.fillMaxWidth(), loading = s.busy, enabled = s.shopName.trim().length >= 2 && s.pinMatches)
        }) {
        OutlinedTextField(s.shopName, vm::setName, Modifier.fillMaxWidth(), label = { Text("Shop name") }, singleLine = true, shape = MaterialTheme.shapes.small)
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(s.newPin, vm::setNewPin, Modifier.fillMaxWidth(), label = { Text("Owner PIN (4-6 digits)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(s.newPinAgain, vm::setNewPinAgain, Modifier.fillMaxWidth(), label = { Text("Repeat PIN") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = MaterialTheme.shapes.small,
            isError = s.newPinAgain.isNotEmpty() && !s.pinMatches,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    }
}

@Composable
fun ShopReadyScreen(vm: OnboardingViewModel) {
    val s by vm.state.collectAsState()
        val ctx = LocalContext.current
    val code = s.createdCode.orEmpty()
    val haptic = LocalHapticFeedback.current
    androidx.compose.runtime.LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    OnboardingPage("${s.createdName ?: "Your shop"} is ready", "Use this code on the counter and kitchen phones.", null, null,
        bottom = { ChitraguptaButton("Open my shop", vm::finishCreate, Modifier.fillMaxWidth()) }) {
        ChitraguptaCard(Modifier.fillMaxWidth(), container = Palette.Navy) {
            Text("SHOP CODE", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(Spacing.s))
            Text(code, style = MaterialTheme.typography.displayMedium, color = Color.White)
        }
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ChitraguptaButton("Copy", { copyText(ctx, code) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Filled.ContentCopy)
            ChitraguptaButton("Share", {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join ${s.createdName} on Chitragupta for Shops. Shop code: $code")
                }, "Share shop code"))
            }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = Icons.Filled.Share)
        }
    }
}

/** Opens Google's code scanner (no camera permission needed) and returns the raw text. */
fun scanQr(context: android.content.Context, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { it.rawValue?.let(onResult) }
        .addOnFailureListener { onError("Couldn't open the scanner. Type the address instead.") }
}
