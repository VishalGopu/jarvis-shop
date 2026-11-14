import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "chitragupta-test-"));       // never touch real data
const { createOrder, markPaid, markOrderReady, markLine, allocateDish, parseKitchen, cancelOrder, deliver, pinOk, setSoldOut, flush } = await import("../src/orders.js");
const { applyToCart, parse } = await import("../src/parser.js");

const a = createOrder([{ id: "samosa", qty: 2 }], "hi-IN");                              // 101
const b = createOrder([{ id: "masala_dosa", qty: 1 }, { id: "chai", qty: 1 }]);          // 102
const c = createOrder([{ id: "masala_dosa", qty: 2 }]);                                  // 103
assert.equal(a.total, 30); assert.equal(b.total, 70); assert.equal(a.lang, "hi-IN");
assert.equal(allocateDish("masala_dosa").length, 0, "unpaid orders never match");
markPaid(c); markPaid(b); markPaid(a); c.paidAt -= 2000; c.startedAt -= 2000; b.paidAt -= 1000; b.startedAt -= 1000;                // FIFO: c, b, a

// "dosa ready" (no qty): oldest paid order needing dosa gets it, only its own need
let r = allocateDish("masala_dosa"); assert.equal(r[0].order.id, c.id); assert.equal(r[0].given, 2); assert.equal(r[0].completed, true); assert.equal(c.status, "ready");
// b needs dosa AND chai: dosa alone does not complete it
r = allocateDish("masala_dosa"); assert.equal(r[0].order.id, b.id); assert.equal(r[0].completed, false); assert.equal(b.status, "cooking");
assert.equal(markLine(b.number, "chai").completed, true); assert.equal(b.status, "ready");
// "2 samosa ready" with qty, spread FIFO
const d = createOrder([{ id: "samosa", qty: 1 }]); markPaid(d);
r = allocateDish("samosa", 3); assert.deepEqual(r.map((x) => [x.order.id, x.given]), [[a.id, 2], [d.id, 1]]); assert.ok(a.status === "ready" && d.status === "ready");
// by order number
const e = createOrder([{ id: "chowmein", qty: 1 }, { id: "chai", qty: 2 }]); markPaid(e);
assert.equal(markOrderReady(e.number).order.status, "ready"); assert.equal(markOrderReady(e.number), null, "already ready");
// chef speech parsing
const f = createOrder([{ id: "cold_coffee", qty: 3 }]); markPaid(f);
assert.deepEqual(parseKitchen(`order ${f.number} ready`).numbers, [f.number]);
assert.deepEqual(parseKitchen("two dosa and chai are ready").dishes, [{ id: "masala_dosa", qty: 2 }, { id: "chai", qty: null }]);
assert.deepEqual(parseKitchen("5 samosa ready").dishes, [{ id: "samosa", qty: 5 }]);
assert.equal(pinOk(c.number, "0000"), false); assert.equal(pinOk(c.number, c.pin), true); assert.equal(deliver(c.number).status, "delivered");
// cancel only while unpaid
const g = createOrder([{ id: "chai", qty: 1 }]); assert.equal(cancelOrder(g.id).status, "cancelled"); assert.equal(cancelOrder(f.id), null);
assert.throws(() => createOrder([{ id: "nope", qty: 1 }]));
setSoldOut("chai", true); assert.throws(() => createOrder([{ id: "chai", qty: 1 }]), /sold out/); setSoldOut("chai", false);

let cart = applyToCart([], { items: { samosa: 2, chai: 1 }, remove: {}, clear: false });
cart = applyToCart(cart, { items: {}, remove: { samosa: 1 }, clear: false }); assert.deepEqual(cart, [{ id: "samosa", qty: 1 }, { id: "chai", qty: 1 }]);
assert.deepEqual(applyToCart(cart, { items: {}, remove: {}, clear: true }), []);
// An explicit decrease must subtract from the existing cart even with a model parser configured.
process.env.PARSER = "llama";
const less = await parse("reduce chai by one");
assert.deepEqual(less.remove, { chai: 1 }); assert.deepEqual(less.items, {});
assert.deepEqual(applyToCart([{ id: "chai", qty: 3 }], less), [{ id: "chai", qty: 2 }]);
process.env.PARSER = "rules";
const twoLess = await parse("decrease chai by 2");
assert.deepEqual(applyToCart([{ id: "chai", qty: 3 }], twoLess), [{ id: "chai", qty: 1 }]);
flush(); console.log("ok");
