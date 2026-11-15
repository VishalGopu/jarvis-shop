# Chitragupta for Shops

**A voice-first AI business partner for small shops, in the owner's own language.**
Speak the order. Get paid. Never miss a call. Know your business.

Built for the Paytm AI track (*an AI business partner for every merchant*) on **Sarvam AI** Indian-language voice and **Paytm** UPI payments.

[![Release](https://img.shields.io/badge/release-v1-blue)](../../releases/tag/v1) &nbsp; **[Download the Android app (APK)](../../releases/latest)**

---

## The problem is
A small shop is run by one overloaded owner with paper slips, memory and WhatsApp.
Orders are slow and wrong in the rush, payments are not tied to orders, calls go unanswered while hands are full,
nobody tells the owner what is selling, and the software that exists is English-only and built for chains.

## What it does
| | |
|---|---|
| **Voice ordering** | The counter person or customer says the order in Telugu, Hindi or English. A menu-constrained parser turns it into a cart; prices always come from the shop's catalog, never from a model. |
| **Kitchen that talks** | Each cook gets one task at a time on their own phone, spoken in their language. Finishing it assigns the next. "Order N is ready" is announced at the counter. |
| **Payments tied to orders** | UPI QR (Paytm adapter, demo mode included). An order reaches the kitchen only after the payment provider confirms, never on the customer's word. |
| **Pickup codes** | Every order gets a 4-digit code when it is placed. The counter checks it before billing or handing over, so the right person collects the right order. |
| **AI phone assistant** | The owner forwards missed calls to a number answered by a Sarvam voice agent. It speaks the caller's language, reads the menu and prices, books the order for a pickup time, and reads out the pickup code. The kitchen hears about it, and starts cooking 30 minutes before pickup. |
| **Owner copilot** | Daily briefing, "ask anything" about sales and stock, restock advice from sales pace. |
| **Many shops, one backend** | Shops sign up, get a shop code, and pair counter / kitchen / owner phones. Data, stock and live updates are isolated per shop. |

**Who pays when:** counter orders in the tiffin shop are paid when ordering; phone orders are paid at the counter when collected (`payLater`).
Restaurant and kirana shop types are pay-after for every order.

## Architecture
```
 Counter phone        Kitchen phone         Owner phone          Caller (any phone)
  mic · QR · code      spoken tasks         briefing · setup      calls the shop / forwarded number
        \                   |                    /                          |
         \                  |                   /                  Sarvam Voice Agent (hosted)
          ────── Node backend (plain JS, JSON files, SSE) ──────  ←  HTTP tools: menu · check · book
                 |                 |                  |
        Sarvam cloud        Order parser          Paytm / mock
   Saaras STT · Bulbul TTS  Sarvam 30B (llama.cpp) · rules     UPI QR + status
```
Exposed to the internet through a Cloudflare tunnel: one hostname for the app, one for the call endpoints only.

## Get the app
1. Download `chitragupta-v1.apk` from the [latest release](../../releases/latest) and install it (allow "install unknown apps").
2. Open it. The release build connects to the demo server by itself. Ask the team for a demo **shop code** and role
   (Counter and Kitchen need only the code; Owner also needs the PIN).
3. The small **swap icon** in the top-right corner switches between Counter, Kitchen and Owner.

The APK is a debug-signed demo build. To point it at your own server: `./gradlew assembleDebug -PserverUrl=https://your.host`.

## Run your own server
```bash
npm install
cp .env.example .env        # add SARVAM_API_KEY (and LLAMA_URL if you run a local model)
TENANCY=multi npm start     # multi-shop mode, http://localhost:3000   (plain `npm start` = one shop)
```
No key? Typed input, the rules parser and the browser's own voice still work.
Create a shop from the app (or `POST /api/shops` with `{"name","profile","pin"}` and your `SIGNUP_KEY`) to get a shop code.

### Shop types (`profiles/*.json`)
| Profile | Fulfilment | Payment | Flow |
|---|---|---|---|
| `canteen` | kitchen | before | speak → pay → chefs cook → announce |
| `tiffin` | kitchen | before (phone orders pay at pickup) | counter: speak → pay → cook · phone: book → cook → pay at counter |
| `restaurant` | kitchen + table | after the meal | speak → slip to kitchen → served → bill |
| `kirana` | pack (picker) | after packing | speak → slip to picker → UPI or cash at handover |

### Phone orders
The full setup (agent prompt, the three tools, deployment, forwarding codes, costs, gotchas) is in
**[docs/SARVAM_PHONE_AGENT.md](docs/SARVAM_PHONE_AGENT.md)**. In short:
1. Run the server with `VOICE_KEY`, `VOICE_PORT=3001`, `VOICE_SHOPS=<shop code>`, `PHONE_AGENT_NUMBER`, `PUBLIC_URL`.
2. Expose port 3001 (call endpoints only) with a tunnel, and port 3000 for the app.
3. In Sarvam, create an agent with the prompt from the doc and three API tools (`get_menu`, `check_order`, `book_order`), attach the bearer credential, and deploy it to your number.
4. The owner dials `**61*<number>**15#` from the shop SIM. `##61#` turns forwarding off.

A pay-by-SMS-link path (with a simulated Paytm checkout page) is built but parked behind `PHONE_PAY=link`.

### Key settings (`.env.example` has the full list)
| Variable | Purpose |
|---|---|
| `SARVAM_API_KEY` | speech-to-text and text-to-speech |
| `LLAMA_URL`, `LLAMA_KEY` | local order-parsing model (optional, rules parser works without it) |
| `TENANCY=multi`, `SIGNUP_KEY` | many shops; invite key so strangers cannot create shops |
| `VOICE_KEY`, `VOICE_PORT`, `VOICE_SHOPS` | phone endpoints: bearer key, own port, allowed shop codes |
| `PHONE_AGENT_NUMBER`, `PUBLIC_URL`, `PHONE_LEAD_MIN` | forwarding number shown to owners; public app URL; minutes before pickup a phone order starts cooking (30) |
| `PAYMENT_MODE` | `mock` (default) or `paytm` with `PAYTM_MID`, `PAYTM_MERCHANT_KEY`, `PAYTM_HOST` |

### Local order parser (optional)
```bash
huggingface-cli download sarvamai/sarvam-30b-gguf --local-dir sarvam-30b-gguf     # Q4_K_M, ~19.6 GB
llama-server -m sarvam-30b-gguf/sarvam-30b-Q4_K_M.gguf-00001-of-00006.gguf --alias sarvam-30b -ngl 99 -c 8192 --jinja --host <ip> --port 18080 --api-key <secret>
```
Set `LLAMA_URL` / `LLAMA_KEY`. If the host is unreachable, phone tool calls wait for the timeout before falling back to the rules parser; use `PARSER=rules` to skip it.

## Build the Android app
Kotlin, Jetpack Compose, Material 3, Navigation 3. Needs JDK 21 and the Android SDK (platform 37).
```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

## Tests
```bash
npm test   # 10 offline suites: order flow, kitchen scheduler, tenancy, phone tools, pay-at-pickup, pickup codes, scheduling
```

## Layout
| Path | What |
|---|---|
| `server.js`, `src/` | backend, no framework: orders, kitchen scheduler, parser, Sarvam / payment / SMS adapters, phone tools |
| `public/` | web versions of Counter, Kitchen and Owner |
| `android/` | native Android app |
| `profiles/` | shop types (catalog, units, aliases, payment rules) |
| `docs/` | phone agent setup, scaling notes, pitch, Android design |
| `test/`, `scripts/`, `voice-lab/` | tests, load test and fake-telephony tools, speech accuracy script |

## Status
**Works:** voice ordering, kitchen scheduling, pickup codes, multi-shop, phone assistant with scheduled cooking, owner briefing, Android app.
**Demo mode:** payments are simulated (the Paytm adapter is written, but untested without merchant credentials).
**Not done:** real SMS (needs a DLT-registered sender), WhatsApp ordering, spoken reviews, daily summary in Hindi/Telugu, the same dish batched across orders.
