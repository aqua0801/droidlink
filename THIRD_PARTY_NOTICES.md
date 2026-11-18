# Third-party notices

DroidLink is released under the [MIT License](LICENSE). The apps bundle or depend on the
components below, each under its own license. Release binaries (the Android APK and the
Windows installer) contain these components; this file is published with every release.

## Bundled binaries

### scrcpy-server v5.0.1 (desktop app)

- Project: https://github.com/Genymobile/scrcpy
- Copyright (C) 2018 Genymobile
- Copyright (C) 2018-2026 Romain Vimont
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

The unmodified `scrcpy-server-v5.0.1` release binary (SHA-256
`764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`) is shipped as
`desktopApp/src/main/resources/scrcpy/scrcpy-server.jar` and pushed to the phone in ADB
enhanced mode to capture the screen. Its license text is next to it in
`LICENSE-scrcpy.txt`, and is included inside the desktop app.

### FFmpeg via bytedeco presets (desktop app, Windows x86_64 natives)

- `org.bytedeco:ffmpeg:8.1.2-1.5.14` — FFmpeg, licensed under the GNU Lesser General Public
  License v2.1 or later. This is the default LGPL build, not the `-gpl` artifact; do not
  switch to the GPL build.
  - License text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
  - FFmpeg source code: https://ffmpeg.org/download.html (release 8.1.x)
  - Build scripts used for these binaries: https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
- `org.bytedeco:javacpp:1.5.14` — Apache License 2.0

FFmpeg is loaded as separate shared libraries from the `ffmpeg-*-windows-x86_64.jar` in the
installed app's `app` folder, and can be replaced with a compatible build.

### Java runtime (Windows installer)

The Windows installer contains a Java runtime created with `jlink` from Eclipse Temurin 21
(OpenJDK), licensed under the GNU General Public License v2 with the Classpath Exception.

- Source and license: https://adoptium.net/ and https://openjdk.org/legal/gplv2+ce.html

## Libraries

Unless noted, the license is the Apache License 2.0 —
https://www.apache.org/licenses/LICENSE-2.0

| Component | Used in | License |
|---|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains) | both apps | Apache 2.0 |
| AndroidX: Core, Activity, Lifecycle, Jetpack Compose (Google) | Android | Apache 2.0 |
| AndroidX Media3 ExoPlayer and UI (Google) | Android | Apache 2.0 |
| Compose Multiplatform for Desktop, Compose resources (JetBrains) | desktop | Apache 2.0 |
| Bouncy Castle `bcpkix-jdk18on` 1.81 | both apps | [Bouncy Castle License](https://www.bouncycastle.org/licence.html) (MIT-style) |
| ZXing core 3.5.3 | both apps | Apache 2.0 |
| ZXing Android Embedded 4.3.0 (JourneyApps) | Android | Apache 2.0 |
| Coil 3 (`coil-compose`, `coil-video`, `coil-gif`) | Android | Apache 2.0 |
| Telephoto (`zoomable`, `zoomable-image-coil3`) | Android | Apache 2.0 |
| JmDNS 3.5.12 | desktop | Apache 2.0 |
| JNA and JNA Platform 5.17.0 | desktop | Apache 2.0 (dual-licensed with LGPL 2.1; used under Apache 2.0) |

## Icons

UI icons are from [Material Symbols](https://fonts.google.com/icons) by Google, licensed under
the Apache License 2.0.
