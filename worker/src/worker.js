// FriendLamp server: pairs two people by a shared code, records "thinking of
// you" pings, and sends a push notification to the other person.
// A person is identified by their name and can use several devices
// (phone, computer, the installed app and a browser tab all count separately).

import { generateVapidKeys, sendPush } from "./webpush.js";
import { fcmClientConfig, sendFcm } from "./fcm.js";

const PING_COOLDOWN_MS = 5000;
const MAX_DEVICES_PER_PERSON = 5;
const HISTORY_SHOWN = 20;
const HISTORY_KEPT = 50;

let schemaReady = false;
let vapidCache = null;

async function ensureSchema(db) {
  if (schemaReady) return;
  await db.batch([
    db.prepare(
      `CREATE TABLE IF NOT EXISTS members (
         pair TEXT NOT NULL,
         id TEXT NOT NULL,
         name TEXT NOT NULL,
         last_ping INTEGER,
         subscription TEXT,
         PRIMARY KEY (pair, id)
       )`
    ),
    db.prepare(`CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS pings (pair TEXT NOT NULL, person TEXT NOT NULL, ts INTEGER NOT NULL)`),
    db.prepare(`CREATE INDEX IF NOT EXISTS pings_lookup ON pings (pair, person, ts)`),
  ]);
  schemaReady = true;
}

// The notification signing keys are created on first use and kept in the database.
async function getVapid(db) {
  if (vapidCache) return vapidCache;
  let row = await db.prepare(`SELECT value FROM config WHERE key = 'vapid'`).first();
  if (!row) {
    const fresh = JSON.stringify(await generateVapidKeys());
    await db.prepare(`INSERT OR IGNORE INTO config (key, value) VALUES ('vapid', ?)`).bind(fresh).run();
    row = await db.prepare(`SELECT value FROM config WHERE key = 'vapid'`).first();
  }
  vapidCache = JSON.parse(row.value);
  return vapidCache;
}

async function pairKey(code) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(code.trim().toLowerCase()));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function text(value, min, max, label) {
  if (typeof value !== "string") throw new ApiError(400, `${label} is missing`);
  const v = value.trim();
  if (v.length < min || v.length > max) {
    throw new ApiError(400, `${label} must be ${min}-${max} characters`);
  }
  return v;
}

async function members(db, pair) {
  const { results } = await db
    .prepare(`SELECT id, name, last_ping, subscription FROM members WHERE pair = ? ORDER BY rowid`)
    .bind(pair)
    .all();
  return results;
}

const personKey = (name) => name.trim().toLowerCase();
const latestPing = (rows) => rows.reduce((max, m) => Math.max(max, m.last_ping || 0), 0) || null;

// Splits the devices of a pair into "mine" (same name as this device) and "theirs".
function sides(all, id) {
  const me = all.find((m) => m.id === id);
  if (!me) throw new ApiError(403, "This device has not joined this lamp");
  const key = personKey(me.name);
  return {
    me,
    mine: all.filter((m) => personKey(m.name) === key),
    theirs: all.filter((m) => personKey(m.name) !== key),
  };
}

// The recent ping times of one person, newest first.
async function historyOf(db, pair, person) {
  const { results } = await db
    .prepare(`SELECT ts FROM pings WHERE pair = ? AND person = ? ORDER BY ts DESC LIMIT ?`)
    .bind(pair, person, HISTORY_SHOWN)
    .all();
  return results.map((r) => r.ts);
}

async function stateFor(db, pair, all, id, key) {
  const { me, mine, theirs } = sides(all, id);
  let other = null;
  if (theirs.length) {
    const newest = theirs.reduce((a, b) => ((b.last_ping || 0) > (a.last_ping || 0) ? b : a));
    other = {
      name: newest.name,
      lastPing: latestPing(theirs),
      notifications: theirs.some((m) => !!m.subscription),
      history: await historyOf(db, pair, personKey(newest.name)),
    };
  }
  return {
    now: Date.now(),
    key,
    me: {
      name: me.name,
      lastPing: latestPing(mine),
      notifications: !!me.subscription,
      history: await historyOf(db, pair, personKey(me.name)),
    },
    other,
  };
}

async function handleApi(request, env, url) {
  const db = env.DB;
  await ensureSchema(db);

  if (url.pathname === "/api/key" && request.method === "GET") {
    return json({ publicKey: (await getVapid(db)).publicKey });
  }
  if (url.pathname === "/api/fcm-config" && request.method === "GET") {
    return json(fcmClientConfig(env));
  }
  if (request.method !== "POST") throw new ApiError(405, "Method not allowed");

  let body;
  try {
    body = await request.json();
  } catch {
    throw new ApiError(400, "Invalid request");
  }
  const code = text(body.code, 6, 100, "Code");
  const id = text(body.id, 16, 64, "Device id");
  const pair = await pairKey(code);
  const vapid = await getVapid(db);
  const key = vapid.publicKey;

  switch (url.pathname) {
    case "/api/join": {
      const name = text(body.name, 1, 30, "Name");
      const all = await members(db, pair);
      const existing = all.find((m) => m.id === id);
      const others = all.filter((m) => m.id !== id);
      const names = new Map(others.map((m) => [personKey(m.name), m.name]));
      if (!names.has(personKey(name)) && names.size >= 2) {
        const [a, b] = [...names.values()];
        throw new ApiError(
          409,
          `This code already belongs to ${a} and ${b}. To add another device, enter one of those names.`
        );
      }
      // Too many devices under one name: the oldest one makes room.
      const sameName = others.filter((m) => personKey(m.name) === personKey(name));
      if (sameName.length >= MAX_DEVICES_PER_PERSON) {
        await db.prepare(`DELETE FROM members WHERE pair = ? AND id = ?`).bind(pair, sameName[0].id).run();
      }
      if (existing) {
        await db.prepare(`UPDATE members SET name = ? WHERE pair = ? AND id = ?`).bind(name, pair, id).run();
      } else {
        await db.prepare(`INSERT INTO members (pair, id, name) VALUES (?, ?, ?)`).bind(pair, id, name).run();
      }
      return json(await stateFor(db, pair, await members(db, pair), id, key));
    }

    case "/api/state": {
      return json(await stateFor(db, pair, await members(db, pair), id, key));
    }

    case "/api/subscribe": {
      const sub = body.subscription;
      let stored;
      if (sub === null) {
        // Unsubscribe: this device stays a member but gets no pushes
        // (the Android app does this for a profile that is not in use).
        stored = null;
      } else if (sub && typeof sub.fcm === "string" && sub.fcm.length >= 20 && sub.fcm.length < 4096) {
        // The Android app: a Firebase registration token.
        stored = JSON.stringify({ fcm: sub.fcm });
      } else {
        // A browser: a Web Push subscription.
        const valid =
          sub &&
          typeof sub.endpoint === "string" &&
          sub.endpoint.startsWith("https://") &&
          sub.endpoint.length < 2000 &&
          sub.keys &&
          typeof sub.keys.p256dh === "string" &&
          typeof sub.keys.auth === "string";
        if (!valid) throw new ApiError(400, "Invalid subscription");
        stored = JSON.stringify({
          endpoint: sub.endpoint,
          keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth },
        });
      }
      const res = await db
        .prepare(`UPDATE members SET subscription = ? WHERE pair = ? AND id = ?`)
        .bind(stored, pair, id)
        .run();
      if (!res.meta.changes) throw new ApiError(403, "This device has not joined this lamp");
      return json({ ok: true });
    }

    case "/api/ping": {
      const all = await members(db, pair);
      const { me, mine, theirs } = sides(all, id);
      const now = Date.now();
      const last = latestPing(mine);

      let sent = false;
      const repeat = !!last && now - last < PING_COOLDOWN_MS;
      if (!repeat) {
        const person = personKey(me.name);
        await db.batch([
          db.prepare(`UPDATE members SET last_ping = ? WHERE pair = ? AND id = ?`).bind(now, pair, id),
          db.prepare(`INSERT INTO pings (pair, person, ts) VALUES (?, ?, ?)`).bind(pair, person, now),
          db
            .prepare(
              `DELETE FROM pings WHERE pair = ?1 AND person = ?2 AND ts <=
                 (SELECT ts FROM pings WHERE pair = ?1 AND person = ?2 ORDER BY ts DESC LIMIT 1 OFFSET ?3)`
            )
            .bind(pair, person, HISTORY_KEPT),
        ]);
        me.last_ping = now;

        // Notify every device of the other person.
        // The notification text carries no names.
        const payload = { title: "Update", body: "Status check", ts: now };
        const results = await Promise.all(
          theirs
            .filter((m) => m.subscription)
            .map(async (m) => {
              try {
                const target = JSON.parse(m.subscription);
                const result = target.fcm
                  ? await sendFcm(env, target.fcm, { type: "ping", name: me.name, ts: String(now), to: m.id })
                  : await sendPush(target, payload, vapid, url.origin);
                if (result.gone) {
                  // That device removed the app or revoked notifications.
                  await db
                    .prepare(`UPDATE members SET subscription = NULL WHERE pair = ? AND id = ?`)
                    .bind(pair, m.id)
                    .run();
                  m.subscription = null;
                } else if (!result.ok) {
                  console.log("push failed", result.status);
                }
                return result.ok;
              } catch (err) {
                console.log("push error", err && err.message);
                return false;
              }
            })
        );
        sent = results.some(Boolean);
      }
      return json({ ...(await stateFor(db, pair, all, id, key)), sent, repeat });
    }

    case "/api/leave": {
      await db.prepare(`DELETE FROM members WHERE pair = ? AND id = ?`).bind(pair, id).run();
      return json({ ok: true });
    }

    default:
      throw new ApiError(404, "Not found");
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      try {
        return await handleApi(request, env, url);
      } catch (err) {
        if (err instanceof ApiError) return json({ error: err.message }, err.status);
        console.log("server error", err && err.stack);
        return json({ error: "Server error" }, 500);
      }
    }
    return env.ASSETS.fetch(request);
  },
};
