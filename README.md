# FriendLamp

A lamp for two people. One tap tells the other person you are thinking of them; their lamp lights up.

- `worker/` - the server and the web app (Cloudflare Worker). See `worker/README.md`.
- `android/` - the Android app with the home-screen widget. Tapping the widget sends without opening anything; the widget is lit while the other person's tap is recent.

## Android app

GitHub builds the APK on every change to `android/` (Actions tab, workflow "Android APK") and publishes it as a release. Download link for the newest build:

https://github.com/JustynaDorsz/friendlamp/releases/latest/download/FriendLamp.apk

Open it on the phone and install. The build needs the repository secret `ANDROID_KEYSTORE_PASSWORD`, the password of the encrypted signing key `android/app/friendlamp-release.jks`.

How long the lamp stays lit is `LIT_MS` in `android/app/src/main/java/com/friendlamp/app/Config.kt` (currently 2 minutes for testing), and in `worker/public/app.js` and `worker/public/sw.js` for the web app.

## Instant notifications on Android (Firebase)

The app receives taps through Firebase Cloud Messaging. The Worker needs:

- secret `FCM_SERVICE_ACCOUNT`: the service-account JSON from Firebase (Project settings, Service accounts, Generate new private key)
- variable `FCM_APP_ID`: the Android app's "App ID" from Firebase (looks like `1:123456789012:android:abc123`)
- variable `FCM_API_KEY`: the "Web API key" / `current_key` of the Firebase project

The Firebase Android app must be registered with the package name `com.friendlamp.app`.
Without these, the widget still sends, and checks for news about every 30 minutes.
