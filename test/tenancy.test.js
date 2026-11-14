import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "chitragupta-multi-")); process.env.TENANCY = "multi"; process.env.TOKEN_SECRET = "test-secret";
const { runIn, cur } = await import("../src/tenant.js");
const O = await import("../src/orders.js"), T = await import("../src/tenants.js"), U = await import("../src/usage.js"), { limiter } = await import("../src/limiter.js"), K = await import("../src/kitchen.js"), I = await import("../src/insights.js");

// ---- registry, PIN, tokens, roles
await assert.rejects(() => T.createShop({ name: "x", profile: "kirana", pin: "1234" }), /name/);
await assert.rejects(() => T.createShop({ name: "Shop", profile: "nope", pin: "1234" }), /type/);
await assert.rejects(() => T.createShop({ name: "Shop", profile: "kirana", pin: "12" }), /PIN/);
const a = await T.createShop({ name: "Ravi Kirana", profile: "kirana", pin: "4321" }), b = await T.createShop({ name: "Campus Chai", profile: "canteen", pin: "9999" });
assert.notEqual(a.code, b.code); assert.equal(T.shopByCode(a.code.toLowerCase()).id, a.id);
assert.equal(await T.pinOkFor(a, "4321"), true); assert.equal(await T.pinOkFor(a, "0000"), false); assert.ok(!JSON.stringify(T.publicShop(a)).includes(a.pinHash));
const tok = T.issue(a.id, "counter"); assert.deepEqual(T.verify(tok), { shop: a.id, role: "counter" });
assert.equal(T.verify(tok.slice(0, -2) + "xx"), null, "tampered signature"); assert.equal(T.verify(T.issue(a.id, "counter", -1)), null, "expired");
const forged = Buffer.from(JSON.stringify({ s: b.id, r: "owner", exp: Date.now() + 1e9 })).toString("base64url") + "." + tok.split(".")[1]; assert.equal(T.verify(forged), null, "payload swapped under old signature");
assert.ok(T.can("owner", "POST", "/api/catalog")); assert.ok(!T.can("counter", "GET", "/api/insights")); assert.ok(T.can("counter", "POST", "/api/turn")); assert.ok(!T.can("counter", "POST", "/api/ready"));
assert.ok(T.can("kitchen", "POST", "/api/ready")); assert.ok(!T.can("kitchen", "POST", "/api/catalog")); assert.ok(!T.can("kitchen", "GET", "/api/kitchen/config")); assert.ok(T.can("kitchen", "POST", "/api/order/ORD101/cash"));
const k = "1.2.3.4:" + a.code; for (let i = 0; i < 5; i++) T.failed(k); assert.equal(T.locked(k), true); T.cleared(k); assert.equal(T.locked(k), false);

// ---- isolation: two shops in one process
assert.throws(() => cur(), /no shop/, "no ambient shop in multi mode");
const A = O.loadTenant(a.id, { name: a.name, profile: a.profile }), B = O.loadTenant(b.id, { name: b.name, profile: b.profile });
const inA = (f) => runIn(A, f), inB = (f) => runIn(B, f);
assert.equal(inA(() => O.menu.id), "kirana"); assert.equal(inB(() => O.menu.id), "canteen"); assert.equal(inA(() => O.menu.shop), "Ravi Kirana");
const rice0 = inA(() => O.itemById("rice").stock);
const oa = inA(() => O.createOrder([{ id: "rice", qty: 2 }])); const ob = inB(() => { const o = O.createOrder([{ id: "chai", qty: 1 }]); O.markPaid(o); return o; });
assert.equal(inA(() => O.orders.length), 1); assert.equal(inB(() => O.orders.length), 1); assert.equal(oa.number, 101); assert.equal(ob.number, 101, "each shop numbers its own orders");
assert.equal(inA(() => O.itemById("rice").stock), rice0 - 2); assert.equal(inB(() => O.itemById("rice")), undefined, "B has no rice");
assert.equal(inB(() => O.priceCart([{ id: "rice", qty: 1 }]).lines.length), 0);
inA(() => K.setKitchen({ chefs: [{ name: "Picker" }] })); assert.equal(inB(() => K.kitchenView().chefs.length), 0);
assert.ok(inA(() => I.facts().shop) === "Ravi Kirana" && inB(() => I.facts().today.orders) === 1 && inA(() => I.facts().today.orders) === 0);
// interleaved async work never leaks between shops
await Promise.all([A, B].flatMap((t, n) => Array.from({ length: 30 }, (_, i) => runIn(t, async () => { await new Promise((r) => setTimeout(r, Math.random() * 5)); const id = n ? "chai" : "maggi"; O.createOrder([{ id, qty: 1 }]); await new Promise((r) => setTimeout(r, Math.random() * 5)); assert.equal(O.menu.id, n ? "canteen" : "kirana"); }))));
assert.equal(inA(() => O.orders.length), 31); assert.equal(inB(() => O.orders.length), 31);
assert.match(inA(() => O.setShop("canteen")).error, /open orders/);

// ---- persistence per shop + unloading idle shops
const stockA = inA(() => O.itemById("rice").stock); inA(() => O.setCatalog([{ id: "rice", name: "Rice", price: 70, unit: "kg", step: 0.5, stock: stockA }]));
O.unloadTenant(a.id); assert.ok(!O.tenants.has(a.id));
const A2 = O.loadTenant(a.id, { name: a.name, profile: a.profile }); assert.notEqual(A2, A);
assert.equal(runIn(A2, () => O.orders.length), 31); assert.equal(runIn(A2, () => O.itemById("rice").price), 70); assert.equal(runIn(A2, () => O.itemById("rice").stock), stockA);
assert.equal(runIn(A2, () => O.createOrder([{ id: "rice", qty: 1 }]).number), 132, "numbering continues after reload");

// ---- usage quotas per shop
const C = O.loadTenant("quota-test", { profile: "canteen", plan: { sttPerDay: 2 } });
runIn(C, () => { assert.ok(U.allowed("stt")); U.track("stt"); U.track("stt"); assert.equal(U.allowed("stt"), false); assert.equal(U.usageView(cur()).today.stt, 2); });
assert.equal(runIn(B, () => U.allowed("stt")), true, "another shop is unaffected");

// ---- model gate: bounded concurrency and queue
const gate = limiter(2, 1); const slow = () => new Promise((r) => setTimeout(() => r("ok"), 30));
const res = await Promise.allSettled(Array.from({ length: 5 }, () => gate(slow))); assert.equal(res.filter((x) => x.status === "fulfilled").length, 3); assert.equal(res.filter((x) => x.status === "rejected").length, 2);
O.flushAll(); console.log("tenancy ok");
