// Phone-call agent. A telephony provider (Exotel "Voicebot applet") opens a WebSocket per call and streams the caller's
// audio as 8 kHz PCM; we answer in the same format. Which shop is called comes from the URL the provider connects to
// (?shop=<shop code>), so the caller never has to say anything about it. Orders placed here are marked source:"phone".
import { WebSocketServer } from "ws";
import { timingSafeEqual } from "node:crypto";
import { stt, tts, sarvamEnabled } from "./sarvam.js";
import { Endpointer, pcm8kToWav16k, wavTo8kPcm, parsePickup, fmtClock, PHONE_RATE } from "./telephony.js";
import { runIn } from "./tenant.js";
import { allowed, track } from "./usage.js";
import { menu, priceCart, createOrder, isOut, persist } from "./orders.js";
import { parse, applyToCart } from "./parser.js";
import { say } from "./phrases.js";
import { lineText, unitPrice } from "../public/units.js";

const MAX_CALL_MS = 5 * 60e3, IDLE_MS = 30e3, DEFAULT_PICKUP_MIN = 20, CHUNK = 3200;   // 3200 bytes = 200 ms of 8 kHz PCM16
const safeEq = (a, b) => { const x = Buffer.from(a), y = Buffer.from(b); return x.length === y.length && timingSafeEqual(x, y); };
const log = (...a) => console.log("[voice]", ...a);

/**
 * hooks.tenantFor(shopCode) -> loaded tenant (or null)        hooks.onOrder(tenant, order) -> tell the kitchen/counter
 * Calls are refused unless VOICE_KEY is set and passed as ?key=..., because the endpoint is reachable from the internet
 * and every utterance costs speech-API credits.
 */
export function attachVoice(server, hooks) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 1 << 20 });
  server.on("upgrade", (req, socket, head) => {
    let url; try { url = new URL(req.url, "http://x"); } catch { return socket.destroy(); }
    if (url.pathname !== "/voice") return;
    const need = process.env.VOICE_KEY;
    if (!need || !safeEq(url.searchParams.get("key") || "", need)) { socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n"); return socket.destroy(); }
    wss.handleUpgrade(req, socket, head, (ws) => handleCall(ws, url, hooks).catch((e) => { console.warn("[voice] call failed:", e.message); ws.close(); }));
  });
  return wss;
}

async function handleCall(ws, url, hooks) {
  const tenant = await hooks.tenantFor(url.searchParams.get("shop") || "");
  if (!tenant) { log("unknown shop code"); return ws.close(1008, "unknown shop"); }
  const call = { sid: null, from: "", to: "", lang: "en-IN", cart: [], phase: "order", pickupTries: 0, quiet: 0, busy: false, mutedUntil: 0, ep: new Endpointer(), last: Date.now(), start: Date.now(), closing: false };
  const P = () => say(call.lang);
  const close = (afterMs = 0) => { call.closing = true; setTimeout(() => ws.readyState === 1 && ws.close(), Math.max(0, afterMs)); };

  async function speak(text) {
    let pcm;
    try {
      if (!sarvamEnabled() || !allowed("tts")) throw new Error("tts unavailable");
      track("tts", text.length);
      pcm = wavTo8kPcm(Buffer.from(await tts(text, call.lang), "base64"));
    } catch (e) { log("cannot speak:", e.message); return 0; }
    // send at playback pace, staying ~400 ms ahead: a provider that plays chunks as they arrive then never sits on seconds of
    // buffered audio, and we know when the caller has actually heard the end of it
    const dur = (pcm.length / 2 / PHONE_RATE) * 1000, t0 = Date.now(), LEAD = 400;
    call.mutedUntil = t0 + dur + 300;                // no barge-in yet: ignore the line while we talk (and the echo after)
    for (let i = 0; i < pcm.length; i += CHUNK) {
      const due = t0 + (i / 2 / PHONE_RATE) * 1000 - LEAD;
      if (due > Date.now()) await new Promise((r) => setTimeout(r, due - Date.now()));
      if (ws.readyState !== 1) return 0;
      if (call.uttAt) { log(`reply latency: ${Date.now() - call.uttAt} ms from the end of the caller's sentence to the first audio sent`); call.uttAt = 0; }
      ws.send(JSON.stringify({ event: "media", stream_sid: call.sid, media: { payload: pcm.subarray(i, i + CHUNK).toString("base64") } }));
    }
    call.last = t0 + dur; call.ep.reset();
    return Math.max(0, t0 + dur - Date.now());   // ms until the caller has heard it all
  }

  async function onUtterance(pcm) {
    await runIn(tenant, async () => {
      if (!allowed("stt")) { await speak(P().phoneQuiet); return close(0); }
      track("stt"); track("sttSec", Math.round(pcm.length / 2 / PHONE_RATE));
      const heard = await stt(pcm8kToWav16k(pcm));
      log("heard:", JSON.stringify(heard));
      if (!heard.text) {
        if (++call.quiet >= 2) { const d = await speak(P().phoneQuiet); return close(d + 200); }
        await speak(P().unknown); return;
      }
      call.quiet = 0; if (heard.lang) call.lang = heard.lang;
      const text = heard.text;

      if (call.phase === "pickup") {
        let at = parsePickup(text);
        if (at == null && ++call.pickupTries < 2) { await speak(P().askPickupAgain); return; }
        if (at == null) at = Date.now() + DEFAULT_PICKUP_MIN * 60e3;
        let order;
        try { order = createOrder(call.cart, call.lang); }
        catch (e) { log("cannot create order:", e.message); call.phase = "order"; await speak(P().unknown); return; }
        Object.assign(order, { source: "phone", pickupAt: at, caller: call.from || null }); persist();
        hooks.onOrder?.(tenant, order);
        log(`order #${order.number} booked for ${fmtClock(at)} (₹${order.total})`);
        const d = await speak(P().phoneDone(order.number, fmtClock(at), order.total));
        call.phase = "done"; return close(d + 500);
      }

      // ordering: same rules as the counter's /api/turn
      const p = await parse(text);
      const blocked = Object.keys(p.items).filter((id) => isOut(id)), blockedNames = blocked.map((id) => menu.items.find((i) => i.id === id)?.name).filter(Boolean);
      blocked.forEach((id) => delete p.items[id]);
      if (p.clear) { call.cart = []; await speak(P().cleared); return; }
      const next = applyToCart(call.cart, p), priced = priceCart(next);
      if (p.checkout) {
        if (!priced.lines.length) { await speak(P().empty); return; }
        call.cart = next; call.phase = "pickup"; await speak(P().askPickup); return;
      }
      const changed = Object.keys(p.items).length || Object.keys(p.remove).length;
      if (!changed) { await speak(blocked.length ? P().soldout(blockedNames.join(", ")) : P().unknown); return; }
      call.cart = next;
      await speak((blocked.length ? P().soldout(blockedNames.join(", ")) + " " : "") + P().added(priced.lines.map((l) => lineText(l, true)).join(", "), priced.total));
    });
  }

  ws.on("message", (raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.event === "start") {
      call.sid = m.stream_sid || m.start?.stream_sid; call.from = m.start?.from || ""; call.to = m.start?.to || "";
      log("call start:", JSON.stringify(m.start || m));      // logged on purpose: this is how we learn what the provider tells us about forwarded calls
      const rate = +m.start?.media_format?.sample_rate; if (rate && rate !== PHONE_RATE) log(`WARNING: sample rate ${rate}, only ${PHONE_RATE} is handled`);
      // phone orders are paid at pickup, so they only make sense for pay-after shops (restaurant, kirana) for now
      runIn(tenant, async () => {
        if (menu.payWhen !== "after") { const d = await speak(say("en-IN").phoneUnavailable); return close(d + 200); }
        await speak(say("en-IN").phoneGreet(menu.shop));
      }).catch((e) => log("greeting failed:", e.message));
    } else if (m.event === "media") {
      if (call.busy || call.closing || Date.now() < call.mutedUntil || !m.media?.payload) return;
      const utt = call.ep.push(Buffer.from(m.media.payload, "base64"));
      if (utt) {
        call.busy = true; call.last = Date.now(); call.uttAt = Date.now();
        onUtterance(utt).catch((e) => log("turn failed:", e.message)).finally(() => { call.busy = false; call.last = Math.max(call.last, Date.now()); });
      }
    } else if (m.event === "dtmf") log("dtmf:", m.dtmf?.digit);
    else if (m.event === "stop") { log("call stop:", JSON.stringify(m.stop || {})); ws.close(); }
  });

  const watchdog = setInterval(() => {
    if (call.closing || call.busy) return;
    const now = Date.now();
    if (now - call.start > MAX_CALL_MS || now - call.last > IDLE_MS) {
      runIn(tenant, async () => { const d = await speak(say(call.lang).phoneQuiet); close(d + 200); });
      call.closing = true;
    }
  }, 3000);
  ws.on("close", () => { clearInterval(watchdog); log(`call ended (${Math.round((Date.now() - call.start) / 1000)}s)`); });
}


// ---------------------------------------------------------------------------------------------------------------------
// HTTP tools for a hosted voice agent (Sarvam Voice Agents "API tool"). The hosted agent runs the conversation; it calls
// these mid-call so that menu, prices and orders stay on OUR server. Auth: "Authorization: Bearer <VOICE_KEY>". Each call
// names the shop (shop code) so a single endpoint serves every shop. Stateless: the agent passes the whole order each time.
//   GET  /phone/menu?shop=CODE
//   POST /phone/check  {shop, order_text}                                   -> items + total, nothing is booked
//   POST /phone/book   {shop, order_text, pickup_in_minutes | pickup_text, caller?, call_id?}  -> books a pay-at-pickup order
// ---------------------------------------------------------------------------------------------------------------------
const booked = new Map();   // call_id -> { number, at }: a retried tool call must not book the same order twice

function readJson(req, limit = 64 * 1024) {
  return new Promise((ok, no) => {
    const c = []; let n = 0;
    req.on("data", (d) => { n += d.length; if (n > limit) { no(new Error("too large")); req.destroy?.(); } else c.push(d); });
    req.on("end", () => { try { ok(c.length ? JSON.parse(Buffer.concat(c).toString()) : {}); } catch { no(new Error("bad json")); } });
    req.on("error", no);
  });
}

/** Turns what the caller said into a priced cart using the same parser as the counter. */
async function priceSpoken(text) {
  const p = await parse(String(text || ""));
  const blocked = Object.keys(p.items).filter((id) => isOut(id)), names = blocked.map((id) => menu.items.find((i) => i.id === id)?.name).filter(Boolean);
  blocked.forEach((id) => delete p.items[id]);
  const cart = applyToCart([], p), priced = priceCart(cart);
  return { cart, priced, soldOut: names };
}
/** True when the caller named more things than we matched (e.g. an item that is not on the menu). */
const missed = (text, priced) => text.split(/,|\band\b|\baur\b|\bplus\b|और/i).filter((x) => /[a-z\u0900-\u0DFF]{3,}/i.test(x)).length > priced.lines.length;
const summary = (priced) => `${priced.lines.map((l) => lineText(l, true)).join(", ")}. Total ${priced.total} rupees.`;

export async function handlePhoneTool(req, res, hooks) {
  const send = (code, obj) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(obj)); };
  const url = new URL(req.url, "http://x"), need = process.env.VOICE_KEY, auth = req.headers.authorization || "";
  if (!need || !safeEq(auth.startsWith("Bearer ") ? auth.slice(7) : "", need)) return send(401, { ok: false, message: "unauthorized" });
  let input;
  try { input = req.method === "POST" ? await readJson(req) : Object.fromEntries(url.searchParams); } catch (e) { return send(400, { ok: false, message: e.message }); }
  const tenant = await hooks.tenantFor(String(input.shop || ""));
  if (!tenant) return send(404, { ok: false, message: "I could not find this shop." });
  const route = url.pathname.replace(/\/+$/, "");
  try {
    return await runIn(tenant, async () => {
      if (menu.payWhen !== "after") return send(200, { ok: false, message: "This shop cannot take phone orders right now. Please ask the caller to visit the counter." });
      if (route === "/phone/menu") {
        const items = menu.items.filter((i) => !isOut(i.id));
        return send(200, { ok: true, shop: menu.shop, items: items.map((i) => ({ name: i.name, price: unitPrice(i), also_called: i.aliases?.slice(0, 4) })),
          message: `${menu.shop} has: ` + items.map((i) => `${i.name} ${unitPrice(i)}`).join(", ") + "." });
      }
      if (route === "/phone/check" || route === "/phone/book") {
        const text = String(input.order_text || "").trim();
        if (!text) return send(200, { ok: false, message: "No order text was given. Ask the caller what they would like." });
        if (route === "/phone/book" && input.call_id && booked.has(String(input.call_id))) {
          const b = booked.get(String(input.call_id));
          return send(200, { ok: true, order_number: b.number, message: `Order ${b.number} is already booked.` });
        }
        if (!allowed("llm")) return send(200, { ok: false, message: "The shop is very busy right now. Please ask the caller to visit the counter." });
        const { priced, soldOut } = await priceSpoken(text);
        const sold = soldOut.length ? ` ${soldOut.join(", ")} ${soldOut.length > 1 ? "are" : "is"} sold out.` : "";
        if (!priced.lines.length) return send(200, { ok: false, message: (soldOut.length ? `Sorry.${sold}` : "I could not match that to anything on the menu.") + " Ask the caller to say it again, or read out the menu." });
        if (route === "/phone/check") return send(200, { ok: true, lines: priced.lines.map((l) => ({ name: l.name, qty: l.qty, unit: l.unit, amount: l.amount })), total: priced.total, message: summary(priced) + sold + (missed(text, priced) ? " Part of what the caller said was not found on the menu, so tell them which item could not be added." : "") });

        let at = null; const mins = Number(input.pickup_in_minutes);
        if (mins > 0 && mins <= 360) at = Date.now() + mins * 60e3; else if (input.pickup_text) at = parsePickup(String(input.pickup_text));
        if (at == null) return send(200, { ok: false, needs: "pickup_time", message: `I could not use that pickup time. It is ${fmtClock(Date.now())} now, and pickup must be within the next 6 hours, not in the past. Nothing is booked yet. Tell the caller this is not a system problem, then ask for a time from now, for example in twenty minutes or at six thirty.` });
        let order; try { order = createOrder(priced.lines.map((l) => ({ id: l.id, qty: l.qty, note: l.note })), "en-IN"); }
        catch (e) { return send(200, { ok: false, message: `Could not book: ${e.message}. Ask the caller what to change.` }); }
        Object.assign(order, { source: "phone", pickupAt: at, caller: input.caller ? String(input.caller).slice(0, 20) : null }); persist();
        if (input.call_id) { booked.set(String(input.call_id), { number: order.number, at: Date.now() }); if (booked.size > 500) booked.delete(booked.keys().next().value); }
        hooks.onOrder?.(tenant, order);
        log(`order #${order.number} booked by tool for ${fmtClock(at)} (₹${order.total})`);
        return send(200, { ok: true, order_number: order.number, total: order.total, pickup_time: fmtClock(at),
          message: `Order number ${order.number} is booked for ${fmtClock(at)}. ${summary(priced)} The caller pays at the counter when they collect it.` });
      }
      return send(404, { ok: false, message: "unknown tool" });
    });
  } catch (e) { console.warn("[voice] tool failed:", e.message); return send(500, { ok: false, message: "Something went wrong. Please ask the caller to visit the counter." }); }
}
