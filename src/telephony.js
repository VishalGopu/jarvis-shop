// Phone-call audio helpers (pure functions, no network): PCM/WAV conversion, speech endpointing, "when will you collect?" parsing.
// Telephony streams are raw 16-bit little-endian mono PCM at 8 kHz (Exotel Voicebot applet); Sarvam wants WAV, and returns WAV.

export const PHONE_RATE = 8000;

export function wrapWav(pcm, rate) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVEfmt ", 8);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

/** Walk the RIFF chunks (TTS WAVs can carry extra chunks) and return format + sample bytes. */
export function parseWav(buf) {
  if (buf.length < 44 || buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") throw new Error("not a WAV file");
  let p = 12, fmt = null, data = null;
  while (p + 8 <= buf.length) {
    const id = buf.toString("ascii", p, p + 4), size = buf.readUInt32LE(p + 4), body = p + 8;
    if (id === "fmt ") fmt = { format: buf.readUInt16LE(body), channels: buf.readUInt16LE(body + 2), rate: buf.readUInt32LE(body + 4), bits: buf.readUInt16LE(body + 14) };
    else if (id === "data") { data = buf.subarray(body, Math.min(buf.length, body + size)); break; }   // size can be bogus on streamed WAVs
    p = body + size + (size & 1);
  }
  if (!fmt || !data) throw new Error("WAV is missing fmt or data");
  return { ...fmt, data };
}

/** 8 kHz PCM -> 16 kHz PCM by linear interpolation (the server's speech check wants 16 kHz). */
export function upsample2x(pcm8) {
  const n = pcm8.length >> 1, out = Buffer.alloc(n * 4);
  for (let i = 0; i < n; i++) {
    const a = pcm8.readInt16LE(2 * i), b = i + 1 < n ? pcm8.readInt16LE(2 * i + 2) : a;
    out.writeInt16LE(a, 4 * i); out.writeInt16LE((a + b) >> 1, 4 * i + 2);
  }
  return out;
}
export const pcm8kToWav16k = (pcm8) => wrapWav(upsample2x(pcm8), 16000);

/** Any 16-bit WAV -> 8 kHz mono PCM (box-filter average when shrinking, so there is no harsh aliasing). */
export function wavTo8kPcm(wav) {
  const { channels, rate, bits, data } = parseWav(wav);
  if (bits !== 16) throw new Error("only 16-bit WAV is supported");
  const frames = Math.floor(data.length / (2 * channels));
  const mono = new Float64Array(frames);
  for (let i = 0; i < frames; i++) { let s = 0; for (let c = 0; c < channels; c++) s += data.readInt16LE((i * channels + c) * 2); mono[i] = s / channels; }
  const ratio = rate / PHONE_RATE, n = Math.floor(frames / ratio), out = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const a = Math.floor(i * ratio), b = Math.max(a + 1, Math.min(frames, Math.ceil((i + 1) * ratio)));
    let s = 0; for (let j = a; j < b; j++) s += mono[j];
    out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(s / (b - a)))), i * 2);
  }
  return out;
}

export const rms = (pcm) => { const n = pcm.length >> 1; if (!n) return 0; let s = 0; for (let i = 0; i < n; i++) { const v = pcm.readInt16LE(2 * i); s += v * v; } return Math.sqrt(s / n); };
const ms = (bytes, rate) => (bytes / 2 / rate) * 1000;

/**
 * Energy-based end-of-utterance detection for a phone line. Feed it frames of any size; it returns the finished
 * utterance (PCM) once the caller has spoken and then been quiet for `endSilenceMs`. The noise floor adapts, so a noisy
 * shop does not read as constant speech.
 */
export class Endpointer {
  constructor({ rate = PHONE_RATE, minSpeechMs = 300, endSilenceMs = 800, maxMs = 15000, preRollMs = 240, minThreshold = 400 } = {}) {
    Object.assign(this, { rate, minSpeechMs, endSilenceMs, maxMs, preRollMs, minThreshold });
    this.reset();
  }
  reset() { this.noise = 150; this.pre = []; this.preMs = 0; this.buf = null; this.speechMs = 0; this.silenceMs = 0; this.totalMs = 0; }
  push(frame) {
    const d = ms(frame.length, this.rate), level = rms(frame), thr = Math.max(this.minThreshold, this.noise * 3);
    const loud = level > thr;
    if (!this.buf) {
      if (!loud) {
        this.noise = this.noise * 0.95 + level * 0.05;
        this.pre.push(frame); this.preMs += d;
        while (this.preMs - ms(this.pre[0].length, this.rate) >= this.preRollMs) { this.preMs -= ms(this.pre.shift().length, this.rate); }
        return null;
      }
      this.buf = [...this.pre]; this.totalMs = this.preMs; this.pre = []; this.preMs = 0; this.speechMs = 0; this.silenceMs = 0;
    }
    this.buf.push(frame); this.totalMs += d;
    if (loud) { this.speechMs += d; this.silenceMs = 0; } else this.silenceMs += d;
    if (this.silenceMs >= this.endSilenceMs || this.totalMs >= this.maxMs) {
      const done = this.speechMs >= this.minSpeechMs ? Buffer.concat(this.buf) : null;   // a short click is not a sentence
      this.buf = null; this.speechMs = this.silenceMs = this.totalMs = 0;
      return done;
    }
    return null;
  }
}

// ---- "when will you collect it?" (the STT runs in translate mode, so this sees English)
const ONES = { zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19 };
const TENS = { twenty: 20, thirty: 30, forty: 40, fifty: 50, sixty: 60, ninety: 90 };
/** "twenty five" -> 25, "7" -> 7, otherwise null. */
export function numberWord(s) {
  s = s.trim().toLowerCase().replace(/-/g, " "); if (/^\d+$/.test(s)) return +s;
  const [a, b] = s.split(/\s+/); if (a in ONES && !b) return ONES[a]; if (a in TENS) return TENS[a] + (b in ONES && ONES[b] < 10 ? ONES[b] : 0);
  return null;
}
const NUM = "(\\d+|(?:(?:twenty|thirty|forty|fifty|sixty|ninety)[ -]?)?(?:one|two|three|four|five|six|seven|eight|nine)|zero|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|ninety)";

/** Returns the pickup time as epoch ms, or null if it could not be understood or is unreasonable (> 6 h away). */
export function parsePickup(text, now = Date.now()) {
  const t = String(text || "").toLowerCase().replace(/[.,!?]/g, " ").replace(/\s+/g, " ").trim(); if (!t) return null;
  let at = null;
  if (/\b(now|right away|asap|immediately|as soon as (possible|i can))\b/.test(t)) at = now + 10 * 60e3;
  else if (/\b(an|one|1) hour and a half\b|\bone and a half hours?\b/.test(t)) at = now + 90 * 60e3;
  else if (/\bhalf (an? |of an? )?hour\b/.test(t)) at = now + 30 * 60e3;
  else if (/\b(quarter|15 minutes)\b.*\bhour\b|\ba quarter of an hour\b/.test(t)) at = now + 15 * 60e3;
  else {
    let m = t.match(new RegExp("\\b" + NUM + "\\s*(?:minutes?|mins?)\\b"));
    if (m && numberWord(m[1]) != null) at = now + numberWord(m[1]) * 60e3;
    else if ((m = t.match(new RegExp("\\b" + NUM + "\\s*hours?\\b"))) && numberWord(m[1]) != null) at = now + numberWord(m[1]) * 3600e3;
    else if (/\b(an|one) hour\b/.test(t)) at = now + 3600e3;
    else if ((m = t.match(new RegExp("\\bat\\s+" + NUM + "(?:\\s*(?:[: ]|and)\\s*" + NUM + ")?\\s*(am|pm|a m|p m)?\\b")))) {
      let h = numberWord(m[1]), min = m[2] != null ? numberWord(m[2]) : 0; const ap = (m[3] || "").replace(" ", "");
      if (h != null && min != null && h >= 0 && h <= 24 && min < 60) {
        const d = new Date(now); d.setSeconds(0, 0);
        const tryAt = (hh) => { d.setHours(hh % 24, min); return d.getTime(); };
        if (ap === "pm") at = tryAt(h % 12 + 12); else if (ap === "am") at = tryAt(h % 12);
        else { const c = [tryAt(h), tryAt(h % 12 + 12)].sort((x, y) => x - y); at = c.find((x) => x >= now - 5 * 60e3) ?? c[1]; }
        if (at < now - 5 * 60e3) at += 864e5;
      }
    }
  }
  if (at == null || at > now + 6 * 3600e3) return null;
  return Math.max(at, now);
}

/** 6:30 PM style label, spoken fine by TTS in every language we use. */
export function fmtClock(ms) {
  const d = new Date(ms); let h = d.getHours(); const m = d.getMinutes(), ap = h >= 12 ? "PM" : "AM"; h = h % 12 || 12;
  return `${h}:${String(m).padStart(2, "0")} ${ap}`;
}
