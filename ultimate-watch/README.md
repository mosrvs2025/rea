# Ultimate Watch — early prototype

An independently implemented experiment for turning spare phones into local cameras. There are two camera options: a native Android app in `android/` (foreground service, reconnection, optional bounded on-phone recording; see `SETUP.md`) and a browser page. In the browser option a phone's browser sends one JPEG per second to a private server; an authenticated dashboard shows recent frames from up to 16 named cameras. Frames are held in RAM only; there is no recording, audio, motion detection, AI, remote wake, or reliable background monitoring. Camera capture stops when the browser is closed and may pause when the screen locks. This is not yet a security system and has not been tested on a physical phone.

## Run

Requires Python 3.10+ and `aiohttp` (`python -m pip install aiohttp`). You need an HTTPS certificate and key for a hostname that both phones trust: Android browsers normally require a secure context to use a camera. A self-signed server certificate may not be trusted even after accepting a warning. A private network with a trusted certificate for its hostname is one option; configure this separately. Do not put the service directly on the public internet.

```sh
export WATCH_PASSWORD='replace-with-a-long-unique-password'
python server.py --cert /path/to/fullchain.pem --key /path/to/key.pem
```

By default the server listens only on `127.0.0.1`. To reach it from phones over your trusted private network, explicitly use `--host 0.0.0.0` and firewall the port to that network. Browse to `https://YOUR-TRUSTED-HOSTNAME:8443` on each device, unlock, select **Use as camera**, name it, then tap **Start monitoring**. Open the same site on the viewing device and select **View cameras**. Camera names must be unique. Anyone with the shared password can view and publish all cameras; use a unique strong password and only trusted devices. Restarting the server invalidates sessions and clears frames. Audio is never requested.

## APK investigation

The user-provided AlfredCamera_2026.21.0.apks is a ZIP with base, arm64 and xxhdpi APKs. Bundle SHA-256 `b9484ada735e33d271dc643422b49d5c2a7a1aa0e2b038d878f0f20888cea62d`; base SHA-256 `a0d2e290c99e416562b014f0c504268753c287fba684c83f1f41d61b75c416e8`.

The base APK contains multiple TensorFlow Lite files, including `assets/ODA20260415A.tflite`, whose adjacent labels file lists person, vehicle, pet and package, and `assets/sound/yamnet.tflite`. Resource names include live status, battery, playback and camera health. These observations suggest useful product ideas but do not prove how the packaged models or UI features run, or what network protocol is used. No original code, model weights, assets, account credentials or paid features are included in this prototype.

## Next steps

Screen-off, thermal and long-run testing of the native app on real phones (not yet done); then motion detection, separate camera/viewer credentials and secure remote access. Original plan, kept for reference: recordings with retention controls, motion detection, separate camera/viewer credentials and secure remote access. Preserve the plain Ultimate edition's no-network promise by shipping networking separately or in an explicitly network-enabled edition.
