<div align="center">

# Flash Transfer

**Turn your Android phone into a blazing-fast file server.**
Share gigabytes over Wi-Fi or hotspot — no cables, no cloud, no account, no size limits.

[![Release](https://img.shields.io/github/v/release/rmofficial/FlashTransfer?style=for-the-badge&logo=github&label=Release)](https://github.com/rmofficial/FlashTransfer/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/rmofficial/FlashTransfer/build.yml?style=for-the-badge&logo=githubactions&label=Build)](https://github.com/rmofficial/FlashTransfer/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/rmofficial/FlashTransfer?style=for-the-badge&label=License)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#tech-stack)

[Download](#download) · [Features](#features) · [How it works](#how-it-works) · [Build](#build-from-source) · [FAQ](#faq)

</div>

---

## Overview

**Flash Transfer** is a lightweight HTTP file-share server that runs entirely on your Android device. Pick a folder, start the server, and scan the QR code from any phone, tablet, laptop, or smart TV on the same network. Files stream directly from your storage to the browser — nothing is uploaded to a third-party server.

Built for one purpose: moving **large files** (videos, backups, APKs, RAW photos) at full LAN speed with a download manager that can pause, resume, and parallelise. The browser page also supports selecting multiple files and uploading them straight back into the shared folder.

> **Example:** a 4 GB movie over a 5 GHz Wi-Fi link, transferred at 40–80 MB/s, with resume support if the connection drops.

---

## Features

| | Capability |
|---|---|
| **One-tap server** | Start/stop instantly on port `8181` (configurable) |
| **QR + link sharing** | Auto-generated QR code, copy, share, or open the URL |
| **Zero-copy streaming** | NIO `SocketChannel` + `FileChannel.transferTo` (sendfile) for minimal CPU usage |
| **Resume & range** | HTTP `Range`, `206 Partial Content`, `HEAD`, and `Accept-Ranges` support |
| **Download-manager ready** | HTTP keep-alive and multi-connection friendly |
| **Optional password** | HTTP Basic auth (user `admin`), constant-time comparison |
| **Background reliability** | Foreground service + partial wake lock + Wi-Fi lock; keeps serving with the screen off |
| **Live dashboard** | Real-time speed, total bytes sent, and active downloads |
| **Smart network detection** | Detects Hotspot, Wi-Fi, USB tethering, and Ethernet addresses, hotspot first |
| **No storage permission** | Uses the Storage Access Framework folder picker; access is persisted |
| **Browser-first UI** | Clean, responsive directory listing with file-type icons and download links |
| **Multi-file upload** | Pick one or more files (or drag & drop), review the list, then upload with live progress |
| **Private session URL** | Every server start gets a random path token, so plain links are not guessable |
| **Tiny footprint** | ~3 MB APK, no analytics, no ads, no tracking |

---

## How it works

```
┌──────────────┐        HTTP/1.1         ┌───────────────────────────┐
│  Any device  │  GET /videos/movie.mp4  │        Android phone      │
│  (browser,   │ ──────────────────────► │                           │
│   download   │                         │  Foreground Service       │
│   manager)   │ ◄────────────────────── │   └─ NIO HTTP server      │
└──────────────┘   Range / 206 / sendfile│       └─ SAF → file stream │
                                         └───────────────────────────┘
```

1. The app resolves the best reachable IPv4 address (hotspot → Wi-Fi → USB → Ethernet).
2. A foreground service starts a small, dependency-free HTTP server on a `ServerSocketChannel`.
3. Requests are resolved against the SAF folder tree, then streamed with `transferTo()` in 1 MB chunks.
4. The dashboard polls shared counters to display live throughput and active connections.

---

## HTTP API

The server implements a focused subset of HTTP/1.1:

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Browse the shared folder (HTML listing) |
| `GET` | `/<path>/` | Browse a subfolder |
| `GET` | `/<path>/<file>` | Stream a file inline |
| `GET` | `/<path>/<file>?dl` | Force download (`Content-Disposition: attachment`) |
| `HEAD` | any path | Headers only, no body |
| `GET` | any file + `Range: bytes=…` | Partial content (`206`) or `416` when unsatisfiable |
| `POST` | `/<path>/` | Upload a file into that folder |

Uploads send the raw request body with `Content-Length`, the target filename in
`X-File-Name` (URL-encoded), and an optional `Content-Type`. Duplicate names are
saved as `file (1).ext`; failed transfers are cleaned up automatically.

Authentication (when a password is set):

```http
Authorization: Basic base64("admin:<password>")
```

Unsupported methods return `405`; unknown paths return `404`; unreadable files return `404`.

---

## Requirements

| Component | Version |
|---|---|
| Android | 8.0 Oreo (API 26) or newer |
| Compile / target SDK | 36 |
| JDK | 17 |
| Gradle | 8.13+ |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.2.20 |

---

## Download

Grab the signed APK from the latest release:

**[github.com/rmofficial/FlashTransfer/releases/latest](https://github.com/rmofficial/FlashTransfer/releases/latest)**

Then sideload it on your device. Because the app is distributed outside Google Play, Play Protect may show a generic *"unknown app / may be harmful"* prompt. This is a trust prompt for the signing key, not a permission issue.

- Tap **More details → Install anyway**, or
- Temporarily disable *Play Store → Settings → Scan apps with Play Protect*, install, then re-enable it.

**Release certificate SHA-256**

```
58:55:08:FD:26:87:E6:1A:CF:93:94:92:9D:54:8B:B6:D5:2B:68:DA:9F:3C:C3:F7:0A:D4:86:3D:0B:E5:49:3A
```

---

## Build from source

The project does not ship a Gradle wrapper; install Gradle 8.13+ (or use the CI workflow).

```bash
# Debug build
gradle assembleDebug

# Release build (requires keystore.properties)
gradle assembleRelease
```

Output locations:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### Release signing

`keystore.properties` is intentionally ignored by git. Create your own keystore and copy the template:

```bash
cp keystore.properties.example keystore.properties
# then fill in storeFile, storePassword, keyAlias, keyPassword
```

`keystore.properties` and `keystore/` are excluded via `.gitignore` — never commit them.

### Continuous integration

`.github/workflows/build.yml` builds the debug APK on every push to `main` and on manual dispatch. Download the artifact from the **Actions** tab (`FlashTransfer-APK`).

---

## Usage

1. Open **Flash Transfer** and tap **Choose folder** to pick what to share.
2. Optionally set a password.
3. Tap **Start server**.
4. Scan the QR code with another device, or open the displayed `http://<ip>:8181` link.
5. Browse, download, and resume as needed.
6. To receive files, use **Choose files → Upload** on the browser page. Multiple files can be queued at once.

> Android does not allow apps to toggle the hotspot programmatically. Use the in-app shortcut to open the Hotspot settings screen instead.

> For uninterrupted transfers, use the battery-optimisation shortcut in the app to exempt Flash Transfer from background restrictions.

---

## Project structure

```
FlashTransfer/
├── app/src/main/java/com/rahulmandal/flashtransfer/
│   ├── MainActivity.kt      # UI, QR, clipboard, network selection
│   ├── ServerService.kt     # Foreground service, wake/Wi-Fi locks, notification
│   ├── FileServer.kt        # NIO HTTP/1.1 server, range/resume, SAF streaming
│   ├── NetUtils.kt          # Interface discovery and ranking
│   ├── QrUtil.kt            # QR generation (ZXing)
│   ├── ServerState.kt       # Shared runtime state and counters
│   └── Fmt.kt               # Human-readable size/speed formatting
├── app/src/main/res/        # Layouts, drawables, theme, launcher icon
├── .github/workflows/       # CI build
├── keystore.properties.example
└── README.md
```

---

## Tech stack

- **Language:** Kotlin
- **UI:** Android Views + View Binding, Material theming
- **Networking:** `java.nio` channels, no third-party server library
- **QR:** ZXing core
- **Storage:** Storage Access Framework (`DocumentsContract`)
- **Concurrency:** Atomic counters, dedicated accept/worker threads

---

## Security notes

- The server is intended for **trusted local networks** (home Wi-Fi, personal hotspot).
- Enable the password option when sharing over a network you do not fully control.
- Each server session uses a random URL path token; do not share the link publicly.
- Uploads write into the selected folder, so re-select the folder if it was originally granted read-only access.
- Traffic is plain HTTP; do not expose the server directly to the public internet.
- Secrets (`keystore.properties`, keystores) are git-ignored.

---

## FAQ

<details>
<summary><b>Why does Android warn about the APK?</b></summary>

The app is signed with a self-managed release key and installed outside Google Play. Play Protect shows a generic warning for new signing keys. Verify the certificate fingerprint above, then choose *Install anyway*.

</details>

<details>
<summary><b>Can I turn on the hotspot from the app?</b></summary>

No. Android does not expose a public API for toggling the hotspot. The app opens the system Hotspot settings screen instead.

</details>

<details>
<summary><b>Does it work with the screen off?</b></summary>

Yes. A foreground service with a partial wake lock and a Wi-Fi lock keeps the server alive. Exempt the app from battery optimisation for the best results.

</details>

<details>
<summary><b>Can I use IDM, aria2, or a browser download manager?</b></summary>

Yes. Range requests, `HEAD`, `Accept-Ranges`, and keep-alive are supported, so multi-connection and resumable downloads work.

</details>

<details>
<summary><b>Why are no files visible?</b></summary>

Make sure a folder has been selected and that the persisted URI permission is still valid. Re-pick the folder if Android revoked access.

</details>

---

## Contributing

Issues and pull requests are welcome. Please keep changes focused, match the existing Kotlin style, and verify a debug build before opening a PR.

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Commit your changes
4. Open a pull request

---

## License

Released under the [MIT License](LICENSE) © 2026 Rahul Mandal.

---

<div align="center">

**Built by [Rahul Mandal](https://github.com/rmofficial)**

[GitHub](https://github.com/rmofficial) · [Email](mailto:rahulmandal.official@zohomail.in) · [Facebook](https://www.facebook.com/share/1FV6HZzVyd/)

If Flash Transfer saves you time, consider starring the repository.

</div>
