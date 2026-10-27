# EarTap — triple-tap your earbuds to record

Android app (Android 11+). Triple-tap your Bluetooth earbuds → recording starts (1 beep). Triple-tap again → recording is saved (2 beeps).

## How it works

```
Triple tap → earbuds send "Previous track" (Bluetooth AVRCP)
           → Android delivers it to the app that played audio last
           → EarTap (plays 0.5 s of silence so it is that app)
           → start / stop MediaRecorder
           → file saved to  Music/EarTap/EarTap_<date>_<time>.m4a
```

## Build the APK

### Option A — GitHub (works from a phone, no PC)
1. Put this project in a GitHub repository (e.g. let Claude push it to an empty repo you create).
2. GitHub runs `.github/workflows/build.yml` automatically (Actions tab).
3. Open the finished run → **Artifacts → EarTap-apk** → download → unzip → `app-debug.apk`.

### Option B — PC with Android Studio
1. Android Studio → **Open** → pick this `EarTap` folder.
2. Wait for Gradle sync → connect phone with USB debugging → **Run ▶**.
   (Or **Build → Build APK(s)**, file at `app/build/outputs/apk/debug/app-debug.apk`.)

## Install & use
1. Copy `app-debug.apk` to the phone → open it → allow "Install unknown apps" for your browser/Files app.
2. Open **EarTap** → **Start listening** → allow Microphone + Notifications.
3. **Battery settings** button → set EarTap to **Unrestricted** (important on Xiaomi/Redmi, Realme, Oppo, Vivo, OnePlus).
4. Triple-tap the earbuds → 1 beep → speak → triple-tap → 2 beeps → saved.

## Settings
- **Trigger**: Previous track (default) / Next track / Play-Pause — pick whatever your tap gesture sends.
- **Earbud mic** (off by default): records from the earbud mic instead of the phone mic. Lower quality (16 kHz), and on some earbuds the buttons stop working while the earbud mic is on — use the notification's **Stop recording** button then.

## Known limits
- If you start music in another app (Spotify, YouTube…), that app takes the earbud buttons. Open EarTap or tap **Record now** in its notification to take them back.
- While EarTap is listening, the trigger tap no longer does "previous track" in music apps.
- If a recording is shorter than ~1 second it is discarded.
