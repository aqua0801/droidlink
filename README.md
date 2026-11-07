# DroidLink

手機 ↔ 電腦開發輔助工具。Kotlin 單一專案，兩端共用協定與加密。

```
shared/      純 Kotlin/JVM：訊息格式、分框、TLS 1.3 + 憑證固定、配對、LinkServer / LinkClient
androidApp/  Jetpack Compose（minSdk 29）：QR 配對、自動重連、NSD 探索、前景服務
desktopApp/  Compose for Desktop：伺服器、配對 QR、mDNS 廣播、系統匣通知
```

## 指令

```powershell
$env:JAVA_HOME = 'C:\@TOOLS\AndroidStudio\jbr'
.\gradlew :shared:test                 # 協定 / 加密測試
.\gradlew :desktopApp:run              # 啟動電腦端
.\gradlew :androidApp:installDebug     # 安裝到已連線的手機
```

## 連線與安全

- 電腦端監聽 TCP `47100`，同區網用 mDNS（`_droidlink._tcp`）廣播，TXT 帶憑證指紋。
- 每台裝置各有一把 EC P-256 金鑰和自簽憑證；TLS 1.3 雙向驗證。
- 配對：電腦 QR 碼含「主機清單 + 電腦憑證指紋 + 一次性 token（5 分鐘）」。手機只接受指紋相符的電腦，電腦用 token 授權手機後記住手機的指紋。
- 之後重連只靠指紋，不需要 token。Tailscale 只是傳輸路徑，加密驗證一樣走自己的 TLS。

## 資料位置

- 電腦：`%APPDATA%\DroidLink\`（`identity.json`、`trusted-devices.json`、`settings.json`）
- 手機：app 私有目錄 `files/identity.json`、SharedPreferences `paired_pcs`、`settings`

## 剪貼簿（基本模式）

- 電腦 → 手機：自動。Windows 用 `GetClipboardSequenceNumber` 偵測變更，只有變了才讀內容。
- 手機 → 電腦：Android 10 起 App 只有在自己的視窗取得焦點時才能讀剪貼簿，所以由使用者主動觸發：
  選取文字選單「傳到電腦」、分享選單、快速設定磚「傳送剪貼簿」，或打開 DroidLink 時自動送出。
- 兩端都會記住最後一次同步的內容，避免回傳造成迴圈。
- 背景雙向即時同步要等 ADB 增強模式（shell 身分可以在背景讀剪貼簿）。

## 多國語系

所有介面字串都在資源檔，目前預設語系是繁體中文：

- Android：`androidApp/src/main/res/values/strings.xml`
- 電腦：`desktopApp/src/main/composeResources/values/strings.xml`

新增語言時，在對應位置加 `values-en/strings.xml` 等資料夾即可。程式碼裡不寫死任何介面文字。

## 平台抽象

電腦端與 OS 相關的部分都放在介面後面（`TrayNotifier`、`SystemClipboard`；之後的 `ScreenCapturer` 也一樣），之後補 Linux 實作即可。
