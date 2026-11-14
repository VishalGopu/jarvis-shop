import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
import { EventEmitter } from "node:events";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "jarvis-payfirst-")); process.env.SHOP = "tiffin"; process.env.PARSER = "rules"; process.env.VOICE_KEY = "k"; process.env.PUBLIC_URL = "https://shop.example/"; process.env.SMS_PROVIDER = "mock"; process.env.PHONE_PAY = "link";
const { handlePhoneTool, toMobile } = await import("../src/voice.js"), { loadTenant, orders, expireUnpaidPhone, menu } = await import("../src/orders.js"), { recentSms } = await import("../src/sms.js");
const { verifyPayToken } = await import("../src/tenants.js");
assert.equal(menu.payWhen, "before", "tiffin shops are pay-first");
assert.equal(toMobile("09059158791"), "+919059158791"); assert.equal(toMobile("+91 90591 58791"), "+919059158791"); assert.equal(toMobile("12345"), null); assert.equal(toMobile("a@b.com"), null);

const hooks = { tenantFor: async () => loadTenant("default"), onOrder: () => { hooks.notified = (hooks.notified || 0) + 1; } };
async function call(path, body) {
  const req = new EventEmitter(); req.method = "POST"; req.url = path; req.headers = { authorization: "Bearer k" };
  let out; const res = { writeHead() {}, end: (t) => (out = JSON.parse(t)) }; const done = handlePhoneTool(req, res, hooks); req.emit("data", Buffer.from(JSON.stringify(body))); req.emit("end"); await done; return out;
}
const bad = await call("/phone/book", { shop: "X", order_text: "two idli", pickup_in_minutes: 20, caller: "someone@mail.com" });
assert.equal(bad.ok, false); assert.equal(bad.needs, "phone_number"); assert.equal(orders.length, 0, "no number, no order");
const ok = await call("/phone/book", { shop: "X", order_text: "two idli", pickup_in_minutes: 20, caller: "09059158791", call_id: "p1" });
assert.ok(ok.ok, JSON.stringify(ok)); assert.equal(ok.payment, "link_sent"); assert.equal(orders.length, 1); const o = orders[0];
assert.equal(o.status, "awaiting_payment", "reserved, not cooking, until paid"); assert.equal(o.source, "phone"); assert.equal(o.caller, "+919059158791"); assert.equal(hooks.notified, undefined, "kitchen is told only after payment");
const sms = recentSms()[0]; assert.equal(sms.to, "+919059158791"); const m = sms.text.match(/https:\/\/shop\.example\/pay\/(\S+)/); assert.ok(m, "SMS carries the pay link");
assert.deepEqual(verifyPayToken(m[1]), { shop: "default", order: o.id }); assert.equal(verifyPayToken(m[1] + "x"), null, "tampered token is refused");
const again = await call("/phone/book", { shop: "X", order_text: "two idli", pickup_in_minutes: 20, caller: "09059158791", call_id: "p1" });
assert.equal(again.order_number, o.number); assert.equal(orders.length, 1, "a retried call sends no second order");
assert.equal(expireUnpaidPhone(Date.now() + 5 * 60e3).length, 0, "not yet"); assert.equal(expireUnpaidPhone(Date.now() + 20 * 60e3).length, 1, "unpaid phone orders expire");
assert.equal(o.status, "cancelled");
console.log("pay-first phone ok");
