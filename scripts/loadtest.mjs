// Load test: ONE backend process, many shops. Usage: node scripts/loadtest.mjs [shops=200] [ordersPerShop=20]
// Starts the server in multi-shop mode (rules parser, no external speech/model calls, so this measures the backend itself), creates the shops,
// pairs devices, opens live (SSE) connections, then every shop runs full order cycles in parallel. Checks data isolation at the end.
import { spawn, execSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
const SHOPS = +process.argv[2] || 200, ORDERS = +process.argv[3] || 20, PORT = 3090 + Math.floor(Math.random() * 500), B = `http://localhost:${PORT}`, DATA = mkdtempSync(join(tmpdir(), "chitragupta-load-"));
const env = { PATH: process.env.PATH, TENANCY: "multi", DATA_DIR: DATA, PORT: String(PORT), PARSER: "rules", ADMIN_KEY: "adm", SIGNUPS_PER_HOUR: "100000", TOKEN_SECRET: "load" };
const srv = spawn("node", [...(process.env.PROF ? ["--cpu-prof", "--cpu-prof-dir=/tmp/jprof"] : []), "server.js"], { env, stdio: ["ignore", "pipe", "inherit"] });
await new Promise((ok) => srv.stdout.on("data", (d) => /Chitragupta for Shops/.test(String(d)) && ok()));
const rss = () => Math.round(+execSync(`ps -o rss= -p ${srv.pid}`).toString().trim() / 1024);
const lat = {}; let errors = 0, reqs = 0;
async function call(name, method, path, tok, body) {
  const t0 = performance.now(); reqs++;
  const r = await fetch(B + path, { method, headers: { "Content-Type": "application/json", ...(tok ? { Authorization: "Bearer " + tok } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json(); (lat[name] ||= []).push(performance.now() - t0); if (!r.ok) { errors++; if (errors < 5) console.log("error", name, r.status, JSON.stringify(j)); } return j;
}
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; };
console.log(`server up (pid ${srv.pid}, ${rss()} MB). creating ${SHOPS} shops…`);

// ---- setup: create shops, pair counter + kitchen devices, one picker/chef each, untracked stock so the test is not limited by inventory
let t0 = performance.now(); const shops = [];
await Promise.all(Array.from({ length: SHOPS }, async (_, i) => {
  const kind = i % 2 ? "kirana" : "canteen"; const c = await call("signup", "POST", "/api/shops", null, { name: `Shop ${i} ${kind}`, profile: kind, pin: "1234" });
  const cnt = await call("join", "POST", "/api/join", null, { code: c.shop.code, role: "counter" }), kit = await call("join", "POST", "/api/join", null, { code: c.shop.code, role: "kitchen" });
  const cfg = await call("config", "GET", "/api/config", c.token); await call("catalog", "POST", "/api/catalog", c.token, { items: cfg.menu.items.map((x) => ({ ...x, stock: null })) });
  await call("kitchen-config", "POST", "/api/kitchen/config", c.token, { mode: kind === "kirana" ? "order" : "station", chefs: [{ name: "Staff", lang: "en-IN" }] }); await call("chef-online", "POST", "/api/chefs/c1/online", kit.token, { online: true });
  shops.push({ i, kind, id: c.shop.id, owner: c.token, counter: cnt.token, kitchen: kit.token, done: 0 });
}));
console.log(`setup ${((performance.now() - t0) / 1000).toFixed(1)}s, ${rss()} MB after ${SHOPS} shops loaded`);

// ---- live connections: 3 devices per shop hold an SSE stream open
let events = 0; const aborts = [];
for (const s of shops) for (const tok of [s.owner, s.counter, s.kitchen]) { const ac = new AbortController(); aborts.push(ac);
  fetch(`${B}/api/events?t=${tok}`, { signal: ac.signal }).then(async (r) => { const rd = r.body.getReader(); for (;;) { const { done, value } = await rd.read(); if (done) break; events += (String(Buffer.from(value)).match(/\ndata: /g) || []).length; } }).catch(() => {}); }
await new Promise((r) => setTimeout(r, 500)); console.log(`${shops.length * 3} live connections open, ${rss()} MB`);

// ---- workload: every shop runs ORDERS full cycles, all shops in parallel
async function cycle(s) {
  const say = s.kind === "kirana" ? "one kilo rice and half kilo sugar, ripe tomatoes 2 kg, that's all" : "two chai and a samosa, that's all";
  const turn = await call("turn", "POST", "/api/turn", s.counter, { text: say, cart: [], lang: "en-IN" });
  const co = await call("checkout", "POST", "/api/checkout", s.counter, { cart: turn.cart, lang: "en-IN" }); const o = co.order;
  if (s.kind === "canteen") { await call("mock-pay", "POST", `/api/order/${o.id}/mock-pay`, s.counter, {}); const st = await call("order-status", "GET", `/api/order/${o.id}`, s.counter);
    await call("ready", "POST", "/api/ready", s.kitchen, { number: o.number }); await call("deliver", "POST", "/api/deliver", s.kitchen, { number: o.number, pin: st.pin }); }
  else { await call("ready", "POST", "/api/ready", s.kitchen, { number: o.number }); await call("cash", "POST", `/api/order/${o.id}/cash`, s.counter, {}); }
  if (s.i % 10 === 0 && s.done % 5 === 0) { await call("kitchen-board", "GET", "/api/kitchen", s.kitchen); await call("insights", "GET", "/api/insights", s.owner); }
  s.done++;
}
t0 = performance.now(); let peak = 0; const mem = setInterval(() => (peak = Math.max(peak, rss())), 300);
await Promise.all(shops.map(async (s) => { for (let n = 0; n < ORDERS; n++) await cycle(s); }));
const secs = (performance.now() - t0) / 1000; clearInterval(mem);

// ---- isolation check: every shop must hold exactly its own orders (and nobody else's)
const adm = await (await fetch(B + "/api/admin/shops", { headers: { "x-admin-key": "adm" } })).json();
const byId = Object.fromEntries(adm.shops.map((s) => [s.id, s])); const leaks = shops.filter((s) => byId[s.id].ordersTotal !== ORDERS).length;
const revBad = shops.filter((s) => s.kind === "canteen" ? byId[s.id].revenueToday !== ORDERS * 35 : byId[s.id].revenueToday <= 0).length;
console.log(`\n${SHOPS} shops × ${ORDERS} orders = ${SHOPS * ORDERS} orders · ${reqs} requests in ${secs.toFixed(1)}s → ${(reqs / secs).toFixed(0)} req/s, ${(SHOPS * ORDERS / secs).toFixed(1)} orders/s`);
console.log(`memory: peak ${peak} MB for ${SHOPS} shops (${(peak / SHOPS).toFixed(2)} MB/shop) · live events delivered: ${events} · HTTP errors: ${errors}`);
console.log(`isolation: ${leaks} shops with the wrong order count, ${revBad} with wrong revenue`);
console.log("latency ms (p50 / p95 / p99 / max):"); for (const [k, v] of Object.entries(lat).sort()) console.log(`  ${k.padEnd(15)} n=${String(v.length).padStart(6)}  ${pct(v, .5).toFixed(1).padStart(7)} ${pct(v, .95).toFixed(1).padStart(7)} ${pct(v, .99).toFixed(1).padStart(7)} ${Math.max(...v).toFixed(1).padStart(8)}`);
aborts.forEach((a) => a.abort()); srv.kill("SIGTERM"); await new Promise((r) => setTimeout(r, 300)); rmSync(DATA, { recursive: true, force: true });
process.exit(errors || leaks || revBad ? 1 : 0);
