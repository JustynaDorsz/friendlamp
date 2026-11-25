// Checks the push sender against independent implementations:
// - the encrypted body is decrypted with the http_ece package (as a browser would)
// - the VAPID signature is verified with Node's crypto module
import assert from "node:assert/strict";
import crypto from "node:crypto";
import ece from "http_ece";
import { generateVapidKeys, sendPush, b64uDecode } from "../src/webpush.js";

// A fake browser subscription.
const browser = crypto.createECDH("prime256v1");
browser.generateKeys();
const auth = crypto.randomBytes(16);
const subscription = {
  endpoint: "https://push.example.test/send/abc123",
  keys: { p256dh: browser.getPublicKey().toString("base64url"), auth: auth.toString("base64url") },
};

const vapid = await generateVapidKeys();
const payload = { title: "FriendLamp", body: "Zoë is thinking of you 💡", ts: 1234567890 };

let captured;
const fakeFetch = async (url, init) => {
  captured = { url, init };
  return { ok: true, status: 201 };
};
const result = await sendPush(subscription, payload, vapid, "https://friendlamp.example.workers.dev", fakeFetch);
assert.deepEqual(result, { ok: true, status: 201, gone: false });
assert.equal(captured.url, subscription.endpoint);
assert.equal(captured.init.headers["Content-Encoding"], "aes128gcm");
assert.equal(captured.init.headers.TTL, "86400");

// 1. Decrypt like the browser.
const plain = ece.decrypt(Buffer.from(captured.init.body), {
  version: "aes128gcm",
  privateKey: browser,
  authSecret: auth,
});
assert.deepEqual(JSON.parse(plain.toString("utf8")), payload);
console.log("ok  payload decrypts:", plain.toString("utf8"));

// 2. Verify the VAPID header.
const m = /^vapid t=([\w-]+)\.([\w-]+)\.([\w-]+), k=([\w-]+)$/.exec(captured.init.headers.Authorization);
assert.ok(m, "Authorization header format");
const [, h, c, sig, k] = m;
assert.equal(k, vapid.publicKey);
assert.equal(b64uDecode(k).length, 65);
assert.deepEqual(JSON.parse(Buffer.from(h, "base64url")), { typ: "JWT", alg: "ES256" });
const claims = JSON.parse(Buffer.from(c, "base64url"));
assert.equal(claims.aud, "https://push.example.test");
assert.equal(claims.sub, "https://friendlamp.example.workers.dev");
const secondsLeft = claims.exp - Date.now() / 1000;
assert.ok(secondsLeft > 0 && secondsLeft <= 24 * 3600, "exp within 24h");

const pubKey = crypto.createPublicKey({
  key: { ...vapid.privateJwk, d: undefined, key_ops: ["verify"] },
  format: "jwk",
});
const valid = crypto.verify(
  "sha256",
  Buffer.from(`${h}.${c}`),
  { key: pubKey, dsaEncoding: "ieee-p1363" },
  Buffer.from(sig, "base64url")
);
assert.ok(valid, "VAPID signature verifies");
console.log("ok  VAPID signature verifies, aud =", claims.aud);

// 3. A removed subscription is reported as gone.
const gone = await sendPush(subscription, payload, vapid, "https://x.example", async () => ({ ok: false, status: 410 }));
assert.equal(gone.gone, true);
console.log("ok  410 is reported as gone");
