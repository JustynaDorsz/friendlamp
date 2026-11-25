// Checks the Firebase sender: the sign-in assertion verifies with the service
// account's public key, and the message request has the expected shape.
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { fcmClientConfig, sendFcm } from "../src/fcm.js";

const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const env = {
  FCM_SERVICE_ACCOUNT: JSON.stringify({
    project_id: "friendlamp-test",
    client_email: "sender@friendlamp-test.iam.gserviceaccount.com",
    private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
  }),
  FCM_APP_ID: "1:123456789012:android:abcdef0123456789",
  FCM_API_KEY: "test-api-key",
};

assert.deepEqual(fcmClientConfig({}), { configured: false });
assert.deepEqual(fcmClientConfig(env), {
  configured: true,
  projectId: "friendlamp-test",
  appId: env.FCM_APP_ID,
  apiKey: "test-api-key",
  senderId: "123456789012",
});
console.log("ok  client config");

const calls = [];
const fakeFetch = async (url, init) => {
  calls.push({ url, init });
  if (url.includes("oauth2")) return { ok: true, status: 200, json: async () => ({ access_token: "ya29.test", expires_in: 3600 }) };
  return { ok: true, status: 200 };
};
const data = { type: "ping", name: "Zo\u00eb", ts: "1234567890" };
assert.deepEqual(await sendFcm(env, "device-token-abcdefghijklmnop", data, fakeFetch), { ok: true, status: 200, gone: false });

const assertion = new URLSearchParams(calls[0].init.body).get("assertion");
const [h, c, sig] = assertion.split(".");
assert.deepEqual(JSON.parse(Buffer.from(h, "base64url")), { alg: "RS256", typ: "JWT" });
const claims = JSON.parse(Buffer.from(c, "base64url"));
assert.equal(claims.iss, "sender@friendlamp-test.iam.gserviceaccount.com");
assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
assert.equal(claims.scope, "https://www.googleapis.com/auth/firebase.messaging");
assert.ok(crypto.verify("sha256", Buffer.from(`${h}.${c}`), publicKey, Buffer.from(sig, "base64url")));
console.log("ok  sign-in assertion verifies");

assert.equal(calls[1].url, "https://fcm.googleapis.com/v1/projects/friendlamp-test/messages:send");
assert.equal(calls[1].init.headers.Authorization, "Bearer ya29.test");
assert.deepEqual(JSON.parse(calls[1].init.body), {
  message: { token: "device-token-abcdefghijklmnop", data, android: { priority: "HIGH", ttl: "3600s" } },
});
console.log("ok  message request");

// The sign-in token is reused; an unknown device is reported as gone.
const before = calls.length;
const gone = await sendFcm(env, "old-token-abcdefghijklmnopqrstu", data, async (url, init) => {
  calls.push({ url, init });
  return { ok: false, status: 404 };
});
assert.equal(calls.length, before + 1);
assert.equal(gone.gone, true);
assert.deepEqual(await sendFcm({}, "x".repeat(30), data, fakeFetch), { ok: false, status: 0, gone: false });
console.log("ok  token reuse, gone device, not configured");
