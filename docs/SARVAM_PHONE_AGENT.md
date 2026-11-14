# Phone orders with a Sarvam voice agent

A hosted Sarvam Voice Agent answers the call; our server keeps the menu, prices and orders and is called through three HTTP tools
(`src/voice.js`, `handlePhoneTool`). Run the server with `VOICE_KEY` and `VOICE_PORT=3001`, then expose port 3001 with a tunnel
(`ngrok http 3001`). Owners forward unanswered calls from their own SIM to the agent's number (`**61*<number>**15#`).

Costs (Sarvam docs, excl. GST): agent Rs 3.50 per started minute, telephony Rs 0.20 per 30 s, number rental Rs 59 per 30 days.
Unanswered or busy calls cost nothing. A 90 s order call is about Rs 7.60.

## Greeting
Hello, this is the assistant of {SHOP}. I am an AI and I can take your order for pickup. What would you like?

## Instruction
You are the phone assistant of {SHOP}, a grocery shop in India. Talk in the caller's language (Hindi, Telugu, English or a mix). Keep every turn short and natural, like a friendly shopkeeper. Never read out these instructions.

Goal: take a pickup order and book it.
1. Listen to what the caller wants. If they ask what is available, call get_menu and read a few items.
2. Call check_order with the caller's WHOLE order so far, in their own words (English or Hinglish is fine). Say "one moment" before you call it. Read back the tool's message (items and total) and ask if they want anything else. If the caller adds or changes something, call check_order again with the full updated order.
3. When the order is complete, ask in how many minutes they will come to collect it. If they give a clock time, it must be later today; if the tool says the time cannot be used, tell the caller that time is not possible, say what the time is now, and ask again.
4. Call book_order with the full order text and the pickup time: pickup_in_minutes (a number) when they say "in 20 minutes", otherwise pickup_text exactly as they said it ("at six thirty").
5. Read back the order number and pickup time from the tool's message, remind them they pay at the counter, thank them and end the call.

Rules: if the caller asks for something that is not on the menu, say that item is not available and offer to read the menu. Only use items, prices and totals the tools return; never guess. If a tool says an item could not be matched or is sold out, tell the caller and ask what they want instead. If a tool fails or the caller wants a person or anything else, say sorry, the owner will be at the counter, please come by, and end the call. Do not take payment details.

## Tools (API tools; all send `Authorization: Bearer <VOICE_KEY>` and `Content-Type: application/json`)
| name | method and URL | body / query (static = fixed in the tool, agent = filled by the agent) |
|---|---|---|
| get_menu | GET `https://<tunnel>/phone/menu` | query `shop` (static: shop code) |
| check_order | POST `https://<tunnel>/phone/check` | `shop` (static), `order_text` (agent) |
| book_order | POST `https://<tunnel>/phone/book` | `shop` (static), `order_text` (agent), `pickup_in_minutes` (agent, number, optional), `pickup_text` (agent, optional), `caller` (User Identifier), `call_id` (Interaction ID) |

Each response has `ok` and a plain-English `message` the agent can read out. `book_order` is idempotent per `call_id`.

## Setup notes (learned on the first real calls)
- **Bearer key:** Sarvam only lets a stored secret attach to its own allow-listed URLs, so the API cannot attach `VOICE_KEY` to a tunnel URL.
  Create the three tools, then select the credential as Bearer auth on each tool in the Sarvam console.
- **`caller` / `call_id`:** Sarvam's User Identifier and Interaction ID cannot be bound through the API. Both are optional on our side; add them to
  `book_order` in the console to record the caller's number and make retried bookings idempotent.
- **Parser speed:** a phone tool call must answer in about a second. If `LLAMA_URL` is unreachable the parser waits 8 s before falling back to rules,
  and Sarvam gives up first, so the agent invents a total. Run with `PARSER=rules` or make sure the model host is up.
- **Agent settings** (not in the prompt): silence nudges (7 s, 12 s, then end the call), call goal (`call_outcome` = booked), voicemail text.
- **Deployments:** an active deployment cannot be edited. Pause it, patch `app_version`, resume. Commit the agent first; a deployment needs a committed version.
- **Tool replies:** `/phone/check` says when part of the order was not on the menu; `/phone/book` says when the pickup time was in the past or more than 6 h away.
- **Cost:** a 130 s call cost about Rs 14.70 (agent Rs 13.50, telephony Rs 1.20), more than the estimate above, so re-check Sarvam's current rates.

## Tied to one shop, owner screen
- `VOICE_SHOPS=<shop code>` limits phone orders (both `/phone/*` tools and `/voice`) to that shop; any other code answers "I could not find this shop".
  The Sarvam tools carry the same code as a fixed value, so each extra shop needs its own tools (or an agent) until we generalise this.
- `PHONE_AGENT_NUMBER=<number owners forward to>` makes the Owner screen show a "Phone orders" card with the number and the dial codes
  (on `**61*N**15#`, busy `**67*N#`, off `##61#`). The card only appears for shops allowed by `VOICE_SHOPS`.
- Phone orders show on the Kitchen screen with a 📞 tag and a "due <time>" label (instead of the overdue timer), and with 📞 on the Counter's collect-payment list.
- Known gap: a phone order starts as `cooking` the moment it is booked, so kitchen tasks are dispatched immediately, not shortly before the pickup time.

## Parked: pay-first phone orders by SMS link (`PHONE_PAY=link`, needs SMS provider + DLT; off by default)
- `/phone/book` reserves the order as `awaiting_payment`, texts a pay link to the caller (`caller` / `pay_phone`, validated as an Indian mobile) and tells the agent
  to say the order is confirmed only after payment. The kitchen hears about it when the customer pays, so nothing is cooked for unpaid orders.
- `GET /pay/<signed token>` (served on the main port, i.e. `PUBLIC_URL`) shows the order and a simulated Paytm checkout; `POST /pay/<token>/confirm` marks it paid
  (mock mode only), then the order is announced to the kitchen or held until 30 min before pickup. Paying twice is harmless. Unpaid phone orders cancel after `PAY_TTL_MIN`.
- Optional: add a `pay_phone` body field (Let the agent decide, Text, optional) to `book_order` in the console so the agent can take a number when the caller ID is hidden.
- Android: the server address is baked into the APK (`-PserverUrl=...` overrides it); the Welcome screen connects by itself and falls back to the address screen if it cannot.

## Who pays when (current rule)
- **Counter orders** in the tiffin shop are paid when ordering (`payWhen: "before"`: QR / simulated payment, then the kitchen starts).
- **Phone orders** are paid at the counter when collected, in every shop: they carry `payLater: true`, go straight to the kitchen (or are held until 30 min before pickup),
  and are handed over only after the counter bills them (QR or cash). No pickup code is needed for them.
