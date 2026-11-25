// FriendLamp service worker: shows the notification and opens the app when tapped.

// How long the "lit" signs (notification, icon badge) stay. Keep equal to LIT_MS in app.js.
// A service worker can only wait a few minutes, so anything longer is cleared
// when the app is next opened instead.
const LIT_MS = 30 * 60 * 1000;
const MAX_WAIT_MS = 4 * 60 * 1000;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (_) {}

  event.waitUntil(
    (async () => {
      await self.registration.showNotification(data.title || "FriendLamp", {
        body: data.body || "Status check",
        icon: "icons/icon-192.png",
        badge: "icons/badge-96.png",
        tag: "friendlamp",
        renotify: true,
        data: { ts: data.ts || 0 },
      });
      // Icon badge where the system supports it (iPhone, desktop). On Android the
      // notification itself puts a dot on the icon.
      if (self.navigator.setAppBadge) self.navigator.setAppBadge(1).catch(() => {});
      // If the app is open, refresh what it shows.
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) client.postMessage({ type: "refresh" });

      // Unlit again: remove this notification and the badge, unless a newer one arrived.
      if (LIT_MS <= MAX_WAIT_MS) {
        await new Promise((resolve) => setTimeout(resolve, LIT_MS));
        const shown = await self.registration.getNotifications({ tag: "friendlamp" });
        const mine = shown.filter((n) => n.data && n.data.ts === (data.ts || 0));
        mine.forEach((n) => n.close());
        if (mine.length === shown.length && self.navigator.clearAppBadge) {
          self.navigator.clearAppBadge().catch(() => {});
        }
      }
    })()
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      if (windows.length) {
        // Tell the app it was opened from a notification, so it does not answer
        // automatically with a ping of its own.
        windows[0].postMessage({ type: "from-notification" });
        return windows[0].focus();
      }
      return self.clients.openWindow("./?n=1");
    })()
  );
});
