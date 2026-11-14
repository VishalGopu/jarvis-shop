// Order state machine + FIFO pickup matching, persisted to data/state.json (atomic write).
import { readFileSync, readdirSync } from "node:fs";
const PROFILE_DIR = new URL("../profiles/", import.meta.url);
export const profiles = Object.fromEntries(readdirSync(PROFILE_DIR).filter((f) => f.endsWith(".json")).map((f) => { const p = JSON.parse(readFileSync(new URL(f, PROFILE_DIR))); return [p.id, p]; }));
import { cur, proxyOf, setDefaultFactory } from "./tenant.js";
import { loadState, saveState } from "./store.js";
const r2 = (x) => Math.round(x * 100) / 100;

// ---- per-shop runtime state. Everything a shop owns lives in one object; the exports below are proxies to the CURRENT shop (see tenant.js).
export const tenants = new Map();                            // id -> loaded shop state (idle shops are unloaded by the server)
function activate(t, id) {                                    // the shop's active profile: catalog + fulfilment mode (kitchen | pack | instant) + payment timing (before | after) + vocabulary
  const p = profiles[id]; for (const k of Object.keys(t.menu)) delete t.menu[k];
  Object.assign(t.menu, structuredClone(p), { items: structuredClone(t.catalogs[id] || p.items) }); if (t.name) t.menu.shop = t.name;
  t.byId = Object.fromEntries(t.menu.items.map((i) => [i.id, i]));
}
export function loadTenant(id, meta = {}) {
  let t = tenants.get(id); if (t) { t.lastUsed = Date.now(); if (meta.plan) t.plan = meta.plan; return t; }
  const saved = loadState(id) || {};
  t = { id, plan: meta.plan || {}, name: meta.name || saved.name || null, orders: saved.orders || [], soldOut: new Set(saved.soldOut || []), kitchen: { mode: "station", chefs: [], ...(saved.kitchen || {}) }, menu: {}, byId: {},
    catalogs: saved.catalog || {}, seq: 100, timer: null, hooks: { ready: null }, usage: saved.usage || {}, clients: new Set(), lastUsed: Date.now() };   // clients (live SSE) is runtime only
  t.seq = t.orders.reduce((m, o) => Math.max(m, o.number), 100);
  activate(t, profiles[saved.shop] ? saved.shop : profiles[meta.profile] ? meta.profile : profiles[process.env.SHOP] ? process.env.SHOP : "canteen");
  if (t.menu.fulfil === "pack") t.kitchen.mode = "order";   // a picker takes whole slips
  t.kitchen.chefs.forEach((c) => { c.also ||= []; if (c.helpAny == null) c.helpAny = true; });
  tenants.set(id, t); return t;
}
setDefaultFactory(() => loadTenant("default"));
export const menu = proxyOf(() => cur().menu);
export const orders = proxyOf(() => cur().orders, []);
export const soldOut = proxyOf(() => cur().soldOut);
export const kitchenState = proxyOf(() => cur().kitchen);   // chefs: {id,name,lang,skills[],online,task:{number,lineIds,since}|null}
export const hooks = proxyOf(() => cur().hooks);              // called when an order becomes fully ready
const byId = proxyOf(() => cur().byId);
export const itemById = (id) => cur().byId[id];
export const isOut = (id) => soldOut.has(id) || (byId[id]?.stock != null && byId[id].stock <= 0);

export function flushTenant(t) { clearTimeout(t.timer); saveState(t.id, { orders: t.orders, soldOut: [...t.soldOut], kitchen: t.kitchen, shop: t.menu.id, name: t.name, catalog: { ...t.catalogs, [t.menu.id]: t.menu.items }, usage: t.usage }); }
export const flush = () => flushTenant(cur());
export const flushAll = () => { for (const t of tenants.values()) flushTenant(t); };
export function unloadTenant(id) { const t = tenants.get(id); if (!t) return; flushTenant(t); tenants.delete(id); }
export const persist = () => { const t = cur(); clearTimeout(t.timer); t.timer = setTimeout(() => flushTenant(t), 150); };

// Quantities are in the item's own unit (kg, litre, packet, plate...). Count items are whole numbers; weights/volumes keep 2 decimals.
export function clampQty(m, q) { q = Number(q); if (!(q > 0)) return 0; const max = m.max || (m.step && m.step < 1 ? 50 : 20); return Math.min(max, m.step >= 1 || !m.step ? Math.max(1, Math.round(q)) : Math.max(0.05, r2(q))); }
export function priceCart(rawItems) {
  const lines = [];
  for (const it of rawItems || []) {
    const m = byId[it.id]; const qty = m && clampQty(m, it.qty); if (!m || !qty) continue;
    lines.push({ id: m.id, name: m.name, qty, price: m.price, unit: m.unit, amount: Math.round(qty * m.price), ...(it.note ? { note: String(it.note).slice(0, 60) } : {}) });
  }
  return { lines, total: lines.reduce((s, l) => s + l.amount, 0) };
}
export const startWork = (o, at = Date.now()) => { o.status = "cooking"; o.startedAt = at; };
export function createOrder(cart, lang = "en-IN", table = "") {
  const { lines, total } = priceCart(cart);
  if (!lines.length) throw new Error("empty cart");
  const gone = lines.filter((l) => isOut(l.id)).map((l) => l.name);
  if (gone.length) throw new Error("sold out: " + gone.join(", "));
  const short = lines.find((l) => byId[l.id].stock != null && l.qty > byId[l.id].stock); if (short) throw new Error(`only ${byId[short.id].stock} ${byId[short.id].unit || ""} ${short.name} left`.replace(/\s+/g, " "));
  lines.forEach((l) => { const m = byId[l.id]; if (m.stock != null) m.stock = r2(m.stock - l.qty); });   // reserve stock for this order
  const num = ++cur().seq;
  const o = { id: `ORD${num}`, number: num, pin: String(1000 + Math.floor(Math.random() * 9000)), shop: menu.id, lines, total, lang, status: "awaiting_payment", createdAt: Date.now(), startedAt: null, paidAt: null, readyAt: null, deliveredAt: null, ...(table ? { table: String(table).slice(0, 8) } : {}) };
  if (menu.payWhen === "after") startWork(o);               // restaurant / kirana: the slip goes to the team now, the customer pays at the end
  orders.push(o); persist(); return o;
}
// pay-first shops: payment confirmed -> work starts (or, with no kitchen, the order is simply handed over)
export function markPaid(o) {
  if (o.status !== "awaiting_payment") return o; o.paidAt = Date.now();
  if (menu.fulfil === "instant") { o.status = "delivered"; o.startedAt = o.readyAt = o.deliveredAt = o.paidAt; } else startWork(o, o.paidAt);
  persist(); return o;
}
// pay-after shops: the finished order is billed at the counter; paying (UPI verified, or cash) completes the hand-over
export function settle(o, method = "upi") {
  if (o.status !== "ready" || o.paidAt) return null; o.paidAt = Date.now(); o.method = method; o.status = "delivered"; o.deliveredAt = o.paidAt; persist(); return o;
}
const live = (st) => ["awaiting_payment", "scheduled", "cooking", "ready"].includes(st);
// Phone orders booked well ahead wait as "scheduled" and start cooking PHONE_LEAD_MIN (default 30) minutes before pickup.
export const phoneLeadMs = () => { const m = Number(process.env.PHONE_LEAD_MIN); return (process.env.PHONE_LEAD_MIN !== undefined && process.env.PHONE_LEAD_MIN !== "" && Number.isFinite(m) && m >= 0 ? m : 30) * 60e3; };
/** Holds a just-created order until `lead` before its pickup time. Returns false (and changes nothing) when pickup is close enough to start now. */
export function holdForPickup(o, at, now = Date.now()) { const lead = phoneLeadMs(); if (at - now <= lead) return false; o.status = "scheduled"; o.startedAt = null; o.startAt = at - lead; persist(); return true; }
/** Moves scheduled orders whose start time has come into the kitchen. Returns them. */
export function startDue(now = Date.now()) { const due = orders.filter((o) => o.status === "scheduled" && (o.startAt || 0) <= now); due.forEach((o) => { o.status = "cooking"; o.startedAt = now; }); if (due.length) persist(); return due; }
export function setShop(id) {
  if (!profiles[id]) return { error: "unknown shop type" };
  if (id === menu.id) return { ok: true };
  if (orders.some((o) => live(o.status))) return { error: "finish or cancel the open orders first" };
  const t = cur(); t.catalogs[menu.id] = menu.items; activate(t, id);
  kitchenState.chefs.forEach((c) => { c.skills = c.skills.filter((d) => byId[d]); c.also = (c.also || []).filter((d) => byId[d]); });
  if (menu.fulfil === "pack") kitchenState.mode = "order";
  persist(); return { ok: true };
}
const slug = (n) => String(n).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 24) || "item";
export function setCatalog(list) {
  if (!Array.isArray(list) || !list.length) return { error: "catalog is empty" };
  const out = [], used = new Set();
  for (const x of list.slice(0, 200)) {
    const name = String(x.name || "").trim().slice(0, 40); if (!name) continue;
    const old = x.id && byId[x.id]; let id = old ? old.id : slug(name); while (used.has(id) || (!old && byId[id])) id += "_"; used.add(id);
    const unit = String(x.unit || old?.unit || "piece").slice(0, 10), step = Number(x.step) > 0 ? Number(x.step) : old?.step || 1;
    out.push({ ...(old || {}), id, name, unit, step, price: Math.max(0, Math.round(Number(x.price) || 0)), stock: x.stock === "" || x.stock == null ? null : Math.max(0, r2(Number(x.stock) || 0)),
      aliases: [...new Set((Array.isArray(x.aliases) ? x.aliases : String(x.aliases || "").split(",")).map((a) => String(a).trim()).filter(Boolean))].slice(0, 12) });
  }
  if (!out.length) return { error: "no valid items" };
  menu.items = out; cur().byId = Object.fromEntries(out.map((i) => [i.id, i]));
  kitchenState.chefs.forEach((c) => { c.skills = c.skills.filter((d) => byId[d]); c.also = (c.also || []).filter((d) => byId[d]); });
  persist(); return { ok: true, items: out };
}

// ---- Kitchen readiness. Each line tracks `done` (qty ready); an order is "ready" when every line is done.
const need = (l) => r2(l.qty - (l.done || 0));
const cooking = () => orders.filter((o) => o.status === "cooking").sort((a, b) => (a.startedAt || a.paidAt) - (b.startedAt || b.paidAt));
export function finish(o) { if (o.status === "cooking" && o.lines.every((l) => need(l) <= 0)) { o.status = "ready"; o.readyAt = Date.now(); hooks.ready?.(o); return true; } return false; }
// whole order ready ("order 104 ready" / Order ready button)
export function markOrderReady(number) { const o = cooking().find((x) => x.number === number); if (!o) return null; o.lines.forEach((l) => (l.done = l.qty)); finish(o); persist(); return { order: o, completed: true }; }
// one line of one order (tap a dish on the card); qty defaults to everything still outstanding on that line
export function markLine(number, lineId, qty) { const o = cooking().find((x) => x.number === number); const l = o?.lines.find((x) => x.id === lineId); if (!l || need(l) <= 0) return null; l.done = r2((l.done || 0) + Math.min(need(l), qty > 0 ? qty : need(l))); const completed = finish(o); persist(); return { order: o, completed }; }
// "dosa ready" / "5 dosa ready": give the dish to the OLDEST paid orders that still need it, FIFO. No qty = oldest order's need only.
export function allocateDish(dishId, qty = null) {
  const out = []; let left = qty;
  for (const o of cooking()) {
    const l = o.lines.find((x) => x.id === dishId && need(x) > 0); if (!l) continue;
    const give = left == null ? need(l) : Math.min(need(l), left); l.done = r2((l.done || 0) + give);
    out.push({ order: o, completed: finish(o), dish: dishId, given: give });
    if (left == null) break; left -= give; if (left <= 0) break;
  }
  if (out.length) persist(); return out;
}
const WORDS = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };
// chef speech (English via STT translate) -> order numbers mentioned + dishes (with optional qty)
export function parseKitchen(text) {
  const t = (text || "").toLowerCase();
  const numbers = [...t.matchAll(/\b(\d{3,5})\b/g)].map((m) => +m[1]).filter((n) => orders.some((o) => o.number === n));
  const dishes = [];
  for (const m of menu.items) for (const a of [m.name, ...m.aliases]) {
    const i = t.indexOf(a.toLowerCase()); if (i < 0) continue;
    const q = t.slice(Math.max(0, i - 12), i).match(/(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten)\s*$/);
    dishes.push({ id: m.id, qty: q ? parseInt(q[1]) || WORDS[q[1]] : null }); break;
  }
  return { numbers, dishes };
}
const restock = (o) => o.lines.forEach((l) => { const m = byId[l.id]; if (m?.stock != null && !l.unavailable) m.stock = r2(m.stock + l.qty); });
// cancel: always while unpaid; in pay-after shops also while the team is still working on it
/** Phone orders that were never paid free their stock after ttl. Returns the cancelled orders. */
export function expireUnpaidPhone(now = Date.now(), ttl = (Number(process.env.PAY_TTL_MIN) || 15) * 60e3) {
  const gone = orders.filter((o) => o.source === "phone" && o.status === "awaiting_payment" && now - o.createdAt > ttl);
  gone.forEach((o) => { o.status = "cancelled"; restock(o); }); if (gone.length) persist(); return gone;
}
/** True when this order is paid at the counter at pickup: every order in pay-after shops, and phone orders in any shop. */
export const owesAtPickup = (o) => menu.payWhen === "after" || !!o.payLater;
export function cancelOrder(id) { const o = orders.find((x) => x.id === id); if (!o || !(o.status === "awaiting_payment" || (owesAtPickup(o) && (o.status === "cooking" || o.status === "scheduled")))) return null; o.status = "cancelled"; restock(o); persist(); return o; }
// every order has a 4-digit pickup code the customer is given when ordering; the counter checks it before handing over (or billing, for orders paid at pickup)
export const pinOk = (num, pin) => { const o = orders.find((x) => x.number === num); return !o || !o.pin || String(pin || "").trim() === o.pin; };
export function deliver(num) { const o = orders.find((x) => x.number === num && x.status === "ready"); if (o && (!owesAtPickup(o) || o.paidAt)) { o.status = "delivered"; o.deliveredAt = Date.now(); persist(); return o; } return null; }
// picker cannot find an item: drop it from the bill (only where payment comes after packing)
export function markUnavailable(number, lineId) {
  if (menu.payWhen !== "after") return { error: "bill already paid" };
  const o = cooking().find((x) => x.number === number); const l = o?.lines.find((x) => x.id === lineId); if (!l || l.unavailable) return { error: "no such item" };
  const m = byId[l.id]; if (m?.stock != null) m.stock = r2(m.stock + (l.qty - (l.done || 0)));
  l.unavailable = true; l.done = l.qty; l.amount = 0; l.chefId = null; o.total = o.lines.reduce((s, x) => s + (x.unavailable ? 0 : x.amount ?? x.qty * x.price), 0);
  const completed = finish(o); persist(); return { order: o, completed };
}
export function setSoldOut(id, v) { if (!byId[id]) return false; v ? soldOut.add(id) : soldOut.delete(id); persist(); return true; }
export function findDish(text) {
  const t = (text || "").toLowerCase();
  for (const m of menu.items) if ([m.name, ...m.aliases].some((a) => t.includes(a.toLowerCase()))) return m.id;
  return null;
}
