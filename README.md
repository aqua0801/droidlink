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

- 電腦：`%APPDATA%\DroidLink\`（`identity.json`、`trusted-devices.json`）
- 手機：app 私有目錄 `files/identity.json`、SharedPreferences `paired_pcs`

## 平台抽象

電腦端與 OS 相關的部分都放在介面後面（目前有 `TrayNotifier`；之後的 `ClipboardMonitor`、`ScreenCapturer` 也一樣），之後補 Linux 實作即可。
