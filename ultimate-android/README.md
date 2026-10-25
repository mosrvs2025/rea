# Ultimate

One Android app that holds a growing set of small, offline tools. No ads, no analytics, no accounts. The manifest has no `INTERNET` permission, so the app cannot reach the network.

This is an independent app in this repository's `ultimate-android/` directory, unrelated to REA. It supersedes the earlier standalone `squeeze-android/` and `pluck-android/` projects, whose code now lives here as tools.

## Tools

| Tool | What it does |
| --- | --- |
| **Squeeze** | Shrinks videos with the phone's hardware encoders (HEVC when available), with size targets, a live synced before/after player and measured stats. |
| **Video to audio** | Extracts a video's sound as **MP3** (real LAME encoding), **M4A** (the original AAC, copied untouched) or **WAV**. Trim, mono, bitrate choice, live waveform, in-app player. |
| **Pluck** | Lists installed apps, extracts their APKs (split APKs become one `.apks` bundle), and shows details, permissions, signing certificate and an offline ad/tracker SDK scan. |

The **Library** tab lists everything any tool made, filterable by tool. The **Activity** tab shows each tool's current or last run with a live terminal log (codecs chosen, encoder names, real bitrates, speed, files written); the same log appears on every progress screen. Videos shared to Ultimate from other apps go to Squeeze or Video to audio (you choose when both apply).

### Pluck details

- Apps list: seven sorts (reversible), User/System/All, and combinable quick filters (has ads, sideloaded, Play Store, split APK, under 30 MB, disabled). Scanned apps show a tracker badge; every row has a menu (extract, open, app info, copy package, select, uninstall).
- Ads & trackers report: every scanned app, searchable, sortable, filterable by SDK kind or a single SDK, plus a ranking of SDKs across your apps. Every row opens the app.
- Extract options: **APK** (installs as-is) or **Compact**, a maximum-compression pack for sending, shown with estimated size and checks against 16/25/30/100 MB before anything is written. Modern APKs store code, native libraries and resources uncompressed so apps start fast, so packing typically halves them (measured on four real APKs: 49 to 53% smaller). The APK itself cannot be made smaller and still install as the same app, because its signature covers every byte; unpacking gives back the untouched original. Single apps become `.zip`; split apps become a compressed `.apks` that split installers such as SAI read directly.

## Editions: building a tool as its own app

One codebase builds several apps. `app/build.gradle.kts` defines an edition per product flavor, each with its own app id, name, icon (`src/<edition>/res`) and manifest additions (`src/<edition>/AndroidManifest.xml`, e.g. share targets and the app-listing permission only where needed). `HAS_*` flags switch tools on; they are compile-time constants, so R8 strips every tool an edition does not include.

| Edition | Command | App id |
| --- | --- | --- |
| Ultimate (all tools) | `gradle :app:assembleUltimateRelease` | `app.ultimate` |
| Squeeze | `gradle :app:assembleSqueezeRelease` | `app.squeeze` |
| Video to Audio | `gradle :app:assembleAudioRelease` | `app.ultimate.audio` |
| Pluck | `gradle :app:assemblePluckRelease` | `app.pluck` |

A single-tool edition opens straight into that tool; its top bar links to the Library and Activity. To add an edition, copy a flavor block, set its flags, and add `src/<edition>/` resources.

## Adding a tool

1. Create a package under `app/src/main/java/app/ultimate/<tool>/`.
2. Implement `core/Tool.kt`: an id, title, icon, a Compose `Screen`, a `library` flow of `LibraryItem`s, and how to remove one.
3. If it does long work, make its manager a `core/WorkSource` and register it in `UltimateApp.onCreate`. One foreground service and notification covers every tool, with a cancel action per tool.
4. Add the tool to `UltimateApp.tools` behind a new `HAS_<TOOL>` flag. Home, the Library, the Activity tab and share routing pick it up with no other changes.
5. Give the manager a `JobLog` and write to it at each decision point; the terminal panels show it.

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
gradle :app:testUltimateDebugUnitTest
gradle :app:assembleRelease    # every edition: app/build/outputs/apk/<edition>/release/
```

The release build is signed with the debug key for sideloading. Configure your own keystore before distributing.
