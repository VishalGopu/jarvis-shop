import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
import { EventEmitter } from "node:events";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "jarvis-phone-")); process.env.SHOP = "kirana"; process.env.PARSER = "rules"; process.env.VOICE_KEY = "test-key";
const { handlePhoneTool } = await import("../src/voice.js"), { loadTenant, orders } = await import("../src/orders.js");

const hooks = { tenantFor: async () => loadTenant("default"), onOrder: () => { hooks.notified = (hooks.notified || 0) + 1; } };
async function call(method, path, body, key = "test-key") {
  const req = new EventEmitter(); req.method = method; req.url = path; req.headers = key ? { authorization: "Bearer " + key } : {};
  let status, out; const res = { writeHead: (c) => (status = c), end: (t) => (out = JSON.parse(t)) };
  const done = handlePhoneTool(req, res, hooks);
  if (body !== undefined) req.emit("data", Buffer.from(JSON.stringify(body))); req.emit("end"); await done;
  return { status, out };
}

assert.equal((await call("GET", "/phone/menu?shop=X", undefined, null)).status, 401, "no key");
assert.equal((await call("GET", "/phone/menu?shop=X", undefined, "wrong")).status, 401, "wrong key");
const menu = await call("GET", "/phone/menu?shop=X"); assert.ok(menu.out.ok && /Rice/.test(menu.out.message));
const chk = await call("POST", "/phone/check", { shop: "X", order_text: "two kilo rice and one packet maggi" });
assert.ok(chk.out.ok); assert.equal(chk.out.total, 134); assert.equal(orders.length, 0, "check never books");
const nope = await call("POST", "/phone/book", { shop: "X", order_text: "two kilo rice" });
assert.equal(nope.out.ok, false); assert.equal(nope.out.needs, "pickup_time"); assert.equal(orders.length, 0, "no pickup time, no order");
const bk = await call("POST", "/phone/book", { shop: "X", order_text: "two kilo rice", pickup_in_minutes: 25, caller: "+919876543210", call_id: "c1" });
assert.ok(bk.out.ok); assert.equal(orders.length, 1); const o = orders[0];
assert.equal(o.source, "phone"); assert.equal(o.status, "cooking"); assert.equal(o.total, 120); assert.equal(o.caller, "+919876543210");
assert.ok(Math.abs(o.pickupAt - (Date.now() + 25 * 60e3)) < 5000); assert.equal(hooks.notified, 1);
const again = await call("POST", "/phone/book", { shop: "X", order_text: "two kilo rice", pickup_in_minutes: 25, call_id: "c1" });
assert.equal(again.out.order_number, o.number); assert.equal(orders.length, 1, "a retried tool call does not book twice");
const txt = await call("POST", "/phone/book", { shop: "X", order_text: "one kilo sugar", pickup_text: "in half an hour", call_id: "c2" });
assert.ok(txt.out.ok); assert.equal(orders.length, 2);
assert.equal((await call("POST", "/phone/check", { shop: "X", order_text: "flying carpet" })).out.ok, false, "unknown items are not invented");
// booked well ahead: it waits, the kitchen is told now (onOrder), and it starts 30 min before pickup
const { startDue } = await import("../src/orders.js"); const before = hooks.notified;
const late = await call("POST", "/phone/book", { shop: "X", order_text: "one kilo sugar", pickup_in_minutes: 120, call_id: "c3" });
assert.ok(late.out.ok); const l = orders.find((x) => x.number === late.out.order_number);
assert.equal(l.status, "scheduled", "a 2 h pickup is held"); assert.equal(hooks.notified, before + 1, "kitchen is told straight away");
assert.ok(Math.abs(l.startAt - (l.pickupAt - 30 * 60e3)) < 1000, "starts 30 min before pickup"); assert.equal(l.startedAt, null);
assert.equal(startDue(Date.now() + 60 * 60e3).length, 0, "not yet, 60 min on is still too early"); assert.equal(l.status, "scheduled");
assert.deepEqual(startDue(Date.now() + 91 * 60e3).map((x) => x.number), [l.number], "due once 90 min have passed");
assert.equal(l.status, "cooking"); assert.ok(l.startedAt); assert.equal(startDue(Date.now() + 200 * 60e3).length, 0, "starts only once");
console.log("phone tools ok");
