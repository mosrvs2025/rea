# Orbit — independent game hub

A native Android prototype informed by static inspection of the supplied Epic Games 1.0.6 APK. Original Java/platform UI; no Epic code, artwork, credentials or private API integrations.

## Implemented

- Discover launchable apps that Android exposes through a launcher intent query.
- Auto-classify games using Android application category / game flag.
- Add misclassified games manually through All apps.
- Search library, pin favorites, and launch installed apps.
- Store favorites and manual classifications locally across app restarts.
- Discover links open official stores in the user's browser.
- Background package enumeration, safe handling of unavailable apps, readable empty states and accessibility labels for icon actions.

No broad package visibility, network, installation or notification permission is requested. Browsing uses the external browser. No accounts, tracking SDKs or ads.

## Build

Requires JDK 17, Android SDK 35 and Gradle 8.9:

```sh
cd game-hub
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow provides the same build as an artifact. Never commit APKs or keystores. The initial test APK uses a disposable development signature; rebuilds with another key require uninstalling it first, clearing local favorites.

## Verification

Initial source compiled with Eclipse ECJ 3.38.0 to Java 8 bytecode against Android 35 plus the Android lambda stubs, then D8 (min API 26), AAPT2, zipalign and apksigner from Android build tools 35. APK v2/v3 signatures verified. Manifest and DEX parsed with Androguard. This manual packaging route was used because the container has a Java runtime without javac. The Gradle build and device UI have **not** been run here.

Phone acceptance checks: install → Games empty state → All apps → add a game → Games → search → favorite → Favorites → launch → return → restart → confirm persistence → uninstall another app → confirm refresh → Discover → open store. Check gesture/status-bar insets and large text on Android 15–17.

## Scope and next work

This is an installed-game launcher prototype, not a replacement for Epic's distribution service. No Epic login, account library, entitlements, checkout, free-game claiming, download queue, patching, updates or silent installation. PC games listed by external stores do not run on Android through this app.

Next: device validation, adaptive grid/list layout, recently played records, APK import through Android's system installer with signer/digest checks, and an authorized catalog adapter. Epic authentication needs a documented permitted OAuth integration and real entitlement/install testing before implementation.

See [analysis.md](analysis.md) for evidence and unknowns.
