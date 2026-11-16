<p align="center">
  <img src="brand/noeud.svg" alt="" width="120">
</p>

<h1 align="center">Noeud for Android</h1>

<p align="center">Simple, very light reminders that notify you on your Android phone.</p>

# Features

- The whole app is smaller than a single photo taken with your phone, and it needs nothing else installed.
- Works on its own, with no account and no server.
- Type when as a duration like `8h52` or `7w1h3s`, a time like `18:00` or a day like `tomorrow 9:00`, and see exactly when it will notify you before adding it.
- Notifies you on the minute, with Done and Delay right on the notification.
- A reminder that came due while the phone was off notifies you as soon as it starts again.
- The times you use most come first, for new reminders and for snoozing.
- Syncs with your own [Noeud](https://github.com/Synaelle/noeud) server once you add it.
- Offers each new version when it starts, and installs it for you.

# Installing

Noeud needs Android 8 or later.

1. On your phone, open [the latest release](https://github.com/Synaelle/noeud-android/releases/latest) and tap `noeud-<version>.apk`.
2. Open the download. The first time, Android asks to allow installs from your browser. Turn on Allow from this source, go back and tap Install.
3. Open Noeud, add a reminder, and allow notifications when it asks.

When a new version is out, Noeud shows it when it starts. Tap Install, and the first time allow installs from Noeud the same way. Your reminders stay.

## Syncing with your server

Tap the gear, enter your [Noeud server](https://github.com/Synaelle/noeud)'s address and a token made for the app, then tap Connect. Make the token on the server, in the folder that holds Noeud's `compose.yml`:

```sh
docker compose exec server noeud-server token create android
```

# Building

Build it with JDK 21 and the Android SDK.

```sh
./gradlew assembleDebug
```

This gives `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew testDebugUnitTest` runs the tests.
