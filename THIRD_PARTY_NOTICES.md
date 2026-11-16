# Third-party notices

DroidLink is released under the MIT License. It bundles or depends on the components below,
each under its own license.

## scrcpy-server v5.0.1 (bundled with the desktop app)

- Project: https://github.com/Genymobile/scrcpy
- Copyright (C) 2018 Genymobile
- Copyright (C) 2018-2026 Romain Vimont
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

The unmodified `scrcpy-server-v5.0.1` release binary (SHA-256
`764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`) is shipped as
`desktopApp/src/main/resources/scrcpy/scrcpy-server.jar` and pushed to the phone in ADB
enhanced mode to capture the screen. Its license text is next to it in
`LICENSE-scrcpy.txt`.

## FFmpeg via bytedeco presets (desktop app, Windows x86_64 natives)

- `org.bytedeco:ffmpeg:8.1.2-1.5.14` — FFmpeg under LGPL 2.1+ (the default, non-`-gpl`
  artifact; do not switch to the GPL build)
- `org.bytedeco:javacpp:1.5.14` — Apache License 2.0
