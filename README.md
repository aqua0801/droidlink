# DroidLink

A phone-to-PC companion for Android developers and power users. DroidLink pairs an Android
phone with a desktop PC over an end-to-end encrypted connection on your own network, and adds
an optional ADB-powered mode for things a normal app isn't allowed to do.

Everything runs on your own devices: there is no server, no account and no cloud relay.

> **Status:** early and actively developed (`0.1.x`). The desktop app targets **Windows 10/11**;
> it may start on other platforms, but several features below are Windows-only for now.

## Features

**Over the encrypted P2P link** (phone app + desktop app, no cable needed)

- **Pairing by QR code**, then automatic reconnects via mDNS on the LAN, or a manual address
  (works with Tailscale MagicDNS names).
- **Clipboard sync** with per-direction modes (automatic / hotkey / manual / off) and global
  hotkeys on the PC to push or pull the clipboard.
- **File transfer** in both directions with SHA-256 verification: drag files onto the desktop
  window, or use the Android share sheet.
- **Screenshot forwarding**: new phone screenshots appear on the PC in a small preview popup,
  ready to copy or open.
- **Folder sync**: push a PC folder to the phone as a one-way mirror (optionally deleting files
  removed on the PC), with an in-app browser and viewers for images, video, audio, PDF and text.
- **Battery alerts** on the PC when the phone is low or fully charged.
- **Wireless screen mirroring** (view only), using the phone's own screen capture after a
  consent prompt on the phone.

**ADB enhanced mode** (phone connected via USB or wireless debugging)

- **Background clipboard sync** in both directions, without the Android 10+ foreground limit.
- **Screen mirroring with control**: mouse and keyboard input, navigation buttons, rotation,
  screenshots. Powered by the bundled, unmodified [scrcpy](https://github.com/Genymobile/scrcpy)
  server.
- **APK install** and a **logcat viewer** with filters.

**Both apps** ship in English and Traditional Chinese, with light, dark and system themes.

## How it works

```
 ┌──────────────┐   TLS 1.3, mutual auth, pinned certs   ┌──────────────┐
 │  androidApp  │ ◀────────────────────────────────────▶ │  desktopApp  │
 │  (Compose)   │      TCP 47100 · found via mDNS        │  (Compose    │
 └──────────────┘                                        │   Desktop)   │
        ▲                                                └──────┬───────┘
        │  app_process (shell user)                             │
 ┌──────┴───────┐               adb forward                     │
 │ agent / scrcpy│ ◀─────────────────────────────────────────────┘
 │    server     │          (enhanced mode only)
 └───────────────┘
```

The two connection paths are deliberately independent: different transports, protocols and code.

- **P2P link.** Each device has its own EC P-256 key and self-signed certificate. The desktop's
  pairing QR code carries its addresses, its certificate fingerprint and a one-time token valid
  for 5 minutes. The phone only accepts a PC whose fingerprint matches; the PC trusts the phone
  after checking the token and remembers its fingerprint. Later reconnects use the pinned
  fingerprints only. Tailscale or any other network is just a transport; authentication and
  encryption are always DroidLink's own TLS.
- **Enhanced mode.** The desktop pushes a small helper (`agent`, compiled to DEX) to the phone
  and starts it with `app_process`, the same technique scrcpy uses. Running as the shell user
  gives it the permissions of `adb shell`. It listens only on the phone's loopback interface,
  is reached through `adb forward`, and exits when the desktop disconnects.

See [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/screen-mirror.md](docs/screen-mirror.md) for
details (currently in Traditional Chinese).

## Project layout

| Module | Contents |
|---|---|
| `shared` | Pure Kotlin/JVM: wire protocol, framing, TLS and certificate pinning, pairing, file transfer and sync logic |
| `androidApp` | Android app (Jetpack Compose, min SDK 29 / Android 10) |
| `desktopApp` | Desktop app (Compose for Desktop): server, mDNS, tray, hotkeys, enhanced mode, mirroring |
| `agent` | Phone-side helper for enhanced mode, dexed with `d8` and bundled into the desktop app |

## Building

Requirements:

- JDK 17 or newer (Android Studio's bundled JBR works for building and running).
- Android SDK with build-tools and a platform installed. Set `sdk.dir` in `local.properties` or
  `ANDROID_HOME`; the `agent` module needs it too, to find `d8` and `android.jar`.
- `adb` on your `PATH` for enhanced mode.

```shell
./gradlew :desktopApp:run              # run the desktop app
./gradlew :androidApp:installDebug     # install the phone app on a connected device
./gradlew test                         # unit tests
```

On Windows, use `gradlew` instead of `./gradlew`.

### Packaging the desktop app

```shell
./gradlew :desktopApp:packageExe       # or :desktopApp:packageMsi
```

Installers bundle their own Java runtime, so users don't need Java installed. Packaging needs a
full JDK with `jpackage`; Gradle downloads Temurin 21 for this automatically.

### Tests against real devices

Some tests are opt-in because they need hardware or affect your desktop session:

```shell
./gradlew :desktopApp:test -PrealMirror=true --tests "*MirrorRealTest*"    # needs a phone on adb
./gradlew :desktopApp:test -PrealHotkeys=true --tests "*GlobalHotkeys*"    # sends real keystrokes
```

## Where data is stored

| | Location |
|---|---|
| Desktop settings and keys | `%APPDATA%\DroidLink\` on Windows, `~/.config/droidlink/` elsewhere |
| Files received on the PC | `~/Downloads/DroidLink/` (screenshots in `Screenshots/`) |
| Default PC sync folder | `~/DroidLink Sync/` (configurable) |
| Files received on the phone | `Download/DroidLink` via MediaStore |
| Phone sync folder | a folder you pick when setting up sync |

Your device's private key never leaves the device it was created on.

## Known limitations

- The desktop app is developed and tested on Windows. Global hotkeys, the clipboard watcher,
  the dark title bar and the bundled FFmpeg decoder are Windows-only for now.
- Without enhanced mode, phone → PC clipboard sync is manual (selection menu, share sheet or
  quick settings tile), because Android 10+ only lets the foreground app read the clipboard.
- Wireless mirroring is view only. On Android 15+, the system hides sensitive content such as
  one-time codes during screen capture.
- IME composition (e.g. Zhuyin) isn't forwarded when controlling the phone; paste with Ctrl+V.

## License

DroidLink is released under the [MIT License](LICENSE).

It bundles the unmodified scrcpy server (Apache License 2.0) and uses FFmpeg through the
LGPL build of bytedeco's presets, among other libraries. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
