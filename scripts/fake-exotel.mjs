// Pretends to be Exotel's Voicebot applet: opens the /voice WebSocket, plays what a caller would say (spoken with Sarvam TTS,
// downsampled to 8 kHz phone audio), waits for each of the agent's replies, and prints / transcribes them.
//   node --env-file=.env scripts/fake-exotel.mjs "ws://localhost:3000/voice?shop=CODE&key=KEY" "two kilo rice" "that's all" "in twenty minutes"
// Options: --from=+919876543210  --transcribe (prints what the agent said, costs a little STT credit)  --out=/some/dir (saves reply WAVs)
import { writeFileSync, mkdirSync } from "node:fs";
import { tts, stt } from "../src/sarvam.js";
import { wavTo8kPcm, pcm8kToWav16k, wrapWav } from "../src/telephony.js";

const args = process.argv.slice(2), flag = (n) => args.find((a) => a.startsWith(`--${n}`));
const url = args[0], says = args.slice(1).filter((a) => !a.startsWith("--"));
const from = (flag("from") || "--from=+919999900001").split("=")[1], transcribe = !!flag("transcribe"), out = flag("out")?.split("=")[1];
if (!url || !says.length) { console.log("usage: fake-exotel.mjs <ws url> <utterance>... [--from=+91..] [--transcribe] [--out=dir]"); process.exit(1); }
if (out) mkdirSync(out, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const FRAME = 320;                                                      // 20 ms of 8 kHz PCM16
const silence = () => { const b = Buffer.alloc(FRAME); for (let i = 0; i < FRAME / 2; i++) b.writeInt16LE(Math.round((Math.random() - 0.5) * 120), i * 2); return b; };

console.log("generating caller audio…");
const caller = [];
for (const s of says) caller.push(wavTo8kPcm(Buffer.from(await tts(s, "en-IN"), "base64")));

const ws = new WebSocket(url);
let lastMedia = 0, bytes = 0, closed = false, current = [];
const replies = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.event === "media") { lastMedia = Date.now(); const b = Buffer.from(m.media.payload, "base64"); bytes += b.length; current.push(b); }
};
ws.onclose = (e) => { closed = true; console.log(`server closed the call (${e.code})`); };
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error("could not connect (wrong key, shop code or port?)")); });
ws.send(JSON.stringify({ event: "connected" }));
ws.send(JSON.stringify({ event: "start", sequence_number: 1, stream_sid: "SIM1", start: { stream_sid: "SIM1", call_sid: "CALLSIM1", account_sid: "sim", from, to: "+911800000000", custom_parameters: {}, media_format: { encoding: "raw", sample_rate: "8000", bit_rate: "128" } } }));
console.log(`call started from ${from}`);

// the server stays ~400 ms ahead of playback, so 900 ms without new audio means the caller has heard everything
async function agentFinished(timeout = 40000) {          // wait for the agent to start talking, then to stop for a moment
  const t0 = Date.now();
  while (!closed && Date.now() - t0 < timeout) { if (bytes > 0 && Date.now() - lastMedia > 900) return true; await sleep(100); }
  return bytes > 0;
}
const flush = async (label) => {
  const pcm = Buffer.concat(current); current = []; bytes = 0; if (!pcm.length) return;
  replies.push(pcm);
  const secs = (pcm.length / 16000).toFixed(1);
  let text = ""; if (transcribe) { try { text = " -> " + JSON.stringify((await stt(pcm8kToWav16k(pcm), "transcribe")).text); } catch (e) { text = " (could not transcribe: " + e.message + ")"; } }
  if (out) writeFileSync(`${out}/${String(replies.length).padStart(2, "0")}-${label}.wav`, wrapWav(pcm, 8000));
  console.log(`agent (${label}) spoke ${secs}s${text}`);
};

await agentFinished(); await flush("greeting");
for (let i = 0; i < caller.length && !closed; i++) {
  console.log(`caller: "${says[i]}"`);
  for (let p = 0; p < caller[i].length && !closed; p += FRAME) { ws.send(JSON.stringify({ event: "media", stream_sid: "SIM1", media: { payload: caller[i].subarray(p, p + FRAME).toString("base64") } })); await sleep(20); }
  for (let k = 0; k < 90 && !closed; k++) { ws.send(JSON.stringify({ event: "media", stream_sid: "SIM1", media: { payload: silence().toString("base64") } })); await sleep(20); }
  await agentFinished(); await flush(`reply${i + 1}`);
}
await sleep(1500); if (!closed) { ws.send(JSON.stringify({ event: "stop", stop: { reason: "hangup" } })); ws.close(); }
console.log("done");
