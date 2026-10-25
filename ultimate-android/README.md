# Ultimate

One Android app that holds a growing set of small, offline tools. No ads, no analytics, no accounts. The manifest has no `INTERNET` permission, so the app cannot reach the network.

This is an independent app in this repository's `ultimate-android/` directory, unrelated to REA. It supersedes the earlier standalone `squeeze-android/` and `pluck-android/` projects, whose code now lives here as tools.

## Tools

| Tool | What it does |
| --- | --- |
| **Squeeze** | Shrinks videos with the phone's hardware encoders (HEVC when available), with size targets, a live synced before/after player and measured stats. |
| **Video to audio** | Extracts a video's sound as **MP3** (real LAME encoding), **M4A** (the original AAC, copied untouched) or **WAV**. Trim, mono, bitrate choice, live waveform, in-app player. |
| **Pluck** | Lists installed apps, extracts their APKs (split APKs become one `.apks` bundle), and shows details, permissions, signing certificate and an offline ad/tracker SDK scan. |

The **Library** tab lists everything any tool made, filterable by tool. Videos shared to Ultimate from other apps go to Squeeze or Video to audio (you choose when both apply).

## Adding a tool

1. Create a package under `app/src/main/java/app/ultimate/<tool>/`.
2. Implement `core/Tool.kt`: an id, title, icon, a Compose `Screen`, a `library` flow of `LibraryItem`s, and how to remove one.
3. If it does long work, make its manager a `core/WorkSource` and register it in `UltimateApp.onCreate`. One foreground service and notification covers every tool, with a cancel action per tool.
4. Add the tool to `UltimateApp.tools`. Home, the Library and share routing pick it up with no other changes.

Shared building blocks live in `core/`: formatting, theme, `JsonFile` (atomic storage), `Waveform`, `AudioPlayerBar`, `VideoThumb`, `AppIcon`, `SuccessCheck`, `rememberNotificationConsent`.

## Notes and limits

- **MP3 encoding** uses [jump3r](https://git.iem.at/sciss/jump3r), a pure-Java port of LAME 3.98 (LGPL 2.1+), pulled in unmodified as a Maven dependency. Android has no `javax.sound`, so `app/src/main/java/javax/sound/sampled/AudioFormat.java` is a minimal stand-in covering only the members the encoder uses. The encoder was verified on a JVM with `javax.sound` removed, producing valid MP3 that `ffprobe` reads back at the requested bitrate. Encoding on a phone has not been tested.
- **QUERY_ALL_PACKAGES** is needed to list every app on Android 11+. It is restricted by Google Play policy, so this app is intended for sideloading.
- **Save locations:** videos in `Movies/Squeeze`, audio in `Music/Ultimate`, APKs in `Downloads/Pluck`.
- Not tested on a physical device in this repository.

## Build

Requirements: JDK 17+, Gradle 8.9+, Android SDK with platform 35 and build-tools 35.

```sh
export ANDROID_HOME=/path/to/android-sdk
gradle :app:testDebugUnitTest
gradle :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key for sideloading. Configure your own keystore before distributing.
