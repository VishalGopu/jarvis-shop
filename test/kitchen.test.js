import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs"; import { tmpdir } from "node:os"; import { join } from "node:path";
process.env.DATA_DIR = mkdtempSync(join(tmpdir(), "chitragupta-test-"));       // never touch real data
const { createOrder, markPaid, hooks, flush } = await import("../src/orders.js");
const K = await import("../src/kitchen.js");
const ready = []; hooks.ready = (o) => ready.push(o.number);
const pay = (cart, age = 0) => { const o = createOrder(cart); markPaid(o); o.paidAt -= age; return o; };

// --- station mode: A owns dosa, B owns chai+samosa
K.setKitchen({ mode: "station", chefs: [{ name: "A", skills: ["masala_dosa"] }, { name: "B", skills: ["chai", "samosa"] }] });
const [A, B] = K.kitchenView().chefs.map((c) => c.id);
assert.equal(K.schedule().length, 0, "offline chefs get nothing");
const o1 = pay([{ id: "masala_dosa", qty: 2 }, { id: "chai", qty: 1 }], 3000), o2 = pay([{ id: "samosa", qty: 1 }], 1000);
K.setOnline(A, true); K.setOnline(B, true);
let out = K.schedule(); const by = Object.fromEntries(out.map((x) => [x.chefId, x]));
assert.equal(out.length, 2); assert.equal(by[A].items[0].id, "masala_dosa"); assert.equal(by[B].items[0].id, "chai", "specialists get their dishes, oldest order first");
assert.equal(K.schedule().length, 0, "busy chefs are not given more");
let r = K.completeTask(A); assert.equal(r.completed, false, "order 1 still needs chai");
out = K.schedule(); assert.equal(out.length, 1); assert.equal(out[0].chefId, A); assert.equal(out[0].items[0].id, "samosa", "A helps with samosa when nothing of theirs is waiting");
r = K.completeTask(B); assert.equal(r.completed, true); assert.deepEqual(ready, [o1.number]);
r = K.completeTask(A); assert.equal(r.completed, true); assert.deepEqual(ready, [o1.number, o2.number]);

// --- going offline releases the task, another chef picks it up
const o3 = pay([{ id: "masala_dosa", qty: 1 }]); K.schedule(); assert.equal(K.kitchenView().chefs.find((c) => c.id === A).task.number, o3.number);
K.setOnline(A, false); out = K.schedule(); assert.equal(out[0].chefId, B, "released task goes to the other chef"); K.completeTask(B);

// --- order mode: whole orders, oldest first
K.setKitchen({ mode: "order", chefs: [{ id: A, name: "A" }, { id: B, name: "B" }, { name: "C" }] }); const C = K.kitchenView().chefs[2].id;
K.setOnline(A, true); K.setOnline(C, true); K.setOnline(B, false);
const p1 = pay([{ id: "samosa", qty: 1 }, { id: "chai", qty: 1 }], 2000), p2 = pay([{ id: "chowmein", qty: 1 }], 1000), p3 = pay([{ id: "chai", qty: 1 }]);
out = K.schedule(); assert.equal(out.length, 2); assert.equal(out[0].number, p1.number); assert.equal(out[1].number, p2.number); assert.equal(out[0].items.length, 2, "whole order = all its items");
const first = out[0].chefId; assert.equal(K.completeTask(first).completed, true);
out = K.schedule(); assert.equal(out[0].number, p3.number); assert.equal(out[0].chefId, first, "freed chef immediately gets the next order");

// --- board completion (outside the scheduler) frees the chef
const { markOrderReady } = await import("../src/orders.js"); markOrderReady(p2.number); K.sync();
assert.ok(K.kitchenView().chefs.every((c) => !c.task || c.task.number !== p2.number));
// --- reconfigure: removing a chef frees their work
K.setKitchen({ mode: "order", chefs: [{ id: first, name: "Solo" }] }); assert.equal(K.kitchenView().chefs.length, 1);

// ===== secondary dishes + "help with anything" switch
const { orders } = await import("../src/orders.js");
orders.filter((o) => o.status === "cooking").forEach((o) => markOrderReady(o.number)); K.sync();
K.setKitchen({ mode: "station", chefs: [{ name: "X", skills: ["masala_dosa"], also: ["samosa", "masala_dosa"], helpAny: false }, { name: "Y", skills: ["chai"] }] });
const [X, Y] = K.kitchenView().chefs.map((c) => c.id);
assert.deepEqual(K.kitchenView().chefs[0].also, ["samosa"], "a dish can't be both owned and secondary"); assert.equal(K.kitchenView().chefs[0].helpAny, false); assert.equal(K.kitchenView().chefs[1].helpAny, true);
K.setOnline(X, true);
const q1 = pay([{ id: "chowmein", qty: 1 }], 3000), q2 = pay([{ id: "samosa", qty: 1 }], 1000);
out = K.schedule(); assert.equal(out.length, 1); assert.equal(out[0].items[0].id, "samosa", "secondary dish beats an older 'anything' task");
K.completeTask(X); assert.equal(K.schedule().length, 0, "helpAny off: X never takes chowmein");
K.setOnline(Y, true); out = K.schedule(); assert.equal(out[0].chefId, Y); assert.equal(out[0].items[0].id, "chowmein", "Y (help-any) takes it"); K.completeTask(Y);

// ===== transfer
const t1 = pay([{ id: "masala_dosa", qty: 2 }]); out = K.schedule(); assert.equal(out[0].chefId, X);
let tr = K.transferTask(X, Y); assert.ok(tr.ok); assert.equal(K.kitchenView().chefs[0].task, null);
out = K.schedule(); assert.equal(out.length, 1); assert.equal(out[0].chefId, Y); assert.equal(out[0].from, "X", "idle target gets it at once, told who passed it"); K.completeTask(Y);
// busy target: it waits for them, the sender does not take it back
pay([{ id: "chai", qty: 1 }]); out = K.schedule(); assert.equal(out[0].chefId, Y);
const t3 = pay([{ id: "masala_dosa", qty: 1 }]); out = K.schedule(); assert.equal(out[0].chefId, X);
tr = K.transferTask(X, Y); assert.ok(tr.ok); assert.equal(K.schedule().length, 0, "pinned to a busy chef: nobody else takes it, not even the idle sender");
assert.equal(K.kitchenView().chefs[1].queued.length, 1); assert.equal(K.kitchenView().chefs[1].queued[0].from, "X");
K.completeTask(Y); out = K.schedule(); assert.equal(out[0].chefId, Y); assert.equal(out[0].from, "X"); assert.equal(K.kitchenView().chefs[1].queued.length, 0); K.completeTask(Y);
// "anyone else": goes to someone other than the sender
pay([{ id: "masala_dosa", qty: 1 }]); out = K.schedule(); assert.equal(out[0].chefId, X);
tr = K.transferTask(X, "any"); assert.ok(tr.ok); out = K.schedule(); assert.equal(out[0].chefId, Y, "not bounced back to the sender"); K.completeTask(Y);
// errors + only-chef-on-shift + pin cleared if target leaves
pay([{ id: "masala_dosa", qty: 1 }]); K.schedule();
assert.ok(K.transferTask(X, X).error); assert.ok(K.transferTask(X, "nope").error);
K.setOnline(Y, false); assert.match(K.transferTask(X, "any").error, /no other chef/); assert.match(K.transferTask(Y, X).error, /no active task/);
K.setOnline(Y, true); K.transferTask(X, Y); K.setOnline(Y, false); out = K.schedule();
assert.equal(out[0].chefId, X, "target went offline -> pin dropped, owner takes it back");
flush(); console.log("kitchen ok");
