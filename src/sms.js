// SMS sender. SMS_PROVIDER=mock (default) only logs and remembers the message, so a demo works without an SMS account;
// SMS_PROVIDER=exotel sends through Exotel (EXOTEL_SID, EXOTEL_API_KEY, EXOTEL_API_TOKEN, EXOTEL_SENDER, EXOTEL_HOST e.g. api.in.exotel.com, and in India
// EXOTEL_DLT_ENTITY_ID + EXOTEL_DLT_TEMPLATE_ID).
// Real transactional SMS in India needs a DLT-registered sender id and message template: put the approved template text in SMS_PAY_TEMPLATE.
const recent = [];
export const recentSms = (shop) => recent.filter((m) => !shop || m.shop === shop).slice(-20).reverse();
export const smsProvider = () => (process.env.SMS_PROVIDER || "mock");

/** Sends one text. Resolves { ok, mock?, error? }; never throws. */
export async function sendSms(to, text, shop = "") {
  const p = smsProvider();
  try {
    if (p === "exotel") {
      const { EXOTEL_SID: sid, EXOTEL_API_KEY: key, EXOTEL_API_TOKEN: tok, EXOTEL_SENDER: from } = process.env;
      if (!sid || !key || !tok || !from) return { ok: false, error: "Exotel SMS is not configured" };
      const host = process.env.EXOTEL_HOST || "api.exotel.com";
      const r = await fetch(`https://${host}/v1/Accounts/${sid}/Sms/send.json`, {
        method: "POST", headers: { Authorization: "Basic " + Buffer.from(`${key}:${tok}`).toString("base64"), "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ From: from, To: to, Body: text, SmsType: "transactional", ...(process.env.EXOTEL_DLT_ENTITY_ID ? { DltEntityId: process.env.EXOTEL_DLT_ENTITY_ID } : {}), ...(process.env.EXOTEL_DLT_TEMPLATE_ID ? { DltTemplateId: process.env.EXOTEL_DLT_TEMPLATE_ID } : {}) }), signal: AbortSignal.timeout(8000),
      });
      if (!r.ok) return { ok: false, error: "SMS provider answered " + r.status };
      return { ok: true };
    }
    recent.push({ at: Date.now(), to, text, shop }); if (recent.length > 100) recent.shift();
    console.log(`[sms:mock] to ${to}: ${text}`);
    return { ok: true, mock: true };
  } catch (e) { return { ok: false, error: e.message }; }
}
