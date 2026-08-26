# Linebot Commercial

`@linebot-commercial@0.3.0`

商用機的 LINE 自動報價系統：以私訊收集報價資料與工程圖片，經 AI／OCR 整理、人工確認後，在背景產生 Excel／PDF 並交付；圖片歸檔能力只作為報價流程的資產支援。

---

## 它做什麼

| 你在群組做的事 | 系統的反應 |
|---|---|
| 傳一張或一組圖 | 下載至待處理區，等待資料夾代碼 |
| 引用圖片，輸入 `ZD12345` 等合法代碼 | 將已抓取的圖片直接歸檔；缺圖時仍保存成功圖片並回報數量 |
| 再次引用已歸檔圖片並輸入代碼 | 允許重複存入，建立新的流水號 |
| 輸入合法代碼但未引用圖片 | 回覆操作錯誤，不會無回應 |
| 輸入 `#查 ZD12345` | 把該代碼的圖片貼回群組 |
| 輸入 `#標籤` | 列出所有編號與各自張數 |
| 一對一輸入 `#報價`，再依提示補資料 | AI／OCR 解析、完整預覽、確認後背景產生 Excel／PDF 並以 Flex 交付 |
| 輸入 `#說明` | 顯示用法 |
| 標記機器人並輸入 `ping` | 回覆 `pong` 與本次事件的延遲毫秒數 |

多張同時上傳的圖片使用 LINE `imageSet` 資訊分組；webhook 到達順序不影響圖片順序。單張圖片則獨立視為一組。

所有檔案共用 `SYSTEM_ROOT_PATH`；圖片位於其「圖片資產」子目錄，並依「部門代碼／台北日期」分層：

```
system-data\
├─ log\
├─ 報價單\
└─ 圖片資產\
   ├─ assets.db
   ├─ .pending\
   ├─ ZD12345\
   │  ├─ 20260727\
   │  │  ├─ 20260727-01.jpg
   │  │  └─ 20260727-02.jpg
   │  └─ 20260728\
   │     └─ 20260728-01.jpg
   └─ YJ123456\
      └─ 20260728\
         └─ 20260728-01.jpg
```

`.pending` 只保存尚未歸檔的圖片；輸入合法代碼後才建立正式資料夾。每個部門每天從 `01` 獨立計數，超過 `99` 自動擴充為三碼。

---

## Windows App（未簽章，個人使用）

Windows 桌面版會安裝為單一 App，內含 Java Runtime，不需要使用者另裝 JDK／JRE。第一次開啟會顯示繁體中文設定精靈；Spring、Tunnel 與 LINE Bot 由目前使用者的獨立背景 service 執行，Windows 登入時自動啟動。再次開啟 App 只載入控制視窗，可查看狀態與即時 Log，不會建立第二份報價機器人程序。

主視窗的「測試連線」可直接輸入公開或內網網域，分階段檢查本機服務、DNS、TCP、TLS、HTTP、LINE Bot API 與 AI API。AI API 僅匿名測試 endpoint，不傳送 API Key，也不會進行推論或消耗 Token。

**目前版本導向為未簽章的個人使用版。** 功能完整，唯一差別是沒有 Authenticode 簽章，首次執行會出現 Windows SmartScreen 警告（處理方式見下方）。Release 建立在 private repo，只有具備存取權的人看得到，並一律標記為 pre-release。

### 自動發佈到 GitHub Release

推送符合 `v<主版號>.<次版號>.<修訂號>` 的 tag 即自動建置、驗證並建立 GitHub Release，資產只有一份 Setup.exe：

```powershell
powershell.exe -NoProfile -File scripts\release.ps1 -Version 0.3.0
```

腳本會依序完成前置檢查、改寫 `pom.xml` 與 README 版本、提交、本機完整驗證、推分支再推 tag。任何一項前置檢查不過就在改動版本庫之前中止：

| 參數 | 用途 |
| --- | --- |
| `-DryRun` | 只印出將執行的指令，不改任何東西 |
| `-SkipVerify` | 略過本機 `mvnw clean verify`（交給 CI 驗） |
| `-Force` | 允許重新指向遠端已存在的 tag |
| `-Branch` | 發版分支，預設 `main` |

Release 內容取決於是否設定簽章憑證，workflow 會自動判斷：

| 狀態 | 行為 |
| --- | --- |
| 未設定簽章憑證（目前） | 略過商用欄位與 Authenticode 閘門，Release 標記為 **pre-release**，Notes 附 SmartScreen 說明與 SHA-256 |
| 已設定簽章憑證 | 套用商用欄位閘門、簽章並驗證 Authenticode，全部通過才建立正式 Release |

版本號取自 tag，且必須與 `pom.xml` 一致，否則 `build-windows-installer.ps1` 會以「Maven 版本與 Setup 版本不一致」中止。`release.ps1` 會自動保持兩者同步，因此不需要手動改版本再打 tag。

本 repo 目前是 private，因此 Release 只有具備存取權的人看得到。推 tag 時 `dry-run` job 會顯示 skipped，這是正常的：它只在手動 `workflow_dispatch` 時執行。

建立個人使用的 Setup：

```powershell
powershell.exe -NoProfile -File scripts\build-windows-installer.ps1 -Version 0.3.0
```

完整安裝、修復、預設保留資料與明確清除驗收：

```powershell
powershell.exe -NoProfile -File scripts\test-windows-installer.ps1 `
	-InstallerPath dist\LinebotCommercial-Setup-0.3.0.exe `
	-ExecuteLifecycle `
	-TestPurge
```

### 安裝未簽章版本

因為安裝檔沒有程式碼簽章，Windows 會在第一次執行時顯示藍色的「Windows 已保護您的電腦」畫面。這是預期行為，不代表檔案有問題：

1. 執行 `LinebotCommercial-Setup-<版本>.exe`。
2. 出現 SmartScreen 藍色警告時，點左下角的**「其他資訊」**。
3. 展開後點**「仍要執行」**。
4. 依安裝精靈完成安裝；不需要系統管理員權限，只安裝給目前的 Windows 使用者。
5. 安裝完成後會自動開啟設定精靈，填入 LINE Channel Token／Secret 與資料存放位置即可。

同一份 Setup 之後再執行，會顯示**編輯設定**、**修復／升級**、**移除**三個選項。預設的移除會保留你的設定與資料；只有明確勾選完整清除才會刪除。

若瀏覽器在下載時就攔截，選擇「保留」即可。這個警告會在同一台電腦上出現一次，之後執行不再提示。

> 警告的成因是缺少憑證，不是防毒軟體判定為惡意程式。若要消除警告就必須購買 Windows 程式碼簽章憑證；相關取得方式與對 CI 的影響見 [Windows 發佈 Runbook](docs/release-runbook.md)。

### 日後若要轉為商用發佈

需替換核准 EULA、Publisher 與支援網址，並在 GitHub `commercial-release` Environment 配置簽章憑證。注意自 2023 年 6 月起，公開 CA 簽發的 OV 憑證私鑰必須存放於硬體或雲端金鑰服務，現行 workflow 匯入 PFX 的步驟屆時需改為雲端簽章 API。詳細操作與回復方式見 [Windows 發佈 Runbook](docs/release-runbook.md)。

---

## 快速開始

```bash
cp .env.example .env
```

填入 `LINE_BOT_CHANNEL_TOKEN`、`LINE_BOT_CHANNEL_SECRET`、`NGROK_AUTHTOKEN`、
`SYSTEM_ROOT_PATH`、報價安全密鑰與 AI 設定，然後：

```bash
docker compose --profile dev up --build -d
```

打開 http://localhost:4040 取得 ngrok 網址，填回 `.env` 的 `PUBLIC_BASE_URL` 並重啟，最後到 LINE Developers Console 把 Webhook URL 設成 `https://xxxx.ngrok-free.app/callback`。

完整步驟見 [docs/01-bot-deployment.md](docs/01-bot-deployment.md)。

### 確認程式是否正常

先看容器狀態：

```powershell
docker compose ps
```

`linebot` 顯示 `running`、`healthy` 代表服務已就緒。也可以直接檢查健康端點：

```powershell
Invoke-RestMethod http://localhost:8088/actuator/health
```

正常時會看到 `status` 為 `UP`。持續查看應用程式日誌：

```powershell
docker compose logs -f --tail=100 linebot
```

只查看系統定義的關鍵事件：

```powershell
docker compose logs linebot | Select-String "event="
```

啟動完成會出現 `event=application_ready`。其中 `aiConfigured=false` 只代表 AI
設定尚未填妥，不代表主服務啟動失敗。LINE Webhook 與 AI 處理事件會帶有
`requestId`，可用同一個識別碼串起單次請求的日誌。

---

## 文件

| 文件 | 什麼時候看 |
|---|---|
| [文件樹入口](docs/README.md) | 不確定該看哪份 |
| [04 LINE Bot 建置流程](docs/04-linebot-build-guide.md) | 第一次從零建立 LINE Bot |
| [01 部署與外部串接](docs/01-bot-deployment.md) | 要在新機器上架起來 |
| [02 LINE Bot 規則與各階段處理](docs/02-linebot-rules.md) | 動訊息收發的程式碼前；測試或部署卡住 |
| [03 版本、Release 與 Push SOP](docs/03-versioning-release-sop.md) | 要 commit、發版本、部署或回滾 |
| [類別索引](docs/reference/index.md) | 要改程式碼，想知道該動哪個檔案 |

---

## 技術組成

| 項目 | 選擇 |
|---|---|
| Java | 25 |
| 框架 | Spring Boot 4.1 |
| 資料庫 | SQLite（單一檔案，與圖片放在一起） |
| 資料存取 | `JdbcClient`（不用 JPA） |
| LINE 整合 | 直接呼叫 Messaging API，未使用官方 SDK |
| 容器 | 多階段建置，執行階段 `eclipse-temurin:25-jre` |

**執行階段刻意不用 Alpine**：musl 沒有 UTF-8 locale，JVM 的 `sun.jnu.encoding` 會退化成 ASCII，中文分類資料夾會全部變成問號。

---

## 開發

```bash
./mvnw test
```

自動測試涵蓋收錄、圖片組歸檔、每日流水號、路徑穿越防護、AI 提取、報價資料結構與運行日誌，
**不需要真實 LINE 憑證或 AI 金鑰**。

```bash
./mvnw clean package
```

Push 前的檢查清單見 [SOP 2.2 節](docs/03-versioning-release-sop.md#22-push-前檢查清單)。

---

## 目前狀態與已知限制

| 功能 | 狀態 |
|---|---|
| 圖片收錄、`zd` 編號歸檔、查詢取用 | ✅ 完成 |
| 群組語音「小京」與 MCP 圖片取出 | ✅ 第一階段完成（需 OpenAI key、公開 HTTPS 網址及 MCP 權杖） |
| AI 規格資料提取 | ✅ 完成（報價與語音共用 `AI_API_URL`／`AI_API_KEY`／`AI_MODEL`） |
| 五種 Excel 範本提取與變數化 | ✅ 完成（五份單工作表範本，另保留合併版供維護比對） |
| 報價品項資料庫與本機管理頁 | ✅ 完成（開啟 `/admin/`，可匯出正式 XLSX 主檔或 UTF-8 CSV） |
| AI 指令解析、固定 JSON 驗證與主檔解析 | ✅ 完成（管理頁可貼文字試跑；固定欄位不採信 AI） |
| LINE 一對一報價草稿 | ✅ 缺漏補件、圖片詢問、完整預覽、簽章確認／取消與事件冪等已完成 |
| Excel／PDF／LINE 交付 | ✅ 五格式 Excel、圖片嵌入、分頁、SQLite 持久工作、背景 Excel COM 轉 PDF、HTTPS 下載與 Flex 交付已完成 |
| 報價圖片資產 | ✅ 草稿留在 `SYSTEM_ROOT_PATH/圖片資產/.pending`；確認後全部原圖移至「報價單」子目錄 |
| 報價公式 | ✅ 第一階段 DIRECT 複價、5% 稅額與總額完成；⚠️ 長寬高等第二階段數量推算仍待規則 |
| 本機報價管理 | ✅ 草稿／正式報價可搜尋及篩選；可查看完整快照、缺漏、程式計算金額、選圖與稽核，並下載、重試 XLSX／PDF／LINE、建立可複製的短效 HTTPS PDF 連結或撤銷連結；寫入操作具同源 CSRF 防護 |

`#報價` 只在一對一聊天室建立或修改草稿；群組指令只提示改用私訊，不會顯示客戶或價格資料。
正式確認會在同一 SQLite 交易中配置流水號、保存不可變快照及建立 generation job；工作者以租約、
退避重試與啟動恢復處理 Excel／PDF。LINE mutation 完成後的回覆先寫入 outbox，reply 失敗或事件重送時
可使用穩定 retry key 改走 push，不會再次執行同一 mutation。

本機管理頁除限制 loopback 直連外，所有會改變狀態的 `/api/admin/` 請求還必須帶
`X-Local-Admin-Request: 1`，並通過同源 `Origin`／`Sec-Fetch-Site` 驗證。

**LINE 相簿拿不到**：Messaging API 完全不暴露群組相簿，照片放進相簿也不會產生 webhook 事件。本專案改用「引用回覆打編號」達成等效分類。
