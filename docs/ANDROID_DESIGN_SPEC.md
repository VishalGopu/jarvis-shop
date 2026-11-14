# Chitragupta for Shops: Android design spec (step 1)

Status: DRAFT for user feedback. No project code yet. Palette values are approximate and must be checked visually on the phone.
Principles come from the `mobile-app-ui-design` skill (8-pt grid, 60/30/10, thumb zone, 4 sizes / 2 weights, peak-end), implemented in Compose + Material 3.

## 1. Design tokens

### Colour (light)
| Role | Hex | Use |
|---|---|---|
| `primary` (Sky) | `#00BAF2` | Primary buttons, active states, mic button, links |
| `onPrimary` | `#012B72` | Text/icons ON sky. White on `#00BAF2` is only about 2.3:1, so we use navy (about 6:1) |
| `secondary` (Navy) | `#012B72` | Top bars, headings, strong surfaces (balance/total card), selected chips |
| `onSecondary` | `#FFFFFF` | |
| `background` | `#F5F9FC` | 60% neutral base |
| `surface` | `#FFFFFF` | Cards |
| `surfaceVariant` | `#E8F1F8` | Input fields, inactive chips |
| `onSurface` | `#0B1B33` | Body text (navy-tinted black, never pure black) |
| `onSurfaceVariant` | `#5B6B80` | Secondary text (about 60-70% emphasis) |
| `outline` | `#D5E1EC` | Hairlines, 1dp card borders |
| `success` | `#21C17C` (container `#E3F8EE`) | Paid, ready, delivered |
| `warning` | `#F5A623` (container `#FFF3DC`) | Pending payment, cooking |
| `error` | `#E5484D` (container `#FDE8E9`) | Failed, wrong PIN, offline |

Rule: sky is the ~10% accent (one primary action per screen), navy + onSurface are the ~30%, white/grey-blue is the ~60%.

### Colour (dark)
`background #07111F`, `surface #0E1D33`, `surfaceVariant #16294A`, `onSurface #E6EEF7`, `onSurfaceVariant #93A4BA`,
`primary #00BAF2` (onPrimary `#012B72`), `secondary #7FB2FF` (on `#012B72`), status colours the same hue, containers at 20% alpha.

### Typography
One family: **Inter** (bundled via downloadable fonts / `res/font`), tabular figures for amounts. Four sizes, two weights (Regular 400, SemiBold 600).
| Token | Size/line | Weight | Use |
|---|---|---|---|
| `display` | 32/40 | SemiBold | Totals, order numbers, "Rs 240" |
| `title` | 20/28 | SemiBold | Screen titles, card headings |
| `body` | 16/24 | Regular | Everything else |
| `caption` | 12/16 | Regular | Chips, hints, timestamps (secondary colour) |
Amounts always bigger than their labels ("Rs 240" is `display`, "Total" is `caption`).

### Spacing, shape, elevation
- Grid: 4, 8, 12, 16, 24, 32, 48. Screen gutter 16 (24 on onboarding). Gap inside a group 8, between groups 24 (about 2-3x rule).
- Shape: cards 24dp radius, buttons and inputs 16dp, chips fully rounded, bottom sheets 28dp top.
- Elevation: soft, navy-tinted shadow (`#012B72` at 8%, blur 16, y 4). No harsh grey shadows. Cards have a 1dp `outline` border in light mode.
- Tap targets min 48dp; primary buttons 56dp high, full width, in the bottom third.
- Motion: 200-300ms ease-out for state changes; one celebratory moment (see Payment success). Respect "remove animations".

## 2. Screens

Legend: **[P]** = the one primary action, placed in the thumb zone.

### Onboarding (all roles)
1. **Welcome**: logo mark (our own, a simple waveform-in-a-shop-bag glyph), "Chitragupta for Shops", one line ("Speak the order. We do the rest."). **[P] Get started**. Small link "Scan shop QR".
2. **Connect to server**: URL field (prefilled with last used, accepts `host:port`, assumes `http://`), inline reachability check against `GET /api/mode` with a spinner then a green "Connected" chip (or a red message: "Can't reach the server. Is your phone on the same Wi-Fi?"). **[P] Continue**. Secondary: "Scan QR instead" (camera permission asked here, with a one-line reason).
3. **Join or create** (multi mode only; single mode skips to Role): two big cards. "Join a shop" (code) and "Create a shop".
4. **Join**: shop code field (6 chars, auto-uppercase, paste friendly) then **Role** cards (Counter / Kitchen / Owner, each with a one-line description and an icon). Owner reveals a PIN pad (4-6 digits, big keys, dots). Errors: wrong code, wrong PIN, 429 lockout with a countdown.
5. **Create**: step indicator 1-3. (a) Shop type cards (canteen / restaurant / kirana) from `GET /api/mode.types`, each with a mini preview of how the counter looks for that type; (b) shop name + owner PIN (entered twice); (c) **Shop ready**: confetti-light success, the shop code in `display` size with Copy and Share buttons, and a note "Use this code on the counter and kitchen phones". **[P] Open my shop**.
6. **Single-shop mode**: Connect then Role only.

Session: server URL, token and role stored (EncryptedSharedPreferences/DataStore). Next launch goes straight to the role home. Owner re-entry asks for PIN (biometric later). Settings has "Switch role" and "Disconnect".

### Counter (the demo screen)
Layout (portrait, top to bottom): top bar (shop name, connection dot, overflow) / live **cart card** (scrolling lines with qty steppers and notes, per-line "unavailable" swipe action) / total row in a navy card ("Rs 240", item count) / bottom dock: big **mic button** (72dp, sky, pulsing ring while listening, centred) flanked by a keyboard-input icon and **[P] Checkout** pill. After a turn, the assistant reply appears as a chat-style bubble above the dock with a replay-speaker icon.
- Idle empty state: "Tap the mic and say the order" with 3 example chips from the profile's `examples`, tappable.
- Listening: waveform bar, "Listening in English / Hindi / Telugu" (detected language chip after the turn), tap to stop.
- Checkout: bottom sheet with order number, **UPI QR** (large, white card on navy), amount, and a live status chip (Waiting for payment, then Paid). Buttons: Cash received, Cancel. Profile-aware: canteen = pay first; restaurant/kirana = bill then pay after.
- **Payment success (peak moment)**: sheet transitions to a green check with a short scale-in + ring animation, "Rs 240 received", "Verified by server" trust line, order number large, then "Sent to kitchen". Haptic tick. Auto-returns to an empty cart after 3s or on tap.
- Ready announcements: banner "Order 42 ready" (also spoken via TTS audio from the server), with a Delivered action (pickup code if the server requires it).

### Kitchen (chef / picker)
One task at a time. Top: staff name chip + online toggle. Centre: **task card** (order number `display`, items list with qty and notes, elapsed timer, type-aware words: Cooking/Packing). Bottom **[P] Done** (full width, success green) with an alternative mic "say done". Under it: queue strip ("Next: #43, #44") and a "Transfer" text button. Empty: "No tasks. You're all caught up." Live via SSE.

### Owner
Bottom navigation, 4 tabs:
- **Today**: summary cards (sales, orders, avg time) as `display` numbers, a simple sparkline, AI briefing card, "Ask Chitragupta" copilot input (text + mic) with suggestion chips from `/api/insights`.
- **Menu**: catalog list with sold-out switches, add/edit item sheet.
- **Team**: chefs/pickers list (online, assigned), staffing config.
- **Shop**: name, type, **shop code card with Copy/Share (and QR later)**, usage, disconnect.

### Shared states
Loading (skeleton shimmer, never a blank screen), empty (illustration glyph + guidance + CTA), error (inline banner with Retry), offline (persistent amber banner, actions disabled with explanation, SSE auto-reconnect), permission denied (mic: explains and opens settings).

## 3. Component list (`core:designsystem`)
`ChitraguptaTheme`, `ChitraguptaButton` (Primary sky / Secondary outlined / Tertiary text / Success green), `MicButton` (idle, listening ring, processing), `AmountText`, `StatusChip` (paid / pending / cooking / ready / error), `ChitraguptaCard`, `CartLineItem` + `QuantityStepper`, `TotalBar`, `UpiQrCard`, `PinPad` + `PinDots`, `CodeField` (shop code), `ServerUrlField` (with check state), `RoleCard`, `ShopTypeCard`, `StepIndicator`, `TaskCard`, `StatTile`, `SuggestionChip`, `BannerMessage` (info / warning / error), `EmptyState`, `SkeletonBlock`, `SuccessCheck` (animated), `ConnectionDot`, `ChitraguptaTopBar`.

## 4. Accessibility and adaptivity
Dynamic type up to 200% without clipping, TalkBack labels on every icon button (mic announces state), status never conveyed by colour alone (icon + text), contrast >= 4.5:1 for text, edge-to-edge with correct insets, predictive back, dark theme. Phones first; layouts use window size classes so Counter becomes two-pane (cart | payment) on tablets later.

## 5. Compose theme prototype (to go into `core:designsystem`)

```kotlin
package com.chitraguptashop.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

object ChitraguptaColors {
    val Sky = Color(0xFF00BAF2)
    val Navy = Color(0xFF012B72)
    val Success = Color(0xFF21C17C);  val SuccessContainer = Color(0xFFE3F8EE)
    val Warning = Color(0xFFF5A623);  val WarningContainer = Color(0xFFFFF3DC)
    val Danger = Color(0xFFE5484D);   val DangerContainer = Color(0xFFFDE8E9)
}

/** Status colours Material 3 doesn't have as roles. */
@Immutable
data class StatusColors(
    val success: Color, val onSuccessContainer: Color, val successContainer: Color,
    val warning: Color, val warningContainer: Color,
)
val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(ChitraguptaColors.Success, Color(0xFF0B6B45), ChitraguptaColors.SuccessContainer,
        ChitraguptaColors.Warning, ChitraguptaColors.WarningContainer)
}

private val Light = lightColorScheme(
    primary = ChitraguptaColors.Sky, onPrimary = ChitraguptaColors.Navy,
    secondary = ChitraguptaColors.Navy, onSecondary = Color.White,
    background = Color(0xFFF5F9FC), onBackground = Color(0xFF0B1B33),
    surface = Color.White, onSurface = Color(0xFF0B1B33),
    surfaceVariant = Color(0xFFE8F1F8), onSurfaceVariant = Color(0xFF5B6B80),
    outline = Color(0xFFD5E1EC),
    error = ChitraguptaColors.Danger, errorContainer = ChitraguptaColors.DangerContainer,
)
private val Dark = darkColorScheme(
    primary = ChitraguptaColors.Sky, onPrimary = ChitraguptaColors.Navy,
    secondary = Color(0xFF7FB2FF), onSecondary = ChitraguptaColors.Navy,
    background = Color(0xFF07111F), onBackground = Color(0xFFE6EEF7),
    surface = Color(0xFF0E1D33), onSurface = Color(0xFFE6EEF7),
    surfaceVariant = Color(0xFF16294A), onSurfaceVariant = Color(0xFF93A4BA),
    outline = Color(0xFF23395E),
    error = Color(0xFFFF6B70),
)

// 4 sizes, 2 weights. FontFamily is Inter once the font files are added (FontFamily.SansSerif until then).
private val Family = FontFamily.SansSerif
private val Regular = FontWeight.Normal
private val Semi = FontWeight.SemiBold
val ChitraguptaTypography = Typography(
    displayMedium = TextStyle(fontFamily = Family, fontWeight = Semi, fontSize = 32.sp, lineHeight = 40.sp,
        fontFeatureSettings = "tnum"),
    titleLarge = TextStyle(fontFamily = Family, fontWeight = Semi, fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = Family, fontWeight = Regular, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = Family, fontWeight = Semi, fontSize = 16.sp, lineHeight = 24.sp), // buttons
    bodySmall = TextStyle(fontFamily = Family, fontWeight = Regular, fontSize = 12.sp, lineHeight = 16.sp),
)

object Spacing { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val huge = 48.dp }

val ChitraguptaShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),   // buttons, inputs
    medium = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),  // cards
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),   // sheets
)

@Composable
fun ChitraguptaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // No dynamic colour on purpose: the brand palette must stay consistent for the demo.
    CompositionLocalProvider(LocalStatusColors provides LocalStatusColors.current) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = ChitraguptaTypography,
            shapes = ChitraguptaShapes, content = content)
    }
}
```

## 6. Open questions for you
1. **Palette**: sky `#00BAF2` + navy `#012B72` as above (Paytm-inspired), or the teal/saffron fallback? Navy-on-sky button text is my accessibility choice; Paytm itself uses white-on-blue in places.
2. **Logo**: OK to draw our own simple mark (waveform in a bag)? Or do you have one?
3. **Font**: bundle Inter (adds about 300 KB) or stay on the system font?
4. **Owner tabs**: Today / Menu / Team / Shop. Good, or merge Team into Shop?
5. **Payment success**: OK with a short celebratory animation + haptic on the counter?
6. **applicationId**: new id (e.g. `in.chitragupta.shop`) installed beside the old WebView app, or replace the old one?

Next after your feedback: step 2, the project scaffold in `android/`.
