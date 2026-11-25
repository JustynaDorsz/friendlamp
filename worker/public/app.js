// FriendLamp client. Opening the app tells the other person you are thinking of them.

(() => {
  const STORAGE_KEY = "friendlamp";
  const AUTO_PING_GAP_MS = 30 * 1000; // opening the app twice within this time sends once
  // How long the lamp stays lit after the other person thinks of you.
  // Keep equal to LIT_MS in sw.js. (The Android app has its own setting.)
  const LIT_MS = 30 * 60 * 1000;
  const QUICK_CLOSE_MS = 1500; // quick send: how long "Sent" shows before the app closes

  const $ = (id) => document.getElementById(id);
  const hasPush = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;

  let cfg = load();
  let state = null;
  let clockOffset = 0; // server time minus this phone's time
  let lastAutoPing = 0;
  let quietUntil = 0; // opened from a notification: look, don't answer automatically
  let subscribing = false;
  let notifyError = "";

  function load() {
    try {
      const v = JSON.parse(localStorage.getItem(STORAGE_KEY));
      return v && v.id && v.code ? v : null;
    } catch (_) {
      return null;
    }
  }
  function save(v) {
    try {
      if (v) localStorage.setItem(STORAGE_KEY, JSON.stringify(v));
      else localStorage.removeItem(STORAGE_KEY);
    } catch (_) {}
  }

  async function api(path, extra, creds = cfg) {
    const res = await fetch("/api/" + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: creds.code, id: creds.id, ...extra }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(data.error || "Something went wrong. Try again.");
      err.status = res.status;
      throw err;
    }
    return data;
  }

  function toast(message) {
    const el = $("toast");
    el.textContent = message;
    el.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => el.classList.remove("show"), 2600);
  }

  function ago(ts) {
    const s = Math.max(0, Math.round((Date.now() + clockOffset - ts) / 1000));
    if (s < 45) return "just now";
    const m = Math.round(s / 60);
    if (m < 60) return m + " min ago";
    const h = Math.round(m / 60);
    if (h < 24) return h + (h === 1 ? " hour ago" : " hours ago");
    const d = Math.round(h / 24);
    return d + (d === 1 ? " day ago" : " days ago");
  }

  // Recent times read "5 min ago"; older ones also show the clock time or the date.
  function when(ts) {
    const age = Date.now() + clockOffset - ts;
    if (age < 60 * 60 * 1000) return ago(ts);
    const d = new Date(ts);
    const clock = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    const midnight = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const days = Math.round((midnight(new Date()) - midnight(d)) / 86400000);
    if (days <= 0) return ago(ts) + " · " + clock;
    if (days === 1) return "Yesterday · " + clock;
    return d.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" }) + " · " + clock;
  }

  // ---------- Views ----------

  function showSetup(message) {
    $("lampView").hidden = true;
    $("footer").hidden = true;
    $("setup").hidden = false;
    $("setupError").textContent = message || "";
  }

  function showLamp() {
    $("setup").hidden = true;
    $("lampView").hidden = false;
    $("footer").hidden = false;
    render();
    renderNotify();
    renderQuick();
  }

  function render() {
    if (!state) return;
    const other = state.other;
    let headline;
    let hint = "";
    let times = [];
    let glow = 0;

    if (!other) {
      headline = "Waiting for the other device";
      hint = "Open Update there and enter the same code, with a different name.";
    } else if (!other.lastPing) {
      headline = "Nothing received yet";
    } else {
      const lit = Date.now() + clockOffset - other.lastPing < LIT_MS;
      headline = lit ? "Status check received" : "Last status check";
      times = other.history && other.history.length ? other.history : [other.lastPing];
      glow = lit ? 1 : 0;
    }

    $("headline").textContent = headline;
    $("hint").textContent = hint;
    $("hint").hidden = !hint;

    // Newest first. Rows are reused so the list keeps its scroll position.
    const list = $("history");
    while (list.children.length > times.length) list.lastChild.remove();
    while (list.children.length < times.length) list.appendChild(document.createElement("li"));
    times.forEach((ts, i) => {
      const text = when(ts);
      if (list.children[i].textContent !== text) list.children[i].textContent = text;
    });
    $("mine").textContent =
      other && state.me.lastPing ? "Sent: " + ago(state.me.lastPing) : "";
    document.documentElement.style.setProperty("--glow", glow.toFixed(3));
  }

  function setState(next) {
    state = next;
    if (typeof next.now === "number") clockOffset = next.now - Date.now();
    render();
    renderNotify();
  }

  // ---------- Talking to the server ----------

  function handleFailure(err) {
    if (err.status === 403) {
      // The server no longer knows this phone (for example after a reset).
      cfg = null;
      save(null);
      showSetup("This phone is no longer connected. Enter your name and code again.");
    } else {
      toast(err.message === "Failed to fetch" ? "No connection" : err.message);
    }
  }

  async function refresh() {
    if (!cfg) return;
    try {
      setState(await api("state"));
    } catch (err) {
      handleFailure(err);
    }
  }

  async function ping() {
    if (!cfg) return;
    const lamp = $("lamp");
    lamp.classList.remove("sending");
    void lamp.offsetWidth; // restart the animation
    lamp.classList.add("sending");
    try {
      const res = await api("ping");
      setState(res);
      if (!res.other) toast("The other device hasn’t joined yet");
      else if (res.repeat) toast("Already sent a moment ago");
      else if (res.sent) toast("Sent");
      else if (!res.other.notifications) toast("Sent. The other device has notifications off.");
      else toast("Saved, but the notification didn’t go through");
    } catch (err) {
      handleFailure(err);
    }
  }

  async function autoPing() {
    if (!cfg || document.visibilityState !== "visible") return;
    markSeen();
    const now = Date.now();
    if (now < quietUntil) {
      refresh();
      return;
    }
    if (now - lastAutoPing < AUTO_PING_GAP_MS) {
      await refresh();
      if (cfg && cfg.quick) quickClose("Already sent");
      return;
    }
    lastAutoPing = now;
    await ping();
    if (cfg && cfg.quick) quickClose("Sent");
  }

  // The lamp has been looked at: remove the notification and the icon badge.
  function markSeen() {
    if (navigator.clearAppBadge) navigator.clearAppBadge().catch(() => {});
    if (!("serviceWorker" in navigator)) return;
    navigator.serviceWorker
      .getRegistration()
      .then((reg) => (reg && reg.getNotifications ? reg.getNotifications() : []))
      .then((list) => list.forEach((n) => n.close()))
      .catch(() => {});
  }

  // Quick send: show a short confirmation, then close the app. Tapping the
  // screen keeps it open. Browsers that do not allow closing just stay open.
  function quickClose(label) {
    const cover = $("quickCover");
    if (!cover.hidden) return;
    $("quickText").textContent = label;
    cover.hidden = false;
    const timer = setTimeout(() => {
      window.close();
      setTimeout(() => (cover.hidden = true), 400); // still here: closing is not allowed
    }, QUICK_CLOSE_MS);
    cover.onclick = () => {
      clearTimeout(timer);
      cover.hidden = true;
    };
  }

  // ---------- Notifications ----------

  function keyBytes(b64u) {
    const s = atob(b64u.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(s, (c) => c.charCodeAt(0));
  }
  function sameKey(buffer, b64u) {
    if (!buffer) return false;
    const a = new Uint8Array(buffer);
    const b = keyBytes(b64u);
    return a.length === b.length && a.every((v, i) => v === b[i]);
  }

  // Makes sure this phone has a push subscription and the server knows it.
  async function syncSubscription() {
    if (!hasPush || !state || !state.key || Notification.permission !== "granted" || subscribing) return;
    subscribing = true;
    notifyError = "";
    try {
      const reg = await navigator.serviceWorker.ready;
      let sub = await reg.pushManager.getSubscription();
      if (sub && !sameKey(sub.options.applicationServerKey, state.key)) {
        await sub.unsubscribe();
        sub = null;
      }
      const isNew = !sub;
      if (!sub) {
        sub = await reg.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: keyBytes(state.key),
        });
      }
      if (isNew || !state.me.notifications || cfg.endpoint !== sub.endpoint) {
        await api("subscribe", { subscription: sub.toJSON() });
        cfg.endpoint = sub.endpoint;
        save(cfg);
        state.me.notifications = true;
      }
    } catch (err) {
      console.log("subscribe failed", err);
      notifyError = (err && (err.message || err.name)) || "unknown error";
    } finally {
      subscribing = false;
      renderNotify();
    }
  }

  function renderNotify() {
    const note = $("notifyNote");
    const button = $("notifyButton");
    let message = "";
    let showButton = false;

    if (!hasPush) {
      const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
      const installed = window.matchMedia("(display-mode: standalone)").matches || navigator.standalone;
      message =
        ios && !installed
          ? "To get notifications on iPhone: tap Share, then “Add to Home Screen”, and open Update from there."
          : "This browser can’t show notifications.";
    } else if (Notification.permission === "denied") {
      message = "Notifications are blocked for this app. Allow them in the site settings of this browser or phone.";
    } else if (Notification.permission === "default") {
      message = "Get a notification for each status check.";
      showButton = true;
    } else if (state && !state.me.notifications && !subscribing) {
      showButton = true;
      if (notifyError) message = "Couldn’t turn on notifications: " + notifyError;
    } else if (state && state.me.notifications) {
      message = "Notifications are on for this device.";
    }

    note.textContent = message;
    note.hidden = !message;
    button.hidden = !showButton;
  }

  $("notifyButton").addEventListener("click", async () => {
    const button = $("notifyButton");
    button.disabled = true;
    try {
      // Must be asked directly from a tap, which is why this is a button.
      const permission = await Notification.requestPermission();
      if (permission === "granted") {
        await syncSubscription();
        if (state && state.me.notifications) toast("Notifications are on");
        else toast("Couldn’t turn on notifications. Try again.");
      }
    } finally {
      button.disabled = false;
      renderNotify();
    }
  });

  // ---------- Setup and reset ----------

  $("setupForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = $("setupButton");
    const creds = {
      id: crypto.randomUUID(),
      name: $("name").value.trim(),
      code: $("code").value.trim(),
    };
    if (!creds.name || creds.code.length < 6) {
      $("setupError").textContent = "Enter your name and a code of at least 6 characters.";
      return;
    }
    button.disabled = true;
    $("setupError").textContent = "";
    try {
      const joined = await api("join", { name: creds.name }, creds);
      cfg = creds;
      save(cfg);
      lastAutoPing = Date.now(); // setting up is not a ping
      state = null;
      showLamp();
      setState(joined);
      syncSubscription();
    } catch (err) {
      $("setupError").textContent = err.message === "Failed to fetch" ? "No connection. Try again." : err.message;
    } finally {
      button.disabled = false;
    }
  });

  let resetArmed = 0;
  $("resetButton").addEventListener("click", async () => {
    const button = $("resetButton");
    if (Date.now() > resetArmed) {
      resetArmed = Date.now() + 4000;
      button.textContent = "Tap again to disconnect this phone";
      setTimeout(() => {
        resetArmed = 0;
        button.textContent = "Reset this phone";
      }, 4000);
      return;
    }
    resetArmed = 0;
    button.textContent = "Reset this phone";
    try {
      await api("leave");
    } catch (_) {}
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      const sub = reg && (await reg.pushManager.getSubscription());
      if (sub) await sub.unsubscribe();
    } catch (_) {}
    cfg = null;
    state = null;
    save(null);
    document.documentElement.style.setProperty("--glow", "0");
    showSetup();
  });

  // ---------- Quick send setting ----------

  function renderQuick() {
    $("quickToggle").checked = !!(cfg && cfg.quick);
    $("quickHelp").hidden = !(cfg && cfg.quick);
  }
  $("quickToggle").addEventListener("change", () => {
    if (!cfg) return;
    cfg.quick = $("quickToggle").checked;
    save(cfg);
    renderQuick();
  });

  // ---------- Start ----------

  $("lamp").addEventListener("click", () => {
    lastAutoPing = Date.now();
    ping();
  });

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("sw.js").catch((err) => console.log("sw failed", err));
    navigator.serviceWorker.addEventListener("message", (event) => {
      const type = event.data && event.data.type;
      if (type === "from-notification") {
        quietUntil = Date.now() + 3000;
        refresh();
      } else if (type === "refresh") {
        refresh();
      }
    });
  }

  // ?n=1: opened from a notification. ?view=1: opened with "Open lamp". Neither sends.
  const params = new URLSearchParams(location.search);
  if (params.has("n") || params.has("view")) {
    quietUntil = Date.now() + 3000;
    history.replaceState(null, "", location.pathname);
  }

  document.addEventListener("visibilitychange", () => {
    // Small delay so a "from-notification" message can arrive first.
    if (document.visibilityState === "visible") setTimeout(autoPing, 600);
  });

  setInterval(render, 5 * 1000);
  setInterval(() => {
    if (document.visibilityState === "visible") refresh();
  }, 60 * 1000);

  if (cfg) {
    showLamp();
    (async () => {
      markSeen();
      if (Date.now() < quietUntil) await refresh();
      else {
        lastAutoPing = Date.now();
        await ping();
        if (cfg && cfg.quick) quickClose("Sent");
      }
      syncSubscription();
    })();
  } else {
    showSetup();
  }
})();
