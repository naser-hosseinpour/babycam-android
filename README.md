# BabyCam — Turn an old Android phone into a Wi-Fi baby monitor

**English** | [فارسی](README.fa.md)

BabyCam turns a spare Android phone into a network camera that you can watch and control from the browser of any device on the same Wi-Fi — **no app on the viewer, no cloud server, no account, nothing stored**. It uses only the Android framework (no third-party libraries).

Main use: put the phone above a baby's cot. As soon as the baby moves, the web page on the parent's phone shakes and sounds an alarm, so you notice before the baby falls or as soon as they wake up.

## Features

- Live MJPEG video in any browser (also works in VLC)
- **Motion detection** with a red box around the moving area
- **Automatic alarm** on motion, generated with the Web Audio API (works offline, no sound file), plus a manual **Buzz** button
- Draw a **motion zone** with your finger; adjustable **sensitivity** and shake strength
- Night mode (brightness boost), zoom, near/far focus, rotation, front/back camera, camera on/off
- Reachable by name at `http://ipcam.local:8080` via a built-in mDNS responder
- Keeps running with the screen off (foreground service + wake lock)
- Optional HTTP Basic password

## How it works

```
Camera2 (YUV frames)
   └─ CameraService ── motion detection (frame diff vs. sensitivity threshold)
         │            ├─ night-mode brightness, rotation
         │            └─ JPEG compression
         └─ StreamHub  (thread-safe shared state: latest frame, motion, settings)
               └─ MjpegServer (hand-written HTTP server, port 8080)
                     /        control page (HTML/JS embedded in the server)
                     /video   live MJPEG stream
                     /jpg     single snapshot
                     /status  JSON status (polled ~every 0.6 s by the page)
                     /control commands (zoom, focus, zone, sensitivity, …)
MdnsResponder ── announces ipcam.local on the network
```

| File | Role |
|---|---|
| `CameraService.kt` | Camera2 capture, frame processing, motion detection |
| `StreamHub.kt` | Shared state between camera and web server |
| `MjpegServer.kt` | Web server, control page and alarm sound |
| `MdnsResponder.kt` | Announces `ipcam.local` |
| `MainActivity.kt` | Permissions and service start |

## Settings (top of `CameraService.kt`)

`PORT` (8080) · `JPEG_QUALITY` (60) · `TARGET_WIDTH` × `TARGET_HEIGHT` (1280×720) · `PIXEL_DELTA` (20, motion threshold per pixel) · `USE_FRONT_CAMERA` · `AUTH_USER` / `AUTH_PASSWORD` (set both to require a login) · `MDNS_HOST` ("ipcam")

## Build and install

Requires Android Studio or Gradle 9.3, Android SDK 36, JDK 17. Min Android version: 7.0 (API 24).

1. Open the folder in Android Studio and let Gradle sync, then **Run** — or from the command line run `gradle wrapper` once, then:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Open the app → **Start** → allow Camera and notifications.
3. On another device on the same Wi-Fi, open `http://ipcam.local:8080` or `http://<phone-IP>:8080`.

## How to use (step by step)

**1. Set up the camera phone**
- Install `BabyCam-v1.0.apk` from the [Releases](../../releases) page (allow installing from unknown sources).
- Open the app, press **Start** and allow Camera and notifications. The app then shows two addresses: `http://ipcam.local:8080/` and `http://<phone-IP>:8080/`.
- Keep the phone on its charger, and turn off battery optimization for BabyCam in Android settings so the system does not stop it at night.

**2. Place the phone**
- Mount it above or beside the cot so the whole cot is in view.
- Use **Rotate 90°** on the control page if the picture is sideways.

**3. Open it on the parent's phone**
- Connect to the **same Wi-Fi** and open one of the two addresses in any browser.
- If `ipcam.local` does not open (some Android phones do not support `.local` names), use the number address shown in the app.

**4. Turn on the alarm**
- The motion alarm is **OFF by default**. Tap **Motion alarm** to turn it on — browsers only allow sound after you tap the page.
- Tap **Buzz** to test that you can hear it.

**5. Set the motion zone and sensitivity**
- **Drag on the image** to draw a box around the cot, so movement elsewhere (curtains, a pet, people walking by) does not trigger the alarm. **Clear zone** removes it.
- Adjust **Sensitivity** until normal breathing does not trigger it but turning over does.

**6. At night**
- Raise **Brightness** and keep a small night light on: a phone camera cannot see in complete darkness.

**Important**
- Keep the page open and the parent's phone screen on; the alarm plays from that page.
- This is a helper, not a medical device, and it does not replace watching your child.

## Notes

- Android always shows a notification while the camera is in use; this is required by the system.
- The stream is plain HTTP on the local network. Set a password if others share the network, and never expose the port to the internet.
- Only record spaces and people you have the right to record.

---
Author: **Naser Hosseinpour**
