# Pluck

An Android APK extractor with no ads, no analytics and no network access. The manifest has no `INTERNET` permission, so the app cannot reach the network.

This is an independent app in this repository's `pluck-android/` directory. It is unrelated to REA.

## What it does

- **Apps:** every installed app with search, User/System/All filters and four sorts. Long-press to multi-select.
- **Extract:** saves the installed APK to `Downloads/Pluck` without any storage permission. Apps made of split APKs are saved as one `.apks` bundle. Extraction runs in a foreground service, so you can leave the app, and half-written files are removed on cancel or failure.
- **Extract & share:** extracts, then opens the share sheet.
- **Details:** version, size, install and update dates, install source, minimum and target Android, native code ABIs, component counts, permissions (sensitive first, granted or not), signing certificate SHA-256 (tap to copy), and the split APK list.
- **Saved:** history of extractions with the SHA-256 of each base APK; entries disappear if the file is deleted elsewhere.
- **Insights:** biggest apps, where your apps came from (Play Store, other store, sideloaded), apps asking for the most sensitive permissions, apps built for old Android, and an offline ad/tracker SDK scan.

## Ad and tracker scan

`TrackerScanner` searches an APK's dex files for the class-name prefixes of about 30 well-known ad, analytics and crash-reporting SDKs. It is a heuristic: SDKs that were renamed by an obfuscator are missed, and a match shows the code is present, not that it is in use. Results are cached per app version.

## Permissions

| Permission | Why |
| --- | --- |
| `QUERY_ALL_PACKAGES` | Listing every installed app is the app's purpose. Android 11+ hides most apps without it. |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Progress while extracting in the background. |

`QUERY_ALL_PACKAGES` is restricted by Google Play policy; this app is intended for sideloading or F-Droid-style distribution.

## Limits

- Extracted APKs are copies of what is installed. Some apps verify their own signature or ship splits that need a split installer such as SAI.
- Whether a given device keeps the `.apks` extension in Downloads has not been verified. The Saved tab shows the name the system actually used.
- Not tested on a physical device in this repository.

## Build

Requirements: JDK 17+, Gradle 8.9+, Android SDK with platform 35 and build-tools 35.

```sh
export ANDROID_HOME=/path/to/android-sdk
gradle :app:testDebugUnitTest
gradle :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key for sideloading. Configure your own keystore before distributing.
