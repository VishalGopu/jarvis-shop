import http from "node:http";
import { readFile } from "node:fs/promises";
import { extname } from "node:path";
import { stt, tts, sarvamEnabled } from "./src/sarvam.js";
import { wavDuration } from "./src/audio.js";
import { createQr, verifyPaid, isMock } from "./src/pay.js";
import { runIn, isMulti, cur } from "./src/tenant.js";
import { loadState } from "./src/store.js";
import { createShop, shopByCode, getShop, listShops, publicShop, pinOkFor, issue, verify, can, locked, failed, cleared, signupAllowed, ROLES } from "./src/tenants.js";
import { allowed, track, usageView } from "./src/usage.js";
import { startDue, phoneLeadMs, tenants, loadTenant, unloadTenant, flushAll, orders, menu, profiles, soldOut, isOut, priceCart, createOrder, markPaid, settle, markOrderReady, markLine, markUnavailable, allocateDish, parseKitchen, cancelOrder, deliver, pinOk, setSoldOut, setShop, setCatalog, findDish } from "./src/orders.js";
import { lineText, unitPrice } from "./public/units.js";
import { schedule, completeTask, transferTask, setOnline, freeChef, setKitchen, kitchenView, chefsView } from "./src/kitchen.js";
import { facts, suggestions, briefing, answerRules, renderSuggestion, noHear, LANG_NAMES } from "./src/insights.js";
import { ask, llmProvider } from "./src/llm.js";
import { parse, applyToCart } from "./src/parser.js";
import { fmtClock } from "./src/telephony.js";
import { say, replyLang } from "./src/phrases.js";
import { attachVoice, handlePhoneTool } from "./src/voice.js";

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "Content-Type, Authorization", "Access-Control-Allow-Methods": "GET,POST,OPTIONS" };
const json = (res, code, obj) => { res.writeHead(code, { "Content-Type": "application/json", ...CORS }); res.end(JSON.stringify(obj)); };
const body = (req) => new Promise((ok, no) => { const c = []; let n = 0; req.on("data", (d) => { n += d.length; if (n > 8e6) { no(new Error("request too large")); req.destroy(); } else c.push(d); }); req.on("end", () => ok(Buffer.concat(c))); req.on("error", no); });
const speak = async (text, lang) => { if (!sarvamEnabled() || !allowed("tts")) return null; try { const a = await tts(text, replyLang(lang)); track("tts", text.length); return a; } catch (e) { console.warn(e.message); return null; } };
// speech-to-text with the shop's daily quota and usage metering
const listen = async (b64) => { const buf = Buffer.from(b64, "base64"); const seconds = wavDuration(buf); if (!seconds) { const e = new Error("Recording was too short or invalid. Hold the microphone button a little longer and try again."); e.invalidAudio = true; throw e; } if (!allowed("stt")) { const e = new Error("daily voice limit reached - please type or tap instead"); e.limited = true; throw e; } track("stt"); track("sttSec", Math.round(seconds)); return stt(buf); };
const KITCHEN_LANG = process.env.KITCHEN_LANG || "en-IN";
// live updates (SSE) are scoped to ONE shop: each shop state owns its own set of connected devices
const emitTo = (t, type, data = {}) => { const m = `data: ${JSON.stringify({ type, ...data })}\n\n`; for (const c of t.clients) c.write(m); };
const emit = (type, data = {}) => emitTo(cur(), type, data);
setInterval(() => { for (const t of tenants.values()) for (const c of t.clients) c.write(": ping\n\n"); }, 20000).unref();
// Counter speaker announces "order N is ready" in the customer's language whenever an order completes (from any path).
const wire = (t) => { t.hooks.ready = (o) => emitTo(t, "ready", { number: o.number, lang: o.lang, text: t.menu.payWhen === "after" ? say(o.lang).readyPay(o.number) : say(o.lang).ready(o.number) }); };
// Run the scheduler and speak each new task on that chef's own device (SSE event addressed by chefId).
const taskText = (a) => { const t = a.whole ? say(a.lang).newOrder(a.number, a.items.map((i) => lineText(i, true)).join(", ")) : say(a.lang).task(lineText(a.items[0], true), a.number); return a.from ? say(a.lang).transfer(a.from, t) : t; };
function dispatch() { const out = schedule(); for (const a of out) emit("task", { chefId: a.chefId, number: a.number, lang: a.lang, text: taskText(a) }); emit("update"); return out; }
const menuView = () => ({ shop: menu.shop, items: menu.items.map((i) => ({ ...i, soldOut: isOut(i.id) })) });
const profileView = () => ({ id: menu.id, kindLabel: menu.kindLabel, fulfil: menu.fulfil, payWhen: menu.payWhen, table: !!menu.table, words: menu.words, shops: Object.values(profiles).map((p) => ({ id: p.id, label: p.label })) });
const slipText = (o) => say(KITCHEN_LANG).newOrder(o.number, o.lines.map((l) => lineText(l, true)).join(", "));
const view = (o) => ({ id: o.id, number: o.number, lines: o.lines, total: o.total, status: o.status, paidAt: o.paidAt, startedAt: o.startedAt, lang: o.lang, table: o.table, method: o.method, source: o.source, pickupAt: o.pickupAt, startAt: o.startAt, caller: o.caller ? "XXXXXX" + String(o.caller).slice(-4) : undefined });

async function app(req, res, url, role) {
  try {
    if (url.pathname === "/api/config") return json(res, 200, { menu: menuView(), profile: profileView(), sarvam: sarvamEnabled(), mock: isMock(), parser: process.env.PARSER || (process.env.LLAMA_URL ? "llama" : process.env.OLLAMA_URL ? "ollama" : "rules") });

    // One customer turn: audio(wav b64) | text -> updated cart + spoken reply
    if (req.method === "POST" && url.pathname === "/api/turn") {
      const { text: typed, audio, cart = [], lang: hint, add: payload_add } = JSON.parse((await body(req)).toString());
      const t = {}; let text = typed || "", lang = hint || "en-IN";
      if (audio) { const s = Date.now(); let r; try { r = await listen(audio); } catch (e) { if (!e.limited && !e.invalidAudio) throw e; return json(res, 200, { heard: "", reply: e.invalidAudio ? say(lang).noAudio : e.message, lang, cart, ...pricedView(cart), audio: null, action: "none", limited: !!e.limited, timings: t }); } text = r.text; lang = r.lang || lang; t.stt = Date.now() - s; }
      const P = say(lang);
      const reply = async (msg, extra = {}) => { const s = Date.now(); const a = await speak(msg, lang); t.tts = Date.now() - s; return json(res, 200, { heard: text, reply: msg, lang, cart, ...pricedView(cart), audio: a, timings: t, ...extra }); };
      if (!text && !payload_add) return reply(P.unknown);
      const p = payload_add ? { items: { [payload_add.id]: payload_add.qty }, notes: {}, remove: {}, checkout: false, clear: false, via: "tap", ms: 0 } : await parse(text); t.parse = p.ms;
      const blocked = Object.keys(p.items).filter((id) => isOut(id)); const blockedNames = blocked.map((id) => menu.items.find((i) => i.id === id).name); blocked.forEach((id) => delete p.items[id]);
      const next = applyToCart(cart, p); const changed = Object.keys(p.items).length || Object.keys(p.remove).length;
      Object.assign(t, { via: p.via });
      if (p.clear) return json(res, 200, { ...(await (async () => { const a = await speak(P.cleared, lang); return { heard: text, reply: P.cleared, lang, cart: [], lines: [], total: 0, action: "clear", audio: a, timings: t }; })()) });
      const pv = pricedView(next);
      if (p.checkout) {
        if (!pv.lines.length) { cart.length = 0; return reply(P.empty); }
        const msg = menu.payWhen === "after" ? P.sent(pv.total) : P.pay(pv.total); const s = Date.now(); const a = await speak(msg, lang); t.tts = Date.now() - s;
        return json(res, 200, { heard: text, reply: msg, lang, cart: next, ...pv, action: "checkout", audio: a, timings: t });
      }
      if (!changed) return reply(blocked.length ? P.soldout(blockedNames.join(", ")) : P.unknown);
      const msg = (blocked.length ? P.soldout(blockedNames.join(", ")) + " " : "") + P.added(pv.lines.map((l) => lineText(l, true)).join(", "), pv.total); const s = Date.now(); const a = payload_add ? null : await speak(msg, lang); t.tts = Date.now() - s;   // taps are silent and instant
      return json(res, 200, { heard: text, reply: msg, lang, cart: next, ...pv, action: "none", audio: a, timings: t });
    }

    if (req.method === "POST" && url.pathname === "/api/checkout") {
      const { cart, lang, table } = JSON.parse((await body(req)).toString());
      let o; try { o = createOrder(cart, replyLang(lang), table); } catch (e) { return json(res, 409, { error: e.message }); }
      if (menu.payWhen === "after") { dispatch(); emit("order", { order: view(o), text: slipText(o) }); return json(res, 200, { order: view(o), qr: null, payLater: true }); }   // slip goes straight to the team; billed when packed
      return json(res, 200, { order: view(o), qr: await createQr(o) });
    }

    let m;
    if (req.method === "GET" && (m = url.pathname.match(/^\/api\/order\/(ORD\d+)$/))) {
      const o = orders.find((x) => x.id === m[1]); if (!o) return json(res, 404, { error: "no such order" });
      let announce = null;
      if (o.status === "awaiting_payment" && (await verifyPaid(o))) { markPaid(o); dispatch(); emit("order", { order: view(o), text: slipText(o) }); const msg = say(o.lang).paid(o.number, menu.payWhen === "after" ? null : o.pin); announce = { text: msg, audio: await speak(msg, o.lang) }; }
      else if (o.status === "ready" && !o.paidAt && menu.payWhen === "after" && (await verifyPaid(o))) { settle(o, "upi"); emit("update"); const msg = say(o.lang).paid(o.number); announce = { text: msg, audio: await speak(msg, o.lang) }; }
      return json(res, 200, { order: view(o), announce, pin: o.paidAt && menu.payWhen !== "after" ? o.pin : null });
    }
    if (req.method === "POST" && (m = url.pathname.match(/^\/api\/order\/(ORD\d+)\/mock-pay$/))) {
      if (!isMock()) return json(res, 403, { error: "mock pay disabled" });
      const o = orders.find((x) => x.id === m[1]); if (o) o.mockPaid = true; return json(res, 200, { ok: !!o });
    }

    // pay-after shops: bill a finished order at the counter (UPI QR for the final amount, or cash)
    if (req.method === "POST" && (m = url.pathname.match(/^\/api\/order\/(ORD\d+)\/(bill|cash)$/))) {
      const o = orders.find((x) => x.id === m[1]); if (!o) return json(res, 404, { error: "no such order" });
      if (menu.payWhen !== "after" || o.status !== "ready" || o.paidAt) return json(res, 409, { error: "order is not waiting for payment" });
      if (m[2] === "cash") { settle(o, "cash"); emit("update"); return json(res, 200, { order: view(o) }); }
      return json(res, 200, { order: view(o), qr: await createQr(o) });
    }
    if (req.method === "POST" && url.pathname === "/api/line/unavailable") {
      const { number, lineId } = JSON.parse((await body(req)).toString()); const r = markUnavailable(number, lineId); if (r.error) return json(res, 409, r);
      dispatch(); return json(res, 200, { ok: true, total: r.order.total, completed: r.completed });
    }
    // owner: switch shop type, edit the catalog (price, unit, stock, voice aliases)
    if (req.method === "POST" && url.pathname === "/api/shop") { const r = setShop(JSON.parse((await body(req)).toString()).profile); if (r.error) return json(res, 409, r); dispatch(); emit("menu"); return json(res, 200, { profile: profileView(), menu: menuView() }); }
    if (req.method === "POST" && url.pathname === "/api/catalog") { const r = setCatalog(JSON.parse((await body(req)).toString()).items); if (r.error) return json(res, 400, r); emit("menu"); return json(res, 200, { menu: menuView() }); }

    if (req.method === "GET" && url.pathname === "/api/kitchen") return json(res, 200, { orders: orders.filter((o) => ["cooking", "ready"].includes(o.status)).sort((a, b) => (a.startedAt || a.paidAt) - (b.startedAt || b.paidAt)).map(view), upcoming: orders.filter((o) => o.status === "scheduled").sort((a, b) => a.pickupAt - b.pickupAt).map(view), ...kitchenView() });

    // Chef marks things ready: tap (number [+ lineId]) or voice ("order 104 ready", "dosa ready", "5 dosa ready").
    // Dishes are allocated FIFO to the oldest PAID orders; an order is announced (in its customer's language) once ALL its items are done.
    if (req.method === "POST" && url.pathname === "/api/ready") {
      const { audio, text: typed, dish, number, lineId, qty } = JSON.parse((await body(req)).toString());
      let heard = typed || "", results = [];
      if (number && lineId) { const r = markLine(number, lineId, qty); if (r) results.push(r); }
      else if (number) { const r = markOrderReady(number); if (r) results.push(r); }
      else {
        if (audio) heard = (await listen(audio)).text;
        const k = parseKitchen(heard); if (dish) k.dishes.push({ id: dish, qty: null });
        if (k.numbers.length) k.numbers.forEach((n) => { const r = markOrderReady(n); if (r) results.push(r); });
        else k.dishes.forEach((d) => results.push(...allocateDish(d.id, d.qty)));
      }
      if (!results.length) return json(res, 200, { heard, results: [], announcements: [] });
      dispatch();
      const announcements = results.filter((x) => x.completed).map((r) => ({ number: r.order.number, lang: r.order.lang, text: say(r.order.lang).ready(r.order.number) }));
      return json(res, 200, { heard, results: results.map((r) => ({ number: r.order.number, status: r.order.status, completed: r.completed })), announcements });
    }
    if (req.method === "POST" && (m = url.pathname.match(/^\/api\/order\/(ORD\d+)\/cancel$/))) {
      const o = orders.find((x) => x.id === m[1]); if (!o) return json(res, 404, { error: "no such order" });
      if (o.status === "awaiting_payment" && (await verifyPaid(o))) { markPaid(o); dispatch(); emit("order", { order: view(o), text: slipText(o) }); return json(res, 409, { error: "already paid", order: view(o) }); }
      const ok = !!cancelOrder(o.id); if (ok) dispatch(); return json(res, ok ? 200 : 409, { ok: o.status === "cancelled", status: o.status });
    }
    if (req.method === "POST" && url.pathname === "/api/deliver") { const { number, pin, override } = JSON.parse((await body(req)).toString()); if (!override && !pinOk(number, pin)) return json(res, 403, { error: "wrong pickup code" }); const ok = !!deliver(number); if (ok) dispatch(); return json(res, 200, { delivered: ok }); }

    if (url.pathname === "/api/events") { res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive", ...CORS }); res.write("retry: 2000\n\n"); const t = cur(); t.clients.add(res); req.on("close", () => t.clients.delete(res)); return; }
    if (req.method === "POST" && url.pathname === "/api/soldout") { const { id, soldOut: v } = JSON.parse((await body(req)).toString()); const ok = setSoldOut(id, !!v); if (ok) emit("menu"); return json(res, ok ? 200 : 400, { ok }); }
    if (req.method === "POST" && url.pathname === "/api/tts") { const { text, lang } = JSON.parse((await body(req)).toString()); return json(res, 200, { audio: await speak(String(text || "").slice(0, 300), lang || KITCHEN_LANG) }); }

    // ---- kitchen setup + chef devices
    if (url.pathname === "/api/kitchen/config") { if (req.method === "POST") { const v = setKitchen(JSON.parse((await body(req)).toString())); dispatch(); return json(res, 200, v); } return json(res, 200, kitchenView()); }
    if (req.method === "POST" && (m = url.pathname.match(/^\/api\/chefs\/([\w-]+)\/(online|complete|free|transfer)$/))) {
      const [, id, act] = m; const b = JSON.parse((await body(req)).toString() || "{}");
      if (act === "online") { if (!setOnline(id, b.online !== false)) return json(res, 404, { error: "no such chef" }); dispatch(); return json(res, 200, { chefs: chefsView() }); }
      if (act === "free") { if (!freeChef(id)) return json(res, 404, { error: "no such chef" }); dispatch(); return json(res, 200, { chefs: chefsView() }); }
      if (act === "transfer") {
        const r = transferTask(id, b.to || "any"); if (r.error) return json(res, 409, r);
        dispatch();
        if (r.target) { const t = chefsView().find((c) => c.id === r.target); const got = t.task?.number === r.number;   // idle target already got it (spoken by dispatch); a busy one is told it is queued
          if (!got) emit("task", { chefId: r.target, number: r.number, lang: t.lang, text: say(t.lang).queued(chefsView().find((c) => c.id === id).name, r.items.map((i) => lineText(i, true)).join(", ")) }); }
        return json(res, 200, { ok: true, to: r.targetName || "anyone free", chefs: chefsView() });
      }
      let heard = b.text || ""; if (b.audio) heard = (await listen(b.audio)).text;   // chef can say "done"
      if ((b.audio || b.text != null) && !/\b(done|finish(ed)?|ready|complete(d)?|over)\b/i.test(heard)) return json(res, 200, { heard, completed: false, reason: "not a done phrase" });
      const r = completeTask(id); if (!r) return json(res, 200, { heard, completed: false, reason: "no active task" });
      dispatch(); return json(res, 200, { heard, completed: true, order: view(r.order), orderReady: r.completed });
    }

    // ---- owner copilot: insights, spoken briefing, voice/text Q&A grounded in real order data
    const OL = ["en-IN", "hi-IN", "te-IN"];
    if (url.pathname === "/api/insights") { const f = facts(); return json(res, 200, { facts: f, suggestions: suggestions(f) }); }
    if (url.pathname === "/api/briefing") {
      const lang = OL.includes(url.searchParams.get("lang")) ? url.searchParams.get("lang") : "en-IN"; const f0 = facts(), b = briefing(f0, lang);
      return json(res, 200, { ...b, tips: suggestions(f0).map((x) => renderSuggestion(x, lang)), lang, audio: url.searchParams.get("audio") === "1" ? await speak(b.text, lang) : null });
    }
    if (req.method === "POST" && url.pathname === "/api/copilot") {
      const { audio, text: typed, lang: sel } = JSON.parse((await body(req)).toString()); const t = {};
      let q = typed || "", lang = OL.includes(sel) ? sel : "en-IN";
      if (audio) { const s0 = Date.now(); let r = { text: "", lang: null }; try { r = await listen(audio); } catch (e) { if (!e.invalidAudio) throw e; } q = r.text; if (OL.includes(r.lang)) lang = r.lang; t.stt = Date.now() - s0; }   // answer in the language the owner spoke
      const f = facts(); const open = /should|suggest|advice|offer|why|idea|improve|चाहिए|सलाह|ऑफर|क्यों|ఆఫర్|ఎందుకు|సలహా|కొనాలి|చేయాలి/i.test(q) || q.trim().split(/\s+/).length > 7;   // open-ended: let the model reason, fixed rules only for plain fact questions
      let answer = q.trim() && !open ? answerRules(q, f, lang) : null, source = "rules";
      if (!q.trim()) answer = noHear(lang);
      else if (answer == null && llmProvider()) {
        const s1 = Date.now();
        try {
          const { hourly, ...week } = f.week; const compact = { today: f.today, yesterday: f.yesterday, last7days: { ...week, busiestHourFrom24: f.week.peakHour }, tomorrowPrepSuggestion: f.tomorrow, slowestHour: f.waitPeak, soldOut: f.soldOut, ordersBeingCookedNow: f.cooking, stockLeft: f.stock };
          answer = await ask(`You are Jarvis, the trusted business partner of the owner of "${f.shop}", a small ${f.kind || "shop"} in India. Answer the owner's question using ONLY the FACTS below (amounts are in rupees; hours use a 24-hour clock). Speak like a helpful friend: 1-3 short sentences, plain words, no markdown, no lists, no emojis. Never invent numbers. If the FACTS do not contain the answer, say you do not have that information yet. You may add one practical suggestion that follows from the FACTS. Reply in ${LANG_NAMES[lang]}.\nFACTS: ${JSON.stringify(compact)}`, q);
          if (answer) source = "llm";
        } catch (e) { console.warn("copilot LLM failed:", e.message); answer = null; }
        t.llm = Date.now() - s1;
      }
      if (!answer && q.trim()) answer = answerRules(q, f, lang);   // model unavailable: best fixed answer
      if (!answer) answer = briefing(f, lang).text;                // nothing matched and no model: give the briefing instead of silence
      const s2 = Date.now(); const a = await speak(answer, lang); t.tts = Date.now() - s2;
      return json(res, 200, { heard: q, answer, lang, source, audio: a, timings: t });
    }

    // Owner's daily summary (numbers from the order log; text now, LLM-written + multilingual next)
    if (url.pathname === "/api/summary") {
      const paid = orders.filter((o) => o.paidAt); const rev = paid.reduce((s, o) => s + o.total, 0);
      const tally = {}; paid.forEach((o) => o.lines.forEach((l) => (tally[l.name] = (tally[l.name] || 0) + l.qty)));
      const top = Object.entries(tally).sort((a, b) => b[1] - a[1]).slice(0, 3);
      const waits = paid.filter((o) => o.deliveredAt).map((o) => (o.deliveredAt - o.paidAt) / 60000);
      const hours = {}; paid.forEach((o) => { const h = new Date(o.paidAt).getHours(); hours[h] = (hours[h] || 0) + 1; });
      const peak = Object.entries(hours).sort((a, b) => b[1] - a[1])[0];
      const text = paid.length ? `Today you served ${paid.length} orders and earned ${rev} rupees. Top items: ${top.map(([n, q]) => `${n} (${q})`).join(", ")}.${waits.length ? ` Average wait after payment was ${(waits.reduce((a, b) => a + b, 0) / waits.length).toFixed(1)} minutes.` : ""}${peak ? ` Busiest hour: ${peak[0]}:00.` : ""}` : "No paid orders yet today.";
      return json(res, 200, { text, stats: { orders: paid.length, revenue: rev, top, peakHour: peak?.[0] ?? null }, audio: await speak(text, "en-IN") });
    }

    // owner: this shop's code (to pair more devices), plan limits and today's usage
    if (url.pathname === "/api/shop-info") { const meta = isMulti() ? getShop(cur().id) : null; return json(res, 200, { name: menu.shop, code: meta?.code || null, multi: isMulti(), phone: phoneInfo(meta?.code), usage: usageView(cur()) }); }
    return json(res, 404, { error: "not found" });
  } catch (e) {
    if (e.invalidAudio) return json(res, 400, { error: e.message });
    if (e.limited) return json(res, 429, { error: e.message });
    if (e instanceof SyntaxError) return json(res, 400, { error: "bad request" });
    console.error(e); json(res, 500, { error: e.message });
  }
}

async function serveStatic(res, pathname) {
  const path = pathname === "/" ? "/index.html" : ["/counter", "/kitchen", "/owner", "/admin"].includes(pathname) ? pathname + ".html" : pathname;
  if (path.includes("..")) return json(res, 400, {});
  try { const buf = await readFile(new URL("./public" + path, import.meta.url)); res.writeHead(200, { "Content-Type": types[extname(path)] || "application/octet-stream" }); res.end(buf); }
  catch (e) { if (e.code === "ENOENT") return json(res, 404, { error: "not found" }); throw e; }
}

// ---- gateway: static files, signup/join (multi-shop mode), then authenticate -> pick the shop -> run the app inside that shop's context
const clientIp = (req) => (process.env.TRUST_PROXY ? String(req.headers["x-forwarded-for"] || "").split(",")[0].trim() : "") || req.socket.remoteAddress || "?";
const summary = (id) => { const t = tenants.get(id), st = t || loadState(id) || {}, d0 = new Date().setHours(0, 0, 0, 0), list = (t ? t.orders : st.orders) || [], today = list.filter((o) => (o.paidAt || o.createdAt) >= d0 && o.status !== "cancelled");
  return { ordersToday: today.length, revenueToday: today.filter((o) => o.paidAt).reduce((s, o) => s + o.total, 0), ordersTotal: list.length, usage: (t ? t.usage : st.usage) || {}, loaded: !!t }; };
async function gateway(req, res) {
  const url = new URL(req.url, "http://x"), p = url.pathname;
  if (req.method === "OPTIONS") { res.writeHead(204, { ...CORS, ...(req.headers["access-control-request-private-network"] === "true" ? { "Access-Control-Allow-Private-Network": "true" } : {}) }); return res.end(); }
  try {
    if (p === "/api/health") return json(res, 200, { ok: true, multi: isMulti(), shopsLoaded: tenants.size });
    if (p === "/api/mode") return json(res, 200, { multi: isMulti(), types: Object.values(profiles).map((x) => ({ id: x.id, label: x.label })) });
    if (p.startsWith("/phone/")) return await handlePhoneTool(req, res, voiceHooks);
    if (!p.startsWith("/api/")) return await serveStatic(res, p);
    let tenant, role = "owner";
    if (isMulti()) {
      if (req.method === "POST" && p === "/api/shops") {                               // create a shop
        const b = JSON.parse((await body(req)).toString() || "{}");
        if (process.env.SIGNUP_KEY && b.key !== process.env.SIGNUP_KEY) return json(res, 403, { error: "signups need an invite key" });
        if (!signupAllowed(clientIp(req))) return json(res, 429, { error: "too many signups from this address, try later" });
        let shop; try { shop = await createShop(b); } catch (e) { return json(res, 400, { error: e.message }); }
        return json(res, 200, { shop: publicShop(shop), token: issue(shop.id, "owner"), role: "owner" });
      }
      if (req.method === "POST" && p === "/api/join") {                                // pair a device with a shop code (+ owner PIN for the owner role)
        const b = JSON.parse((await body(req)).toString() || "{}"), key = clientIp(req) + ":" + String(b.code || "").toUpperCase();
        if (locked(key)) return json(res, 429, { error: "too many wrong attempts, wait a few minutes" });
        const shop = shopByCode(b.code);
        if (!shop || !ROLES.includes(b.role) || (b.role === "owner" && !(await pinOkFor(shop, b.pin)))) { failed(key); return json(res, 403, { error: !shop ? "unknown shop code" : "wrong PIN" }); }
        cleared(key); return json(res, 200, { token: issue(shop.id, b.role, b.role === "owner" ? 30 : 90), role: b.role, shop: { name: shop.name, profile: shop.profile } });
      }
      if (p.startsWith("/api/admin/")) {                                               // platform operator view
        if (!process.env.ADMIN_KEY || req.headers["x-admin-key"] !== process.env.ADMIN_KEY) return json(res, 403, { error: "admin key required" });
        if (p === "/api/admin/shops") return json(res, 200, { shops: listShops().map((s) => ({ ...publicShop(s), ...summary(s.id) })), loaded: tenants.size });
        return json(res, 404, { error: "not found" });
      }
      const auth = req.headers.authorization || ""; const who = verify(auth.startsWith("Bearer ") ? auth.slice(7) : url.searchParams.get("t"));
      if (!who) return json(res, 401, { error: "sign in with a shop code" });
      if (!can(who.role, req.method, p)) return json(res, 403, { error: "this device role cannot do that" });
      const meta = getShop(who.shop); if (!meta) return json(res, 401, { error: "shop no longer exists" });
      role = who.role; tenant = loadTenant(who.shop, { name: meta.name, profile: meta.profile, plan: meta.plan });
    } else tenant = loadTenant("default");
    if (!tenant.hooks.ready) wire(tenant);
    return await runIn(tenant, () => app(req, res, url, role));
  } catch (e) {
    if (e.message === "request too large") return json(res, 413, { error: e.message });
    if (e instanceof SyntaxError) return json(res, 400, { error: "bad request" });
    console.error(e); if (!res.headersSent) json(res, 500, { error: e.message });
  }
}
const server = http.createServer(gateway);
server.listen(process.env.PORT || 3000, "0.0.0.0", () => console.log(`Jarvis for Shops on :${process.env.PORT || 3000}  mode=${isMulti() ? "multi-shop" : "single-shop"}  sarvam=${sarvamEnabled()}  parser=${process.env.PARSER || (process.env.LLAMA_URL ? "llama" : process.env.OLLAMA_URL ? "ollama" : "rules")}`));
// Phone orders: a telephony provider streams each call to /voice?shop=<code>&key=<VOICE_KEY> (see src/voice.js)
// VOICE_PORT puts the call endpoint on its own port, so a public tunnel can expose just that and not the whole shop API
const voiceServer = process.env.VOICE_PORT ? http.createServer((q, r) => (q.url.startsWith("/phone/") ? handlePhoneTool(q, r, voiceHooks) : (r.writeHead(404), r.end()))).listen(process.env.VOICE_PORT, "0.0.0.0") : server;
// Phone orders are tied to the shop codes in VOICE_SHOPS (comma separated). Empty = any shop (single-shop installs).
const phoneShopOk = (code) => { const list = (process.env.VOICE_SHOPS || "").split(",").map((s) => s.trim().toUpperCase()).filter(Boolean); return !list.length || list.includes(String(code || "").toUpperCase()); };
const phoneInfo = (code) => { const n = String(process.env.PHONE_AGENT_NUMBER || "").replace(/\D/g, ""); if (!n || !process.env.VOICE_KEY || (isMulti() && !phoneShopOk(code))) return null;
  return { number: n, on: `**61*${n}**15#`, busy: `**67*${n}#`, unreachable: `**62*${n}#`, off: "##61#" }; };
const voiceHooks = {
  tenantFor: async (code) => {
    let t;
    if (isMulti()) { if (!phoneShopOk(code)) return null; const shop = shopByCode(code); if (!shop) return null; t = loadTenant(shop.id, { name: shop.name, profile: shop.profile, plan: shop.plan }); }
    else t = loadTenant("default");
    if (!t.hooks.ready) wire(t);
    return t;
  },
  onOrder: (t, o) => runIn(t, () => {
    if (o.status === "scheduled") { emit("upcoming", { order: view(o), lang: KITCHEN_LANG, text: say(KITCHEN_LANG).phoneAhead(o.number, fmtClock(o.pickupAt), o.lines.map((l) => lineText(l, true)).join(", "), Math.round(phoneLeadMs() / 60e3)) }); emit("update"); return; }   // booked well ahead: tell the kitchen now, start it later
    dispatch(); emit("order", { order: view(o), text: slipText(o) });
  }),
};
attachVoice(voiceServer, voiceHooks);

// idle shops are flushed and unloaded so memory follows ACTIVE shops, not registered ones
if (isMulti()) setInterval(() => { const idle = +process.env.TENANT_IDLE_MS || 15 * 60e3; for (const [id, t] of tenants) if (Date.now() - t.lastUsed > idle && t.clients.size === 0 && !runIn(t, () => orders.some((o) => o.status === "scheduled"))) unloadTenant(id); }, 60e3).unref();   // a shop with phone orders waiting stays loaded so they start on time
// Start scheduled phone orders when their time comes: put them in the kitchen and remind everyone.
setInterval(() => { for (const t of tenants.values()) runIn(t, () => { const due = startDue(); if (!due.length) return; for (const o of due) emit("reminder", { order: view(o), lang: KITCHEN_LANG, text: say(KITCHEN_LANG).phoneRemind(o.number, fmtClock(o.pickupAt)) }); dispatch(); }); }, 15000).unref();
// load the shops that take phone orders at boot, so orders scheduled before a restart still start on time
(async () => { if (!process.env.VOICE_KEY) return; if (!isMulti()) return void voiceHooks.tenantFor(""); for (const s of listShops()) if (phoneShopOk(s.code)) await voiceHooks.tenantFor(s.code); })().catch((e) => console.error("phone shop preload failed:", e.message));

function pricedView(cart) { const p = priceCart(cart); return { lines: p.lines, total: p.total }; }

for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => { flushAll(); process.exit(0); });
