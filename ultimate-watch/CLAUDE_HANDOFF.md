# Ultimate Watch — Claude handoff

Goal: independent ad-free AlfredCamera alternative. Hardware available: Galaxy S10, Galaxy Z Flip6, Galaxy S23 Ultra, and a computer. Actual OS versions and computer OS unknown. Keep plain Ultimate offline; Watch is separate.

## Implemented
- Python aiohttp HTTPS server: shared-password login, secure HttpOnly cookie, same-origin mutation checks, latest JPEG per camera in RAM, 16-camera limit; no recording server-side. Viewer and camera privileges are currently identical.
- Browser camera and viewer: 1fps snapshots. Trusted HTTPS required. Camera page needs to stay open.
- android/: NEW UNCOMPILED CameraX source. minSdk29, target35, AGP8.7.3, Kotlin2.0.21. Visible activity requests camera permission then starts LifecycleService with camera foreground type and Stop notification. Authenticates to server then publishes JPEG frames. Optional silent SD MP4 on phone in Android/data/app.ultimate.watch/files/Movies. No APK or Gradle wrapper yet. No Android SDK or Gradle available here.
- alfred-manifest-analysis.json: actual Androguard-decoded manifest, com.ivuu, version2026.21.0, minSdk32,target36. Dedicated foreground camera/legacy camera/FCM services and motion widgets. APK assets contain TFLite models with person/vehicle/pet/package labels, plus yamnet. Presence does not prove runtime usage. No Alfred code/assets/models copied.
- Passed server integration test for auth, frame upload/read, invalid JPEG markers, cross-origin rejection and logout. Python/JS syntax checked previously. No browser camera/hardware test.

## Work order and definition of done
1. Use JDK17, Gradle8.9+, SDK35; generate wrapper with Gradle. From android/: gradle :app:assembleDebug. Fix compile/API errors and produce APK. Review CameraX RGBA pixel padding/color conversion and image rotation using actual outputs.
2. Review lifecycle races: start/stop/restart, permissions, login failure, certificate failure, recording finalization, duplicate service starts. Password must never enter logs or URLs. Current activity reports request rather than real service state; add actual status UI.
3. Configure trusted HTTPS on Mo's computer, private LAN/VPN access. Never disable certificate validation or expose this prototype directly publicly. Determine computer OS for installation/startup instructions.
4. Real phone tests on ALL THREE devices: 30-minute runs, screen locked, app background, Wi-Fi loss/reconnect, stop notification, storage exhaustion, thermal load. Record actual Android versions. Current source has no wake-lock strategy and no proof of screen-off reliability.
5. Add segmented recordings with disk/duration caps and retention; recordings list/playback/export. Current single local MP4 is unbounded, experimental and must not be used for unattended operation. Add reauthentication after server restart and improve capture/network executor separation.
6. Add per-device revocable publisher credentials separate from viewers; bound sessions/login attempts and validate actual JPEG decoding. Shared password implementation is only for trusted prototype users.
7. Upgrade 1fps JPEG relay to H.264/WebRTC full-motion streaming, authenticated signaling, optional TURN. Then motion zones, bounded event recordings, alerts and independently licensed detection models.
8. Further REA/JADX inspection: trace camera lifecycle, network transport, reconnect and storage/event flow. Manifest names alone aren't runtime evidence. Original APK remains user attachment, not committed.

First usable alpha requires installable APK; explicit camera consent/start/stop; demonstrated screen-off capture; authenticated viewer of real feeds; playable finalized MP4; reconnection; bounded storage; setup guide for actual computer. Compilation alone is not a device test.

If Mo assigns agents: Android camera/recording; server/auth/storage; viewer UX; APK investigation and test evidence on separate branches. Agree protocol before concurrent edits. Continue in codex/ultimate-watch; do not merge main until verified.


---

## Status after the first Android build session

Branch `codex/ultimate-watch`. Not merged to main.

### Compiled (checked by building here)
- `cd android && ./gradlew :app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` (about 8.9 MB, debug-signed, minSdk 29, target 35). `lintDebug`: 0 errors, warnings are dependency-version notes. The original source needed only `android.useAndroidX=true` (missing `gradle.properties`) to compile; everything else was rewritten.
- Wrapper generated (`gradlew`, Gradle 8.14); `./gradlew --version` downloaded and ran.

### Tested (automated, run here)
- 12 JVM unit tests: endpoint validation, camera-name rule, password not in `StartRequest.toString()`, backoff growth/cap/reset/jitter, storage-limit clamping, retention planning (age, size cap, never the active file), error classification (certificate vs dropped handshake vs timeout).
- 6 integration tests drive `WatchClient` against the real `server.py` over real TLS (generated certificate, only trusted authority): login, frame post, wrong password is fatal, post without login is session-expired, invalid JPEG is rejected without killing the session, untrusted certificate is fatal, and **server restart then re-login then post works**. They skip themselves if python3+aiohttp or openssl are missing.
- Existing `test_server.py` passes (needs `pip install aiohttp`).

### Implemented but NOT verified on any device
Everything below compiles and is reasoned through, but has never run on a phone or emulator. No Android emulator or phone was available.
- Camera foreground service: duplicate-start handling, stop path that waits (6 s max) for the recording to finalize before releasing the camera, `onDestroy` cleanup, status shared to the UI via `ServiceState`, alert notification on fatal stop.
- Reconnection: exponential backoff 1–60 s with jitter, immediate retry when Android reports a network, automatic re-login on 401, fatal stop for wrong password / untrusted certificate. Capture and network use separate executors; the camera and recording keep running while the network is down.
- Bounded recording: 5-minute segments (configurable), per-file size cap, total-size and age retention, pause when free space is low and resume, delete invalid tiny files, give up after 5 consecutive errors. Recordings screen with play (external player via FileProvider), share/export, delete, and an "incomplete" marker found with `MediaMetadataRetriever`.
- Partial wake lock (10-minute timeout, refreshed every 30 s), thermal handling (slows to 1 frame per 5 s and pauses recording at SEVERE, stops at EMERGENCY).
- Password passes activity to service through an in-process hand-off, never an Intent extra, disk or log; it is not saved.
- User-installed certificate authorities are trusted (`network_security_config.xml`) so a private CA works; certificate and host-name checks stay on.

### Specific risks to check first on hardware
1. Does capture continue with the screen locked and the app in the background for 30 minutes on the S10, Z Flip6 and S23 Ultra? (`FOREGROUND_SERVICE_TYPE_CAMERA` started from a visible activity should keep camera access; unproven. Samsung battery optimisation may still kill it.)
2. `ImageProxy.toBitmap()` output: correct colours, no stripe at the edge, correct rotation in portrait and landscape. The video file's rotation is not set (defaults to natural orientation).
3. CameraX use-case combination (analysis + SD video) on each device.
4. Finalization after Stop, after a crash and after storage full; are segments playable; are gaps between segments acceptable.
5. The `AcceptsUserCertificates` trade-off: any user-installed CA is trusted by this app, not only yours.
6. On Android 10 (API 29) the service starts without a foreground-service type; not exercised.

### Not done (from the work order)
- Step 3 partly: `SETUP.md` explains certificates and startup for Linux/macOS/Windows, but the computer OS is still unknown and nothing was configured or tested end to end with a phone.
- Step 4: no real-phone tests, no Android versions recorded.
- Step 6: still one shared password for cameras and viewers; no per-device credentials, session cap or login-attempt limit; the server still only checks JPEG markers, not full decoding.
- Step 7 (H.264/WebRTC, motion, detection) and step 8 (APK/JADX inspection) not started.
- Viewer UX unchanged.
