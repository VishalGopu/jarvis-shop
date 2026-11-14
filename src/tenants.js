// Shop registry + access control for multi-shop mode. A shop is created once (name, type, owner PIN) and gets a short SHOP CODE.
// Devices join with the code (counter / kitchen) or code + owner PIN (owner) and receive a signed, stateless token (HMAC), so any
// number of app servers can verify requests without a session store. The registry is small; per-shop data lives in the shop's own file.
import { randomBytes, createHmac, scrypt, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, renameSync, existsSync } from "node:fs";
import { join } from "node:path";
import { dataDir } from "./store.js";
import { profiles } from "./orders.js";

export const ROLES = ["counter", "kitchen", "owner"];
let reg = null, byCode = null;
const regFile = () => join(dataDir(), "tenants.json");
function load() {
  if (reg) return reg; try { reg = JSON.parse(readFileSync(regFile(), "utf8")); } catch { reg = {}; }
  reg.shops ||= {}; byCode = new Map(Object.values(reg.shops).map((s) => [s.code, s.id])); return reg;
}
function save() { mkdirSync(dataDir(), { recursive: true }); const f = regFile(), tmp = f + ".tmp"; writeFileSync(tmp, JSON.stringify(reg)); renameSync(tmp, f); }
export const resetRegistry = () => { reg = null; byCode = null; secret = null; };

const ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const newCode = () => { let c; do { c = Array.from(randomBytes(6), (b) => ALPHA[b % ALPHA.length]).join(""); } while (byCode.has(c)); return c; };
const hashPin = (pin, salt) => new Promise((ok, no) => scrypt(String(pin), salt, 32, (e, k) => (e ? no(e) : ok(k.toString("hex")))));   // async: hashing must not block the event loop for other shops

export async function createShop({ name, profile, pin }) {
  load(); name = String(name || "").trim();
  if (name.length < 2 || name.length > 40) throw new Error("shop name must be 2-40 characters");
  if (!profiles[profile]) throw new Error("unknown shop type");
  if (!/^\d{4,6}$/.test(String(pin || ""))) throw new Error("owner PIN must be 4-6 digits");
  const id = "s_" + randomBytes(5).toString("hex"), salt = randomBytes(8).toString("hex");
  const shop = { id, name, profile, code: newCode(), salt, pinHash: await hashPin(pin, salt), createdAt: Date.now(), plan: {} };
  reg.shops[id] = shop; byCode.set(shop.code, id); save(); return shop;
}
export const getShop = (id) => load().shops[id] || null;
export const shopByCode = (code) => { load(); const id = byCode.get(String(code || "").trim().toUpperCase()); return id ? reg.shops[id] : null; };
export const listShops = () => Object.values(load().shops);
export const pinOkFor = async (shop, pin) => { try { return timingSafeEqual(Buffer.from(await hashPin(pin, shop.salt), "hex"), Buffer.from(shop.pinHash, "hex")); } catch { return false; } };
export const publicShop = (s) => ({ id: s.id, name: s.name, profile: s.profile, code: s.code, createdAt: s.createdAt });

// ---- signed tokens
let secret = null;
function key() {
  if (secret) return secret; if (process.env.TOKEN_SECRET) return (secret = process.env.TOKEN_SECRET);
  const f = join(dataDir(), "secret.key"); if (existsSync(f)) return (secret = readFileSync(f, "utf8").trim());
  mkdirSync(dataDir(), { recursive: true }); secret = randomBytes(32).toString("hex"); writeFileSync(f, secret, { mode: 0o600 }); return secret;
}
const b64 = (x) => Buffer.from(x).toString("base64url"), mac = (p) => createHmac("sha256", key()).update(p).digest("base64url");
export const issue = (shopId, role, days = 30) => { const p = b64(JSON.stringify({ s: shopId, r: role, exp: Date.now() + days * 864e5 })); return p + "." + mac(p); };
/** Signed, stateless link token for paying one order: no login needed, valid only for that shop + order. */
export const payToken = (shopId, orderId) => { const p = b64(JSON.stringify({ s: shopId, o: orderId })); return p + "." + mac("pay." + p); };
export function verifyPayToken(token) {
  const [p, sig] = String(token || "").split("."); if (!p || !sig) return null;
  const want = mac("pay." + p); if (sig.length !== want.length || !timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return null;
  try { const d = JSON.parse(Buffer.from(p, "base64url").toString()); return d.s && d.o ? { shop: d.s, order: d.o } : null; } catch { return null; }
}
export function verify(token) {
  const [p, sig] = String(token || "").split("."); if (!p || !sig) return null;
  const want = mac(p); if (sig.length !== want.length || !timingSafeEqual(Buffer.from(sig), Buffer.from(want))) return null;
  try { const d = JSON.parse(Buffer.from(p, "base64url").toString()); return d.exp > Date.now() && ROLES.includes(d.r) ? { shop: d.s, role: d.r } : null; } catch { return null; }
}

// ---- what each role may call (owner = everything)
const RULES = {
  counter: [["*", "/api/config"], ["POST", "/api/turn"], ["POST", "/api/checkout"], ["*", "/api/order/"], ["GET", "/api/kitchen"], ["POST", "/api/tts"], ["GET", "/api/events"], ["POST", "/api/deliver"]],
  kitchen: [["*", "/api/config"], ["GET", "/api/kitchen"], ["POST", "/api/ready"], ["POST", "/api/deliver"], ["POST", "/api/soldout"], ["*", "/api/chefs/"], ["POST", "/api/line/"], ["POST", "/api/order/"], ["POST", "/api/tts"], ["GET", "/api/events"]],
};
export function can(role, method, path) {
  if (role === "owner") return true;
  return (RULES[role] || []).some(([m, p]) => (m === "*" || m === method) && (p.endsWith("/") ? path.startsWith(p) : path === p));
}

// ---- brute-force / abuse throttles (in memory; per process)
const fails = new Map(), signups = new Map();
export const locked = (k) => { const f = fails.get(k); return !!f && f.n >= 5 && Date.now() < f.until; };
export const failed = (k) => { const f = fails.get(k) || { n: 0 }; f.n++; f.until = Date.now() + 5 * 60e3; fails.set(k, f); };
export const cleared = (k) => fails.delete(k);
export function signupAllowed(ip) { const now = Date.now(), a = (signups.get(ip) || []).filter((t) => now - t < 36e5); if (a.length >= (+process.env.SIGNUPS_PER_HOUR || 10)) return false; a.push(now); signups.set(ip, a); return true; }
