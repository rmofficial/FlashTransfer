# Flash Transfer

HTTP file-share server for Android. Start the server, scan the QR from another device, browse and download big files over Wi-Fi / hotspot at LAN speed.

Developer: Rahul Mandal · github.com/rmofficial

## Features
- Start/Stop server, default port 8181
- QR code + link (copy / share / open)
- Own NIO server (zero-copy sendfile, 1 MB socket buffers, keep-alive), Range/resume, multi-connection friendly
- Optional password (Basic auth, user `admin`)
- Foreground service + wake/Wi-Fi lock: keeps running with screen off, minimized, during calls
- Live speed, total sent, active downloads
- Detects Hotspot / Wi-Fi / USB-tethering IPs automatically

## Build
Needs JDK 17, Android SDK (platform 36) and Gradle 8.13+.

    gradle assembleDebug
    gradle assembleRelease

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
Release APK: `app/build/outputs/apk/release/app-release.apk`

Release signing uses `keystore.properties` (kept out of git). Create your own keystore and copy the keys from `keystore.properties.example`.

Easiest: push this folder to GitHub — `.github/workflows/build.yml` builds the APK automatically (Actions tab -> artifact `FlashTransfer-APK`).

## Install (sideload)
The APK is not distributed through Google Play, so Play Protect may show a generic
"unknown app / may be harmful" prompt. It is a trust prompt for the new signing key, not a
permission problem. Tap **More details -> Install anyway** (or temporarily turn off
"Scan apps with Play Protect" in the Play Store settings, install, then turn it back on).

Release signing certificate SHA-256:
`58:55:08:FD:26:87:E6:1A:CF:93:94:92:9D:54:8B:B6:D5:2B:68:DA:9F:3C:C3:F7:0A:D4:86:3D:0B:E5:49:3A`

## Notes
- Android does not allow apps to switch the hotspot on/off. The app opens the Hotspot settings screen instead.
- Folder access uses the system folder picker (Storage Access Framework), so the app needs no "All files access" permission. Pick the folder to share once; the permission is remembered.
