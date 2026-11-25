# FriendLamp

A tiny app for two people. Opening it tells the other person you are thinking of them: they get a notification, and their lamp shows who and how long ago.

It runs on a free Cloudflare Worker, which hosts the app, keeps the two timestamps in a small database, and sends the notifications.

## Put it online (once, about 10 minutes)

1. Create a free account at https://dash.cloudflare.com/sign-up
2. Install Node.js (LTS) from https://nodejs.org
3. On Windows, double-click `deploy.bat` and follow it. Otherwise open a terminal in this folder and run:

   ```
   npm install
   npx wrangler login
   npm run deploy
   ```

   - `wrangler login` opens the browser to connect your Cloudflare account.
   - The first deploy asks to create a database called `friendlamp`. Answer yes.
   - If asked, pick a `workers.dev` subdomain.

4. The deploy prints your address, like `https://friendlamp.YOUR-NAME.workers.dev`. That is the app.

If the deploy complains about the database, run `npx wrangler d1 create friendlamp`, copy the `database_id` it prints into `wrangler.jsonc` next to `database_name`, and deploy again.

## Install on both phones

**iPhone** (iOS 16.4 or newer): open the address in Safari, tap Share, then "Add to Home Screen". Open FriendLamp from the home screen icon. Notifications only work from the installed icon, not from a Safari tab.

**Android**: open the address in Chrome, then menu, "Add to Home screen" (or "Install app").

Then, on each phone:

1. Enter your name and the same secret code on both phones.
2. Tap "Turn on notifications" and allow them.

## Using it

- Tap the FriendLamp icon. That is all: the other person gets "<your name> is thinking of you".
- The lamp glows when the other person thought of you and fades over an hour. Under it is a list of when they lit it, newest first.
- Tapping a notification opens the app without sending one back. To answer, tap the lamp.
- Opening the app twice within 30 seconds sends only once.

## Good to know

- The secret code works like a password. Anyone who knows it could join, so pick something that is not guessable. One code holds two people (two names).
- More devices: a person can use several (phone, computer, the installed app and a browser tab each count). Enter the same code and exactly the same name on each one. Up to 5 per person; the oldest is dropped after that.
- Cost: the free plan allows 100,000 requests a day. Two people will use a few dozen.
- To change texts or colours, edit the files in `public/` and run `npm run deploy` again.

## Files

- `public/` - the app: page, script, service worker (notifications), icons
- `src/worker.js` - the server: pairing, pings, state
- `src/webpush.js` - sends the push notifications
- `test/` - `npm test` checks the notification encryption
