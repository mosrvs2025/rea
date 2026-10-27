# Ultimate Watch — setup guide

Goal: phones send camera frames to **your own computer** over your private network, and you watch them in a browser. Nothing goes to a third party.

The computer's operating system was not known when this was written, so the steps below are given for Linux, macOS and Windows. Only the **install a certificate** and **run at startup** parts differ.

## 1. Run the server

Python 3.10+ and `aiohttp` are required.

```sh
python3 -m pip install aiohttp          # Windows: py -m pip install aiohttp
```

## 2. A certificate the phones will trust

Android apps and browsers need HTTPS with a certificate they trust. The simplest private route is to make your own certificate authority with [mkcert](https://github.com/FiloSottile/mkcert) (Linux, macOS and Windows builds are available), or with `openssl` if you prefer.

```sh
mkcert -install                                     # creates the CA on this computer
mkcert -cert-file watch.pem -key-file watch-key.pem  YOUR-HOSTNAME  192.168.1.50   # use your computer's name and LAN IP
```

Copy `rootCA.pem` (see `mkcert -CAROOT`) to each phone and install it: **Settings → Security (or Biometrics and security) → Other security settings → Install from device storage → CA certificate**. The camera app trusts certificate authorities you install this way; certificate and host-name checks stay on. The address you type in the app must be one the certificate covers (the host name or the IP you gave mkcert).

Keep `rootCA-key.pem`, `watch-key.pem` and the password private. Never commit them.

## 3. Start the server

```sh
export WATCH_PASSWORD='a-long-unique-password-of-16+-characters'     # Windows PowerShell: $env:WATCH_PASSWORD='...'
python3 server.py --cert watch.pem --key watch-key.pem --host 0.0.0.0 --port 8443
```

`--host 0.0.0.0` lets phones on your network connect. Allow port 8443 in the computer's firewall **for the private network only**, and never forward it from your router to the internet. For access away from home, use a VPN you control (for example WireGuard or Tailscale) rather than exposing the port.

Check from a phone's browser: `https://YOUR-HOSTNAME:8443` should show the unlock page without a certificate warning.

### Keep it running (optional)

- **Linux:** a systemd unit with `Environment=WATCH_PASSWORD=...` in a file only your user can read, `Restart=always`.
- **macOS:** a `launchd` agent in `~/Library/LaunchAgents`.
- **Windows:** Task Scheduler, "At startup", running `py server.py ...` with the variable set for that task.

If the server restarts, cameras log in again automatically within a few seconds.

## 4. Install and use the camera app

Build: JDK 17 and the Android SDK 35 are needed.

```sh
cd android
./gradlew :app:assembleDebug        # or: gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on the phone (allow installs from your file manager or `adb install`). Open **Ultimate Watch**, enter `https://YOUR-HOSTNAME:8443`, a camera name, the password, then **Start monitoring**. Grant camera (and notification) permission. The status line shows the service's real state: Starting, LIVE, Reconnecting, or why it stopped. The notification has a **Stop** button.

Open the server address in a browser on any device, unlock, and choose **View cameras**.

### Optional on-phone recording

Tick **Also record silent video**. Video is written in short files (default 5 minutes) to the app's private folder. Limits you can set: maximum storage (default 2 GB), days to keep (7), and the free space below which recording pauses (500 MB). Oldest files are deleted first; the file being written is never deleted. **Recordings on this phone** lists them with play, share/export and delete. A file marked "incomplete" was cut short, for example by a crash, and may not play.

## What to expect

- The stream is **one picture per second**, not smooth video.
- The camera keeps working through Wi-Fi drops; frames resume when the server is reachable again (retries back off to once a minute, and restart at once when Android reports a network).
- If the password is wrong, or the certificate is not trusted, monitoring stops and says so, instead of retrying forever.
- Swiping the app away from recent apps should not stop monitoring (use the notification Stop button or the in-app button). The phone does not restart monitoring after a reboot. These behaviours have not yet been checked on a phone.
- This is a prototype: one shared password for all cameras and viewers, no motion detection, no alerts.
