import assert from "node:assert/strict";
import { wrapWav, parseWav, upsample2x, wavTo8kPcm, rms, Endpointer, parsePickup, fmtClock, numberWord } from "../src/telephony.js";
import { wavDuration } from "../src/audio.js";

const tone = (ms, rate, amp = 4000, freq = 300) => { const n = Math.round((ms / 1000) * rate), b = Buffer.alloc(n * 2); for (let i = 0; i < n; i++) b.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * amp), 2 * i); return b; };
const quiet = (ms, rate = 8000) => tone(ms, rate, 60, 50);
const frames = (pcm, bytes = 320) => { const out = []; for (let i = 0; i < pcm.length; i += bytes) out.push(pcm.subarray(i, i + bytes)); return out; };

// WAV round trip + the server's own speech check accepts what we build from phone audio
const w = wrapWav(tone(500, 16000), 16000), p = parseWav(w);
assert.equal(p.rate, 16000); assert.equal(p.channels, 1); assert.equal(p.bits, 16); assert.equal(p.data.length, 16000);
assert.equal(wavDuration(wrapWav(upsample2x(tone(1000, 8000)), 16000)), 1, "8 kHz call audio upsampled to 16 kHz passes the server check");

// TTS-style WAV (24 kHz, extra chunk before data) -> 8 kHz phone PCM
const t24 = wrapWav(tone(1000, 24000), 24000), extra = Buffer.concat([t24.subarray(0, 36), Buffer.from("LIST\x04\x00\x00\x00abcd", "binary"), t24.subarray(36)]);
extra.writeUInt32LE(extra.length - 8, 4);
assert.equal(wavTo8kPcm(extra).length, 16000, "1 s at 24 kHz becomes 1 s (8000 samples) at 8 kHz");
assert.ok(rms(wavTo8kPcm(t24)) > 2000, "signal survives downsampling");

// endpointing: noise, one sentence, trailing silence
let ep = new Endpointer(), got = [];
for (const f of frames(Buffer.concat([quiet(1000), tone(1200, 8000), quiet(1200)]))) { const u = ep.push(f); if (u) got.push(u); }
assert.equal(got.length, 1, "one utterance");
assert.ok(got[0].length / 16 >= 1200 && got[0].length / 16 < 2800, "utterance covers the speech (" + got[0].length / 16 + " ms)");
ep = new Endpointer(); got = [];
for (const f of frames(Buffer.concat([quiet(600), tone(120, 8000), quiet(1500)]))) { const u = ep.push(f); if (u) got.push(u); }
assert.equal(got.length, 0, "a short click is not a sentence");
ep = new Endpointer(); got = [];
for (const f of frames(Buffer.concat([tone(600, 8000), quiet(1000), tone(600, 8000), quiet(1000)]), 800)) { const u = ep.push(f); if (u) got.push(u); }
assert.equal(got.length, 2, "two sentences with a pause between, even with 50 ms frames of odd size");

// pickup time ("when will you collect it?")
const now = new Date(2026, 10, 14, 17, 0, 0).getTime(), min = (m) => now + m * 60e3;
assert.equal(parsePickup("in twenty minutes", now), min(20));
assert.equal(parsePickup("In 45 minutes.", now), min(45));
assert.equal(parsePickup("half an hour", now), min(30));
assert.equal(parsePickup("in an hour", now), min(60));
assert.equal(parsePickup("two hours", now), min(120));
assert.equal(parsePickup("right now", now), min(10));
assert.equal(fmtClock(parsePickup("at six thirty", now)), "6:30 PM", "no am/pm: the next sensible time");
assert.equal(fmtClock(parsePickup("at 7 pm", now)), "7:00 PM");
assert.equal(fmtClock(parsePickup("at 5:45", now)), "5:45 PM");
assert.equal(parsePickup("at 9 am", now), null, "9 am has passed and tomorrow is beyond the 6 hour window");
assert.equal(parsePickup("tomorrow evening", now), null, "too vague");
assert.equal(parsePickup("in ten hours", now), null, "too far");
assert.equal(parsePickup("", now), null);
assert.equal(numberWord("twenty five"), 25); assert.equal(numberWord("seven"), 7); assert.equal(numberWord("12"), 12);
console.log("telephony ok");
