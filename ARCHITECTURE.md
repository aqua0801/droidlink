# DroidLink 架構

DroidLink 是一個供開發者自用的手機↔電腦輔助工具，在使用者自己的裝置、使用者自己的授權下運作。
它有兩個彼此獨立的連線子系統，分別對應兩種使用情境。

## 基本模式：P2P 連線

手機 App 與電腦程式之間的直接連線。

- 傳輸：TCP，TLS 1.3 雙向驗證，憑證以指紋固定（配對時交換）。
- 探索：區網 mDNS，或手動輸入位址（含 Tailscale MagicDNS）。
- 程式位置：`shared/`（協定、加密、連線）、`androidApp/`（手機 UI 與前景服務）、
  `desktopApp/` 的 `link`/`net` 等套件（伺服器、配對、mDNS）。
- 功能：剪貼簿（電腦→手機自動；手機→電腦由使用者主動送出，因為 Android 10 起
  App 只有在前景時才能讀剪貼簿）、電量提示。

## 增強模式：ADB

在手機上以 shell 使用者身分執行一個小型輔助程式。這是 scrcpy 等開發工具常見的作法：
電腦透過使用者已授權的 ADB，用 `app_process` 啟動手機上的程式。shell 身分的權限比
一般 App 高，因此可以做到一般 App 做不到的事：

- 背景即時剪貼簿（不受前景限制）
- 靜默安裝 APK（`pm install`，與 `adb install` 相同機制）
- 讀取完整系統紀錄（`logcat`）

### 運作方式

1. 電腦把 `agent` 模組編譯成的 DEX（`droidlink-agent.jar`）推送到
   `/data/local/tmp/`。
2. 電腦用 `adb shell ... app_process` 啟動它。輔助程式在手機的 loopback 綁一個 TCP 埠，
   把埠號印到 stdout。
3. 電腦讀到埠號後，用 `adb forward` 建立通道並連上。
4. 之後走一個簡單的長度前綴框架協定（`AgentFrame`）。電腦中斷連線時輔助程式即結束，
   不會常駐。

### 與基本模式的關係

兩者**完全獨立**：不同傳輸（ADB vs. TLS over TCP）、不同協定、不共用執行期程式碼。
`agent/` 模組不依賴其他模組，也不含加密相關程式，所以產生的 DEX 很小、能獨立運作。
電腦端的增強模式程式集中在 `desktopApp` 的 `adb` 套件，與 P2P 的 `link` 套件互不相干。
刻意這樣切開，是為了讓任一邊日後單獨修改時不會牽動另一邊。

## 模組一覽

| 模組 | 內容 |
|---|---|
| `shared` | P2P 協定、TLS + 指紋固定、配對、連線（純 Kotlin/JVM） |
| `androidApp` | 手機 App：Compose UI、前景服務、剪貼簿、配對掃碼 |
| `desktopApp` | 電腦程式：Compose Desktop UI、伺服器、mDNS、系統匣、`adb` 增強模式驅動 |
| `agent` | 手機端輔助程式，編譯為 DEX，透過 ADB 的 `app_process` 執行 |
