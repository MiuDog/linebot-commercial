# Assets Manager LINE Bot

`@assets-manager-linebot@0.1.0`

把 LINE 群組當成圖片資產的收件與取件窗口：群組上傳圖片後，引用圖片並輸入合法資料夾代碼即可直接歸檔；SQLite 保存圖片組與正式檔案索引。

---

## 它做什麼

| 你在群組做的事 | 系統的反應 |
|---|---|
| 傳一張或一組圖 | 下載至待處理區，等待資料夾代碼 |
| 引用圖片，輸入 `ZD12345` 等合法代碼 | 將完整圖片組直接歸檔並分配流水號 |
| 再次引用已歸檔圖片並輸入代碼 | 允許重複存入，建立新的流水號 |
| 輸入合法代碼但未引用圖片 | 回覆操作錯誤，不會無回應 |
| 輸入 `#查 ZD12345` | 把該代碼的圖片貼回群組 |
| 輸入 `#標籤` | 列出所有編號與各自張數 |
| 引用規格圖，輸入 `#報價` | AI 讀出規格欄位（公式與 PDF 模板待補） |
| 輸入 `#說明` | 顯示用法 |

多張同時上傳的圖片使用 LINE `imageSet` 資訊分組；webhook 到達順序不影響圖片順序。單張圖片則獨立視為一組。

圖片資產固定使用專案根目錄下的 `圖片資產`：

```
圖片資產\
├─ assets.db
├─ .pending\
├─ 20260727\
│  ├─ 20260727-001.jpg
│  └─ 20260727-002.jpg
└─ 20260728\
   └─ 20260728-001.jpg
```

`.pending` 只保存尚未確認的圖片；正式日期資料夾只會在使用者輸入「確定」後建立。

---

## 快速開始

```bash
cp .env.example .env
```

填入 `LINE_BOT_CHANNEL_TOKEN`、`LINE_BOT_CHANNEL_SECRET`、`NGROK_AUTHTOKEN`、
`ASSETS_ROOT` 與 `ASSETS_SYNC_TOKEN`，然後：

```bash
docker compose --profile dev up --build -d
```

資料庫同步預設改由腳本執行。單次同步：

```powershell
.\scripts\sync-assets.ps1
```

在隱藏的背景程序中每 30 秒同步：

```powershell
.\scripts\sync-assets.ps1 -Background
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

33 個測試涵蓋收錄、中文歸檔、路徑穿越防護、AI 提取、報價資料結構與運行日誌，
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
| AI 規格資料提取 | ✅ 完成（需自行填入 `AI_API_URL`／`AI_API_KEY`／`AI_MODEL`） |
| 報價公式 | ⚠️ 佔位實作，公式尚未定義 |
| PDF 報價單產出 | ⚠️ 佔位實作，模板尚未提供 |

`#報價` 目前會把 AI 讀出的欄位回報給群組，並說明卡在哪一步。

**LINE 相簿拿不到**：Messaging API 完全不暴露群組相簿，照片放進相簿也不會產生 webhook 事件。本專案改用「引用回覆打編號」達成等效分類。
