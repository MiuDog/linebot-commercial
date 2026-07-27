# Assets Manager LINE Bot

`@assets-manager-linebot@0.1.0`

把 LINE 群組當成資產的收件與取件窗口：群組上傳的圖片自動落地到本機磁碟，SQLite 只保存**指向該檔案的路徑**與編號，需要時在群組下指令就能把圖片取回來。

---

## 它做什麼

| 你在群組做的事 | 系統的反應 |
|---|---|
| 傳一張圖 | 自動下載存到 `{ASSETS_ROOT}/20260727/20260727-224530123.jpg` |
| 引用該圖，輸入 `zd12345` | 把該圖登記到資產編號 `zd12345` 底下（檔案不會搬動） |
| 引用該圖，輸入 `zd12345 台北 機房` | 同上，多的字詞存成附加標籤 |
| 輸入 `#查 zd12345` | 把該編號的圖片貼回群組 |
| 輸入 `#標籤` | 列出所有編號與各自張數 |
| 引用規格圖，輸入 `#報價` | AI 讀出規格欄位（公式與 PDF 模板待補） |
| 輸入 `#說明` | 顯示用法 |

**核心設計是「指標法」**：磁碟只負責保存，資料庫負責組織。圖片依收錄日期落地後就**永遠不再搬動**，資產編號與標籤全部存在資料庫裡。

資產庫位置由 `.env` 的 `ASSETS_ROOT` 指定，可以是任意路徑：

```
F:\資產庫\
├─ assets.db
├─ 20260727\
│  ├─ 20260727-224530123.jpg
│  └─ 20260727-224612456.jpg
└─ 20260728\
   └─ 20260728-091502001.jpg
```

這樣安排的三個好處：改標籤時檔案路徑不變，備份與外部引用不會失效；同一張圖可以同時屬於多個資產編號，不必在磁碟上複製；整個資產庫連同 `assets.db` 可以整包搬到別台機器而不失效。

---

## 快速開始

```bash
cp .env.example .env
```

填入 `LINE_BOT_CHANNEL_TOKEN`、`LINE_BOT_CHANNEL_SECRET`、`NGROK_AUTHTOKEN`、`ASSETS_ROOT`，然後：

```bash
docker compose --profile dev up --build -d
```

打開 http://localhost:4040 取得 ngrok 網址，填回 `.env` 的 `PUBLIC_BASE_URL` 並重啟，最後到 LINE Developers Console 把 Webhook URL 設成 `https://xxxx.ngrok-free.app/callback`。

完整步驟見 [docs/01-bot-deployment.md](docs/01-bot-deployment.md)。

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

9 個測試涵蓋收錄、中文歸檔、路徑穿越防護、AI 提取與錯誤處理，**不需要真實 LINE 憑證或 AI 金鑰**。

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
