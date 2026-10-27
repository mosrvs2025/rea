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
