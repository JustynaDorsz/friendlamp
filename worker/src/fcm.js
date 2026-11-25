// Sends "thinking of you" to the Android app through Firebase Cloud Messaging (HTTP v1).
// Needs the secret FCM_SERVICE_ACCOUNT (the service-account JSON from Firebase) and the
// variables FCM_APP_ID and FCM_API_KEY (from the Firebase Android app settings).

const te = new TextEncoder();

function b64u(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function serviceAccount(env) {
  if (!env.FCM_SERVICE_ACCOUNT) return null;
  try {
    const account = JSON.parse(env.FCM_SERVICE_ACCOUNT);
    return account.client_email && account.private_key && account.project_id ? account : null;
  } catch (_) {
    return null;
  }
}

// What the Android app needs to register with Firebase. None of this is secret.
export function fcmClientConfig(env) {
  const account = serviceAccount(env);
  if (!account || !env.FCM_APP_ID || !env.FCM_API_KEY) return { configured: false };
  return {
    configured: true,
    projectId: account.project_id,
    appId: env.FCM_APP_ID,
    apiKey: env.FCM_API_KEY,
    senderId: String(env.FCM_APP_ID).split(":")[1] || "",
  };
}

let cachedToken = null; // { token, exp, email }, kept in memory only

async function accessToken(account, fetchImpl) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.email === account.client_email && cachedToken.exp - 60 > now) {
    return cachedToken.token;
  }
  const der = Uint8Array.from(
    atob(account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")),
    (c) => c.charCodeAt(0)
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const header = b64u(te.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64u(
    te.encode(
      JSON.stringify({
        iss: account.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      })
    )
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, te.encode(`${header}.${claims}`));
  const res = await fetchImpl("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${b64u(signature)}`,
    }).toString(),
  });
  if (!res.ok) throw new Error("Firebase sign-in failed: " + res.status);
  const data = await res.json();
  cachedToken = { token: data.access_token, exp: now + (data.expires_in || 3600), email: account.client_email };
  return cachedToken.token;
}

// Sends one data message. Returns { ok, status, gone }.
// `data` values must be strings.
export async function sendFcm(env, deviceToken, data, fetchImpl = fetch) {
  const account = serviceAccount(env);
  if (!account) return { ok: false, status: 0, gone: false };
  const token = await accessToken(account, fetchImpl);
  const res = await fetchImpl(
    `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: { token: deviceToken, data, android: { priority: "HIGH", ttl: "3600s" } },
      }),
    }
  );
  return { ok: res.ok, status: res.status, gone: res.status === 404 };
}
