import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "chitragupta-kirana-")); process.env.SHOP = "kirana";
const O = await import("../src/orders.js"), K = await import("../src/kitchen.js"), P = await import("../src/parser.js"), I = await import("../src/insights.js");
const { menu, createOrder, markLine, markUnavailable, settle, deliver, cancelOrder, itemById, setCatalog, setShop, orders } = O;
assert.equal(menu.id, "kirana"); assert.equal(menu.payWhen, "after"); assert.equal(K.kitchenView().mode, "order");

// order with weights + a quality note; work starts immediately, stock is reserved, nothing is paid yet
const stock0 = itemById("rice").stock;
const o = createOrder([{ id: "rice", qty: 1.5, note: "basmati" }, { id: "sugar", qty: 0.5 }, { id: "maggi", qty: 3 }]);
assert.equal(o.total, 90 + 22 + 42); assert.equal(o.status, "cooking"); assert.equal(o.paidAt, null); assert.ok(o.startedAt);
assert.equal(o.lines[0].note, "basmati"); assert.equal(itemById("rice").stock, stock0 - 1.5);
assert.throws(() => createOrder([{ id: "rice", qty: 50 }]), /only/);
// a picker gets the whole slip
K.setKitchen({ chefs: [{ name: "Ravi", lang: "te-IN" }] }); K.setOnline("c1", true); const t = K.schedule();
assert.equal(t.length, 1); assert.equal(t[0].whole, true); assert.equal(t[0].items.length, 3); assert.equal(t[0].items[0].note, "basmati");
// ticks one line, says there is no sugar: bill shrinks, stock comes back, then the last line completes the slip
markLine(o.number, "rice"); const s0 = itemById("sugar").stock;
const u = markUnavailable(o.number, "sugar"); assert.equal(u.order.total, 90 + 42); assert.equal(itemById("sugar").stock, s0 + 0.5); assert.equal(o.status, "cooking");
assert.equal(markLine(o.number, "maggi").completed, true); assert.equal(o.status, "ready");
// ready but unpaid cannot be handed over; cash settles and hands over
assert.equal(deliver(o.number), null); assert.equal(settle(o, "cash").status, "delivered"); assert.equal(o.method, "cash"); assert.ok(o.paidAt);
assert.equal(settle(o, "cash"), null, "cannot pay twice");
// cancelling a slip puts the stock back
const before = itemById("milk").stock; const c = createOrder([{ id: "milk", qty: 4 }]); assert.equal(itemById("milk").stock, before - 4); cancelOrder(c.id); assert.equal(itemById("milk").stock, before);
// parser understands units and notes
const p = await (async () => { process.env.PARSER = "rules"; return P.parse("half kilo sugar, ripe tomatoes 2 kg and 3 packets maggi, that's all"); })();
assert.deepEqual(p.items, { sugar: 0.5, tomato: 2, maggi: 3 }); assert.equal(p.notes.tomato, "ripe"); assert.equal(p.checkout, true);
assert.deepEqual(P.applyToCart([{ id: "sugar", qty: 0.5 }], p).find((x) => x.id === "sugar"), { id: "sugar", qty: 1 });
// owner copilot: restock advice uses stock + sales pace
orders.push(...Array.from({ length: 12 }, (_, i) => ({ id: "X" + i, number: 900 + i, shop: "kirana", lines: [{ id: "toor_dal", name: "Toor Dal", qty: 2, unit: "kg", price: 150, amount: 300 }], total: 300, status: "delivered", startedAt: Date.now() - 3600e3 * (i + 1) * 3, paidAt: Date.now() - 3600e3 * (i + 1) * 3 + 600e3, readyAt: Date.now() - 3600e3 * (i + 1) * 3 + 300e3, deliveredAt: Date.now() - 3600e3 * (i + 1) * 3 + 600e3 })));
const f = I.facts(); assert.ok(f.stock.some((x) => x.name === "Toor Dal" && x.daysLeft != null && x.daysLeft < 2), "dal is running low");
assert.match(I.answerRules("what is running low", f, "en-IN"), /Toor Dal/); assert.ok(!f.tomorrow.prep.length || f.fulfil !== "kitchen");
for (const l of ["en-IN", "hi-IN", "te-IN"]) assert.ok(!/undefined|NaN/.test(I.briefing(f, l).text));
// catalog edits: price, stock, a new item with aliases; shop switch blocked while orders are open
const r = setCatalog([{ id: "rice", name: "Rice", price: 65, unit: "kg", step: 0.5, stock: 20 }, { name: "Jaggery", price: 80, unit: "kg", aliases: "gud, బెల్లం" }]);
assert.equal(r.items.length, 2); assert.equal(itemById("rice").price, 65); assert.equal(itemById("jaggery").aliases[1], "బెల్లం");
createOrder([{ id: "rice", qty: 1 }]); assert.match(setShop("canteen").error, /open orders/);
console.log("kirana ok");
