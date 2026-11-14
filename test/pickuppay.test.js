import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
import { EventEmitter } from "node:events";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "chitragupta-pickuppay-")); process.env.SHOP = "tiffin"; process.env.PARSER = "rules"; process.env.VOICE_KEY = "k";
const { handlePhoneTool } = await import("../src/voice.js"), O = await import("../src/orders.js");
assert.equal(O.menu.payWhen, "before", "counter orders in this shop are paid when ordering");
const hooks = { tenantFor: async () => O.loadTenant("default"), onOrder: () => { hooks.notified = (hooks.notified || 0) + 1; } };
async function book(body) {
  const req = new EventEmitter(); req.method = "POST"; req.url = "/phone/book"; req.headers = { authorization: "Bearer k" };
  let out; const res = { writeHead() {}, end: (t) => (out = JSON.parse(t)) }; const done = handlePhoneTool(req, res, hooks); req.emit("data", Buffer.from(JSON.stringify(body))); req.emit("end"); await done; return out;
}
// a counter order is pay-first: it waits for payment and is not "pay later"
const walkin = O.createOrder([{ id: "idli", qty: 1 }]); assert.equal(walkin.status, "awaiting_payment"); assert.ok(!walkin.payLater);
// a phone order goes straight to the kitchen and is paid at the counter when collected
const r = await book({ shop: "X", order_text: "two idli", pickup_in_minutes: 20, caller: "09059158791", call_id: "q1" });
assert.ok(r.ok, JSON.stringify(r)); assert.match(r.message, /pays at the counter/); const o = O.orders.find((x) => x.number === r.order_number);
assert.equal(o.status, "cooking"); assert.equal(o.payLater, true); assert.equal(o.paidAt, null); assert.equal(hooks.notified, 1, "kitchen told at booking");
// far-ahead phone order is held, then starts
const far = await book({ shop: "X", order_text: "one vada", pickup_in_minutes: 120, call_id: "q2" }); const f = O.orders.find((x) => x.number === far.order_number);
assert.equal(f.status, "scheduled"); assert.equal(f.payLater, true); assert.equal(O.startDue(Date.now() + 91 * 60e3).length, 1); assert.equal(f.status, "cooking");
// handover: ready, but it cannot be handed over until it is paid at the counter; no pickup code is needed
O.markOrderReady(o.number); assert.equal(o.status, "ready");
assert.equal(O.deliver(o.number), null, "unpaid phone order is not handed over");
assert.match(r.message, /pickup code is \d \d \d \d/, "the caller is given the code, digit by digit"); assert.equal(r.pickup_code, o.pin);
assert.equal(O.pinOk(o.number, ""), false, "no code, no handover"); assert.equal(O.pinOk(o.number, "0000" === o.pin ? "1111" : "0000"), false, "wrong code"); assert.equal(O.pinOk(o.number, o.pin), true, "the right code");
assert.ok(O.settle(o, "cash")); assert.equal(o.status, "delivered"); assert.ok(o.paidAt);
// an unpaid phone order can be cancelled while cooking (it is not paid yet), but a paid walk-in cannot
assert.ok(O.cancelOrder(f.id), "cooking pay-later order can be cancelled"); assert.equal(f.status, "cancelled");
console.log("pay at pickup ok");
