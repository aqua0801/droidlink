# 手機畫面投影 — 規劃

狀態：第 1、2 階段完成待實測（畫面＋操控）。scrcpy-server 固定為 **v5.0.1**。

## 決策

- **路線 A（ADB 增強模式）優先**：沿用 scrcpy-server（Apache 2.0，不修改，附 NOTICE），電腦端自己寫解碼、視窗、輸入。
  原因：擷取畫面與注入輸入靠隱藏 API，各 Android 版本差異由 scrcpy 維護，我們不自己追。
- **路線 B（基本模式，P2P）之後做**：`MediaProjection` + MediaCodec，只能看；影像走**獨立的 TLS 連線**，避免和檔案傳輸互相卡住（TCP head-of-line blocking）。
- **解碼**：FFmpeg（JavaCV / bytedeco），只帶 windows-x86_64 natives，安裝檔約 +30–40 MB；使用 LGPL 版本。
- **授權**：專案 MIT；第三方見 `THIRD_PARTY_NOTICES.md`。

## 階段

1. 路線 A 只看畫面：push server → forward → 啟動 → 解析串流（`ScrcpyStream`，已完成並有測試）→ 解碼 → Compose 視窗顯示。
2. 路線 A 操控：control socket（點擊、拖曳、滾輪、文字、返回/主畫面/多工鍵）、與剪貼簿整合。
3. 路線 B 無線投影（只能看）。
4. 電腦投到手機（FFmpeg `ddagrab` + 硬體編碼 → 手機 MediaCodec）、錄影（H.264 直接封裝 mp4）、聲音。

## 第 1 階段待辦

- [x] 固定 scrcpy-server v5.0.1（SHA-256 已比對官方 SHA256SUMS），jar 與 LICENSE 放在 `desktopApp/src/main/resources/scrcpy/`。
- [x] 依 v5.0.1 `doc/develop.md` 更新 `ScrcpyStream`：codec id 之後不再帶寬高，改由每個 capture session 的 12-byte session packet 告知（旋轉會送新的）；media packet flag 改為 bit63=0、bit62 config、bit61 key frame、61-bit PTS。
- [x] `FfmpegVideoDecoder`（bytedeco ffmpeg 8.1.2-1.5.14，LGPL，只帶 windows-x86_64 natives），H.264 → BGRA。
- [x] `MirrorController`：push / forward / 啟動 / 連線重試 / 讀串流 / 解碼 / 清理。
- [x] 投影視窗（維持比例、旋轉時調整視窗形狀）；增強模式區塊加「投影畫面」按鈕。
- [x] 實機驗證：畫面正常（swscale 改用 `sws_scale_frame`，舊的 `sws_scale` 傳指標陣列會 EINVAL）。
- [x] 置頂開關、截圖按鈕（存到 下載\DroidLink\Screenshots 並複製到剪貼簿）。
- [ ] 視需要改成重用 Skia bitmap（目前每格新配置）。

## 第 2 階段（操控）

- [x] control socket：影像 socket 讀到 dummy byte 後再連 control socket，之後才讀 device meta（server 等所有 socket 都連上才送 meta）。
- [x] `ScrcpyControl` 依 v5.0.1 `ControlMessageReader` 編碼：觸控（finger pointer id -2）、滾輪（i16 定點 /16）、按鍵、文字、設定剪貼簿並貼上、旋轉。
- [x] 投影視窗：左鍵點擊/拖曳、右鍵返回、中鍵主畫面、滾輪、鍵盤（特殊鍵→keycode，可列印字元→文字，Ctrl+V 貼上電腦剪貼簿）、工具列（返回/主畫面/多工/音量/電源/旋轉/截圖/置頂）。
- [ ] 已知限制：輸入法組字（注音等）不會轉送，中文請用 Ctrl+V 貼上。

## 第 3 階段（無線投影，只看）

流程：
1. 電腦在既有 session 送 `MirrorRequest(requestId, token, maxSize)`。
2. 手機發高優先通知（Android 不允許背景直接開 Activity），點開 → `MirrorConsentActivity` → 系統的 MediaProjection 同意框。
3. 同意後啟動 `MirrorCaptureService`（foreground, type=mediaProjection），對同一個 port 47100 再開一條 TLS 連線，第一個訊息是 `MirrorStreamHello(token)`。
4. `LinkServer` 看到 `MirrorStreamHello` 就不走配對流程：只接受已信任的指紋，交給 `MirrorController.onStream` 比對 token 和指紋，回 `MirrorReady`，之後這條 socket 改成原始位元組串流。
5. 手機用 MediaCodec（H.264，8 Mbps，60 fps）照 scrcpy 的串流格式寫出（沒有 dummy byte），電腦沿用 `ScrcpyStream(forwardTunnel = false)` + FFmpeg 解碼。
6. 拒絕 → `MirrorDeclined`；任一邊停止 → `MirrorStop`；手機 90 秒沒回應 → 電腦顯示「沒有回應」。

- [x] 協定訊息、`LinkServer` 的 stream 分流、`LinkClient.openStream`、測試。
- [x] 手機：通知 → 同意 → 前景服務 → 編碼；旋轉時重設 VirtualDisplay 大小；通知上有「停止投影」。
- [x] 電腦：裝置列「無線投影」按鈕；投影視窗只留截圖和置頂。
- [ ] 實機驗證。

## 備忘

- server 的版本字串必須和 jar 完全一致，否則拒絕啟動。升級 scrcpy 時要重看 `doc/develop.md` 的 Protocol 一節（協定會隨版本變動）。
- v5.0.1 已沒有 `send_codec_meta`，改名為 `send_stream_meta`（預設開）；另設 `clipboard_autosync=false` 避免和 DroidLink 自己的剪貼簿同步衝突。
- `max_size` 預設 1600、60 fps、8 Mbps；之後放進設定。
- Android 15+ 用 MediaProjection 時，系統會遮蔽驗證碼等敏感內容（路線 B 的限制）。
