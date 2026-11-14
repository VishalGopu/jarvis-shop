# Chitragupta for Shops

A voice-first, multilingual counter assistant for college canteens and small food stalls
(built for the Paytm AI track: *an AI business partner for every merchant*).

**Speak → confirm → pay by UPI → kitchen cooks → counter calls the number.** No cashier needed.

```
 Counter phone (APK)        Chef phones (APK)         Owner phone (APK)
   mic · QR · speaker        one task at a time         summary · kitchen setup
          \                        |                        /
           ──────────  Node backend (laptop / edge box)  ──────────
                       orders · scheduler · SSE live events · JSON persistence
                         |                         |                  |
                 Sarvam cloud                Parser (local)       Paytm / mock
              Saaras STT · Bulbul TTS   Sarvam 30B · Qwen · rules   Dynamic QR + status
```

## One engine, many shops
`profiles/*.json` define a shop type: its catalog (units, step, stock, voice aliases), how work is fulfilled, when the customer pays, and the vocabulary.
Switch type and edit prices/stock from **Owner → My shop** (`SHOP=kirana npm start` also works).
| Profile | Fulfilment | Payment | Flow |
|---|---|---|---|
| `canteen` | kitchen (chefs, by item or order) | before | speak → pay → chefs cook → announce |
| `restaurant` | kitchen + table number | after the meal | speak → slip to kitchen → served → bill |
| `kirana` | pack (a picker gets the paper slip) | after packing | speak → slip to picker (ticks items, marks "not available", bill shrinks) → UPI or cash at handover |
The copilot adapts too: food shops get "prepare tomorrow", shops with stock counts get "restock" advice from sales pace.

## Many shops, one backend
`npm run start:multi` runs **multi-shop mode**: shops sign up in the app, get a shop code, and pair counter / kitchen / owner phones with it (owner needs a PIN).
Every shop's data, stock, kitchen and live updates are isolated; speech and model usage is metered with daily quotas per shop; `/admin.html` shows all shops.
Measured: 200 shops on one process, ~2,800 requests/s, ~1 MB per active shop, zero cross-shop leaks (`npm run loadtest`). Details and the cost model: [docs/SCALING.md](docs/SCALING.md).

## What it does
- **Voice ordering in Indian languages.** One Sarvam Saaras v4 call (`translate` mode) gives English text + the detected language;
  a menu-constrained parser turns it into a cart; replies are spoken back in the customer's language (English / Hindi / Telugu templates).
  Prices always come from the shop profile's catalog, never from a model. Quantities carry units ("half kilo rice", "2 packets maggi") and quality notes ("ripe", "Tata").
- **Edit before paying:** +/−/✕ on the cart, voice "remove the chai", and an *Edit order* button after the QR is shown.
- **Payment verified server-side.** Orders reach the kitchen only after the provider confirms (mock adapter now, Paytm Dynamic QR adapter included but untested without credentials).
- **Kitchen scheduler.** Owner sets how many chefs, their language, and the dishes each owns.
  *By item*: oldest orders first, a chef gets the dishes they own and helps with others when free. *By order*: one chef cooks a whole order.
  Each chef holds one task at a time, spoken only on their phone; finishing it assigns the next.
- **Counter speaker** announces "order N is ready" in the customer's language once every item is done; board for managers, sold-out toggles, voice "order 104 ready" / "dosa ready".
- **Owner summary** of the day (orders, revenue, top item, peak hour). Orders persist to `data/state.json`.

## Layout
| Path | What |
|---|---|
| `server.js`, `src/` | backend (no framework): orders, kitchen scheduler, parser providers, Sarvam + payment adapters |
| `public/` | the web app (role picker, counter, kitchen, owner) — also bundled into the APK |
| `android/` | Native Android app (Kotlin, Jetpack Compose, Material 3) |
| `voice-lab/` | script that round-trips phrases through Sarvam TTS → STT to check language accuracy and latency |
| `test/` | unit tests (`npm test`) |

## Run
```bash
npm install
cp .env.example .env        # add SARVAM_API_KEY (and LLAMA_URL if you run a local model)
npm start                   # http://localhost:3000
```
No key? Typed input + the rules parser + the browser's own voice still work. Change the code → restart the server.

### Local parser (Sarvam 30B, open weights, Apache-2.0)
```bash
huggingface-cli download sarvamai/sarvam-30b-gguf --local-dir sarvam-30b-gguf     # Q4_K_M, ~19.6 GB, no login needed
llama-server -m sarvam-30b-gguf/sarvam-30b-Q4_K_M.gguf-00001-of-00006.gguf --alias sarvam-30b -ngl 99 -c 8192 --jinja \
  --host <ip> --port 18080 --api-key <secret>
```
Set `LLAMA_URL` / `LLAMA_KEY`. The server uses the raw `/completion` endpoint with an empty think block (the chat endpoint always "thinks").
On 8 typed Hindi/Telugu/English/Hinglish orders both Sarvam 30B and Qwen 3.8 27B scored 8/8; Sarvam answered in ~0.1–0.2 s vs ~0.5 s.
Real noisy speech has not been benchmarked yet.

### Android app (native, `android/`)
Kotlin + Jetpack Compose + Material 3. Needs JDK 21 and the Android SDK (platform 37).
```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```
On first launch type the server address (or scan a QR holding the address, as `http://host:3000` or JSON `{"url":"...","code":"..."}`).
Multi-shop servers: join with a shop code or create a shop; single-shop servers just ask for a role.
Counter, Kitchen and Owner are all in one app: the small swap icon in the top-right corner switches between them
(Owner asks for the PIN). Counter and Kitchen join silently with the shop code.
Use the laptop's current Wi-Fi address, or Tailscale so it never changes.

### Phone orders (prototype)
The owner forwards unanswered calls from their own number to a telephony number (Exotel ExoPhone). A Voicebot applet in that
number's call flow streams each call to `ws://<host>:<VOICE_PORT>/voice?shop=<shop code>&key=<VOICE_KEY>`; the shop code in the URL is how
we know which shop was called, so the caller never has to say it. The agent greets (as an AI), takes the order with the same parser
and prices as the counter, asks when the caller will collect, and books it as a pay-at-pickup order (`source: "phone"`, pickup time,
masked caller number). Only pay-after shops (restaurant, kirana) for now.
```bash
# .env: VOICE_KEY=<random>  VOICE_PORT=3001  TZ=Asia/Kolkata   (calls are refused without VOICE_KEY)
npm run start:multi
# no telephony account needed to try it: a fake Exotel that speaks to the agent and prints what comes back
node --env-file=.env scripts/fake-exotel.mjs "ws://localhost:3001/voice?shop=<CODE>&key=<VOICE_KEY>" "two kilo rice" "that is all" "in twenty minutes" --transcribe
```
Forwarding codes on the owner's SIM: no answer `**61*<number>**15#`, busy `**67*<number>#`, unreachable `**62*<number>#`; turn off with `##61#`, `##67#`, `##62#`.
Expose only the voice port to the provider (`ngrok http 3001`, then use the `wss://` URL).

### Tests
```bash
npm test        # order flow, FIFO/partial readiness, scheduler, voice phrase parsing (uses a temp data dir)
```

## Status / not done
Paytm credentials (adapter written, untested) · face check at pickup · spoken reviews · WhatsApp ordering · daily summary in Hindi/Telugu · batching the same dish across orders.
