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
import { lineText } from "../public/units.js";

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
