# Android rewrite plan (native, Kotlin + Compose)

Status: steps 1-5 done in `android/` (native app built, installed and checked on a phone against a multi-shop server). The old Capacitor `apk/` folder was removed. See docs/ANDROID_DESIGN_SPEC.md for the design.

## Goal
Replace the Capacitor/WebView app in `apk/` with a clean native Android app and a professional UI.
Hackathon context: Paytm AI track, so the UI follows a **Paytm-inspired** look and principles
(palette + clarity + trust cues). Do NOT use the Paytm logo or name as our branding; the product is "Chitragupta for Shops".

## Scope decisions (made by the user)
- Full rewrite, all three roles: Counter, Kitchen, Owner.
- Multi-shop mode supported (signup, join with shop code, owner PIN, token auth). Single-shop mode must also work.
- **APK only.** Do not edit the server (`server.js`, `src/`). The user runs the server themselves.
- The user types the server URL in the app on first launch. Do not hardcode URLs.
- Adding a QR code to the web UI for pairing is a later, small task done by the user; the app should be able to scan one
  (payload suggestion: JSON or URL with server URL + shop code) but typing the URL/code must always work.
- Do not start or leave the backend running unless asked.

## Design direction (Paytm-inspired)
- Colour (approximate, verify visually): Paytm-style bright blue ~`#00BAF2` as the accent/primary action colour and deep navy
  ~`#012B72` for headers, primary text and strong surfaces. White surfaces, light grey-blue backgrounds, green for success/paid,
  amber for pending, red for errors. Light and dark themes via Material 3 colour roles.
- Principles: lots of white space, big rounded cards, one clear primary action per screen in the thumb zone (bottom third),
  large tap targets, rupee amounts prominent, status chips (paid / cooking / ready), trust cues around payment (verified-by-server
  confirmation, clear order numbers), simple language, instant feedback and a satisfying success moment after payment.
- Use 8-pt spacing grid, max ~4 type sizes and 2 weights, 60/30/10 colour rule (from the `mobile-app-ui-design` skill; ignore its
  React/Tailwind examples, implement in Compose + Material 3).
- Suggested earlier fallback palette if Paytm colours are unwanted: teal `#0F766E` + saffron `#F59E0B`.

## Onboarding (better than the web app's role picker)
1. Welcome, then "Connect to your shop server": enter server URL (with a reachability check and clear error), or scan a QR if provided.
2. Choose: **Join a shop** (shop code, then role Counter/Kitchen/Owner; owner needs PIN) or **Create a shop** (type cards with a short
   preview: canteen / restaurant / kirana, then name + PIN, then a "shop ready" screen showing the code for pairing other devices).
3. Remember server, token and role; next launches open straight into the role's home. Owner re-entry via PIN (biometric later).
4. Ask microphone permission only when the mic is first used, with a one-line reason. Camera only for QR scanning.
5. Friendly error states: server unreachable, wrong code, wrong PIN, lockout (429), offline.
Detect single vs multi mode via `GET /api/mode` (`{multi, types}`); in single-shop mode skip signup/join and go to role pick.

## Architecture (follow the `claude-android-ninja` skill)
- Kotlin, Jetpack Compose, Material 3, MVVM, Hilt, Navigation3, coroutines/Flow, version catalog, AGP 9.
- Modules: `app`, `core:{designsystem,network,data,model,common,audio}`, `feature:{onboarding,counter,kitchen,owner}`.
- Networking: Retrofit/OkHttp or Ktor against the existing API; SSE client for `/api/events` (live updates; token also accepted as `?t=`).
- Audio: record from the mic natively (WAV, matching what the web app sends: base64 in JSON to `/api/turn`, `/api/ready`, etc.),
  play returned TTS audio (base64) and also handle text-only replies when Sarvam is disabled.
- Edge-to-edge, predictive back, TalkBack labels, dark mode, adaptive layouts (phones first; tablets nice-to-have).
- Tests per `testing-setup` skill (unit for view models, a couple of Compose UI/screenshot tests).
- New app lives in a new folder (suggest `android/`), replacing `apk/` once it works. Keep `apk/` until then.

## Server API the app must call (read `server.js` and `src/tenants.js` for exact shapes and role permissions `can()`)
Gateway/auth: `GET /api/mode`, `GET /api/health`, `POST /api/shops` (create), `POST /api/join`, `Authorization: Bearer <token>`.
App: `/api/config`, `POST /api/turn`, `POST /api/checkout`, `GET /api/order/:id`, `POST /api/order/:id/{mock-pay,bill,cash,cancel}`,
`POST /api/line/unavailable`, `POST /api/ready`, `POST /api/deliver`, `GET /api/kitchen`, `/api/kitchen/config`,
`POST /api/chefs/:id/{online,complete,free,transfer}`, `POST /api/soldout`, `POST /api/shop`, `POST /api/catalog`,
`/api/insights`, `/api/briefing`, `POST /api/copilot`, `/api/summary`, `/api/shop-info`, `POST /api/tts`, `GET /api/events` (SSE).
Profiles differ (canteen = pay before; restaurant/kirana = pay after; kirana = pack/picker flow), so screens adapt to `profile`/`words` from `/api/config`.

## Build order
1. Design spec: screens, tokens (colour/type/spacing), component list, short Compose theme prototype. Get user feedback.
2. Project scaffold (modules, theme, DI, networking, onboarding + auth).
3. Counter flow first (it is the demo): mic, cart, edit, checkout QR, payment status, ready announcements.
4. Kitchen (chef task screen, one task at a time, voice/tap done, transfer), then Owner (summary, copilot, shop setup, catalog, shop code).
5. Polish, tests, install on the user's phone with adb, screenshot each screen to verify.

## Environment notes (already set up on this machine)
- Project-local skills in `.claude/skills/` (38: android/skills, claude-android-ninja, mobile-app-ui-design). Restart Claude Code to load them.
- JDK 21 (Temurin) at `~/.sdkman/candidates/java/21.0.13.0+9-tem`; the shell default stays JDK 17. Use
  `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.13.0+9-tem ./gradlew ...`. (Do not run `sdk install` without declining "set as default".)
- Android SDK at `~/Android/Sdk` with platforms 35, 36, 36.1, 37.0 and build-tools 37.0.0; `android` CLI in `~/.local/bin`.
- Phone connected over adb (Samsung SM_S926B). The old WebView APK is currently installed on it; the new app should use a different
  applicationId or replace it deliberately.
- Router MCP `giga-router` (GitHub, memory, Firecrawl) is registered for this project.
- Backend runs from `npm start` (single shop) or `npm run start:multi`; `.env` was copied from `.env.example` (has a placeholder `LLAMA_URL`; clear it).
