# Chitragupta for Shops
### Hackathon · Track 1: Merchant Growth AI · with Sarvam AI and Paytm

---

## 1. Title

# 🤖 Chitragupta for Shops
**The AI business partner for every small shop, in the owner's own language.**

*Speak the order. Get paid. Know your business.*

Built on **Paytm** payments and **Sarvam AI** Indian-language voice.

> Speaker note: one line. "Millions of small merchants run on memory, paper and WhatsApp. We give each of them a partner that listens, takes the order, collects the payment and tells them what to do next, in Telugu, Hindi or English."

---

## 2. Problem Statement

**A small shop is run by one overloaded owner, with no tools built for them.**

- **Taking orders is slow and error-prone.** Someone has to listen, write on paper, and shout it to the kitchen or the storeroom. Rush hour means wrong orders, forgotten items and queues.
- **Payments are not tied to orders.** A customer says "I paid", and the shop cannot always tell. Money and orders live in different places.
- **Nobody tells the owner what is happening.** Stock runs out unnoticed, the best sellers are guesses, and there is no daily summary. Existing software is built for chains, in English, with menus too complex for a stall.
- **Language is the barrier.** Owners, helpers and customers speak Telugu, Hindi and a dozen other languages. Typing into an app is not how they work.
- **Every shop type is different.** A canteen cooks, a restaurant serves tables, a kirana weighs and packs from a paper slip. One-size apps fit none of them.

> Speaker note: the barrier is not money or intent, it is usability. The shop already works by voice and paper, so we build for that.

---

## 3. Proposed Solution

**One voice-first platform that adapts to any small shop and runs it end to end.**

**The flow**
1. **Speak** — the customer or the shop's writer says the order in their own language: *"one kilo rice, half kilo sugar, ripe tomatoes two kilos."* Quantities, units and quality notes are understood.
2. **Send** — the order goes as a digital "paper slip" to the right person's phone: the chef, or the picker who fetches the items. It is spoken to them in their language, one task at a time.
3. **Pay** — Paytm UPI QR for the exact bill. Payment is confirmed by the payment provider, never by the customer's word, and the order is released only when it is truly paid.
4. **Hand over** — "Order 104 is ready" is announced in the customer's language, and pickup is verified with a code.
5. **Learn** — every order feeds the **owner's copilot**.

**The owner's copilot** (the business partner)
- A spoken **daily briefing**: today against yesterday, best sellers, waiting times, the busiest hour.
- **Ask anything by voice:** "What is running low?", "Why were sales lower than yesterday?", "What should I prepare for tomorrow?". Answers come from the shop's real numbers, in Telugu, Hindi or English.
- **Proactive advice:** restock before items run out, prepare the right quantities, add a hand during the rush, run offers on slow items.

**Adapts to the shop**
| Shop | How it works |
|---|---|
| **Food stall / canteen** | speak → pay → chefs cook → announce → pickup code |
| **Restaurant** | table order → kitchen → serve → bill at the end |
| **Kirana / grocery** | writer takes the list → picker gets the slip, ticks items, marks "not available" → bill adjusts → UPI or cash |

Switching shop type, editing prices and tracking stock takes a few taps. Any other small shop is a new profile, not new software.

> Speaker note: the key idea is that the paper slip the shop already uses becomes digital, so nothing about how they work has to change.

---

## 4. Technology / Tech Stack Used

**Voice and language: Sarvam AI**
- **Saaras** speech-to-text, translate mode: one call returns the English text for the parser and the spoken language.
- **Bulbul** text-to-speech: spoken replies and announcements in Telugu, Hindi and English, with fixed phrases cached.
- **Sarvam 30B** (open weights) running **on our own GPU**: order understanding and the copilot's answers, so shop data stays under our control and per-order model cost stays near zero.

**Payments: Paytm**
- **Paytm Dynamic QR** for the exact amount of each order, and **Paytm order-status** verification on the server, so only provider-confirmed payments release an order.
- Designed to sit on Paytm's merchant rails and settlement.

**Platform**
- **Node.js backend**, one shared multi-shop service: each shop's menu, stock, orders and live screens are isolated; access by shop code plus signed role tokens; per-shop usage limits.
- **Live updates** to every phone in a shop (server-sent events).
- **Android app** (one APK for every role: counter, kitchen/picker, owner) built natively in Kotlin with Jetpack Compose.
- **Profiles** describe a shop type: catalog with units and stock, how work is fulfilled, when payment happens, and vocabulary.
- **Safety net:** if the model or network is unavailable, rule-based parsing and answers keep the shop running.

**Proven at scale:** load-tested at 200 shops (100 kirana, 100 food stalls) on a single server process with no data crossing between shops.

```
 Phone (counter / kitchen / owner)
        │ voice, taps                 ┌──────────── Sarvam: Saaras STT · Bulbul TTS
        ▼                             │
  Multi-shop backend ────────────────►├──────────── Local Sarvam 30B (our GPU): parse + copilot
  (isolated per shop, metered)        │
        │                             └──────────── Paytm: Dynamic QR + payment verification
        ▼
  Per-shop store (orders, stock, kitchen, insights)
```

---

## 5. USP (Unique Selling Proposition)

1. **Voice-first in the owner's language.** Telugu, Hindi and English today and any Sarvam language next, with mixed speech understood. No typing, no training.
2. **Works the way small shops already work.** The paper slip becomes a digital slip, so a kirana's writer-and-picker routine or a canteen's rush keeps its shape.
3. **One engine, any small shop.** Canteen, restaurant, kirana, and the next shop type is a configuration, not a rebuild.
4. **Payment-verified orders.** Orders release only on provider confirmation, so there is no "I paid" dispute and no unpaid food.
5. **A partner, not a dashboard.** The copilot speaks, answers questions, and tells the owner what to do tomorrow, using the shop's own data.
6. **Very low cost to serve.** Order understanding and the copilot run on our own GPU, so cost grows with speech usage, not with every model call. Per-shop usage limits cap the worst case.
7. **Built to scale.** One shared backend serves many shops, so adding a shop adds almost no infrastructure.

---

## 6. Impact & Benefits

**For the owner**
- **Faster service, fewer mistakes:** orders reach the cook or picker instantly, in their language, with notes like "ripe" or "no onion".
- **Every order is paid and recorded:** no ledger, no disputes.
- **Fewer stock-outs and less waste:** the copilot warns before items run out and suggests what to prepare.
- **Time back:** a spoken daily briefing replaces guesswork and manual counting.
- **Inclusive by design:** an owner who cannot read an English dashboard can still run a smarter shop.

**For customers**
- Order in their own language, pay with any UPI app, and hear when it is ready. A pickup code protects against wrong handovers.

**For Paytm and the ecosystem**
- Every shop's order is tied to a verified digital payment, deepening digital payment adoption among the smallest merchants.
- A clear, consented view of a merchant's real sales opens the door to better financial products for them, such as working-capital credit.

**What we will measure with pilot shops** *(targets to confirm in pilots, not results)*
- Order-to-handover time and order error rate before and after.
- Share of orders paid digitally and verified.
- Stock-outs avoided, and owner time saved per day.
- Weekly active use of the copilot.

---

## 7. Business Model

**Who pays: the shop, with a price a small merchant will accept. Pricing below is illustrative and will be validated with pilot shops.**

| Tier | Who | What | Price (illustrative) |
|---|---|---|---|
| **Starter** | tiny stalls | tap and typed ordering, UPI QR, order board | free |
| **Pro** | most shops | voice in Indian languages, kitchen / picker slips, pickup codes, stock tracking, daily spoken briefing, copilot Q&A | monthly subscription, low three digits in rupees |
| **Plus** | busier shops and small chains | multiple staff and devices, deeper insights, usage above the standard limits | higher monthly plan |

**Additional revenue**
- **Payments partnership with Paytm:** a share of payment-processing economics on volume that flows through the platform, subject to the partnership terms.
- **Distribution through Paytm's merchant network:** onboarding through existing merchant relationships keeps customer-acquisition cost low.
- **Financial services referrals:** with the owner's consent, qualified merchants can be matched with credit or insurance offers through partners.

**Unit economics**
- **Cost to serve per shop:** the shared backend (very small per shop), our own GPU for order understanding and the copilot (falls as shops grow), and Sarvam speech charges that follow usage. Per-shop daily limits cap that last cost.
- **Levers to protect margin:** cached fixed phrases, silent tap ordering, typed fallback, and limits per plan.

**Go-to-market:** start with canteens and kirana clusters in a few cities through pilots, then expand shop types and languages, riding the Paytm merchant base.

> Speaker note: close with the vision. "Every small shop gets a business partner that speaks their language. We start with canteens and kiranas, and the same engine reaches every small merchant."

---

*Chitragupta for Shops · Track 1: Merchant Growth AI*
