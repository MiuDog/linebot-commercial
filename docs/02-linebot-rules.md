# Doc 2: LINE Bot 開發必知規則與各階段處理方式

適用版本：`@assets-manager-linebot@0.1.0`

本文件分成三部分：**平台規則**（LINE 官方的硬性限制，違反就是壞掉）、**測試階段**、**部署階段**。

---

## 一、LINE 平台規則

這些不是本專案的設計選擇，是 LINE 官方的限制。動任何跟訊息收發有關的程式碼前請先看過。

### 1.1 Webhook 必須快、必須回 200

| 規則 | 後果 |
|---|---|
| 端點必須是**公開的 HTTPS**，且憑證需受信任 | 自簽憑證不接受 |
| 回應**不是 200 就會被重送整批事件** | 處理失敗 → 無限重送 → 資料重複 |
| 應盡快回應，耗時工作要非同步 | 逾時同樣視為失敗 |

本專案的做法：`LineWebhookController` 把單一事件的例外攔在迴圈內，一律回 200；`AssetService.ingest` 以 `messageId` 做冪等判斷，重送不會存成兩份。

> **改動 webhook 處理邏輯時，永遠不要讓例外逸出到 `handleWebhook` 之外。**

### 1.2 簽章驗證

每個請求都帶 `X-Line-Signature`，值是用 **Channel Secret** 對**原始請求本文**做 HMAC-SHA256 再 Base64。

兩個常見錯誤：

1. **用反序列化後再序列化的內容去算**——空白與欄位順序會變，簽章必定對不上。所以 controller 的參數型態是 `String` 而不是 DTO。
2. **用 `String.equals` 比對**——應該用 `MessageDigest.isEqual` 做常數時間比對。

### 1.3 replyToken 的限制

| 限制 | 說明 |
|---|---|
| 只能用**一次** | 用過即失效 |
| 有時效 | 過期只能改用 push |
| 單次最多 **5 則**訊息 | 超過**整個請求**被退回，不是丟掉多的 |

Reply **不計入訊息用量**，push **會計費**。本專案一律用 reply，所以 `#查` 的回傳張數上限設為 4（`app.query.max-results`），留一則給文字摘要。

### 1.4 訊息內容有保存期限

`GET /v2/bot/message/{messageId}/content` 只能在訊息發出後的一段時間內取得。

**收到 webhook 就要立刻下載**，不要排進佇列等有空再處理。`LineStorageService.downloadContent` 因此不做重試——失敗就請使用者重傳，比拿到 410 有意義。

### 1.5 發圖片訊息只吃公開 HTTPS 網址

`originalContentUrl` 與 `previewImageUrl` 必須是 **LINE 伺服器連得到的公開 HTTPS 位址**，不能是本機路徑，也不能塞 base64。

這就是本專案必須有 `MediaController` 與 `PUBLIC_BASE_URL` 的唯一原因。**沒設定 `PUBLIC_BASE_URL`，`#查` 一定失敗。**

其他限制：JPEG 或 PNG；原圖上限 10MB、預覽圖 1MB。

### 1.6 相簿（Album）拿不到

Messaging API **完全不暴露群組相簿**：

- 沒有任何「列出相簿／取得相簿內圖片」的端點
- 使用者把照片放進相簿時**不會產生聊天訊息**，webhook 收不到事件
- Bot 也不能建立或寫入相簿

本專案改用「**引用回覆打編號**」達成等效分類，見 1.7。

### 1.7 引用回覆（quotedMessageId）

使用者引用某則訊息時，webhook 的文字訊息物件會多一個 `quotedMessageId`，值等於被引用訊息的 `messageId`。

這是本專案歸檔機制的技術基礎：先傳圖（收錄，取得 `messageId`）→ 引用該圖輸入 `zd12345`（`quotedMessageId` 對回那張圖）。

> 圖片訊息本身**沒有** caption／text 欄位——LINE 傳圖不能附文字。任何「讀圖片訊息的文字」的想法都行不通。

### 1.8 群組相關設定

要讓 Bot 在群組運作，LINE Developers Console 需確認：

| 設定 | 值 |
|---|---|
| Allow bot to join group chats | **Enabled** |
| Auto-reply messages | **Disabled**（否則官方罐頭訊息會蓋掉你的回覆） |
| Greeting messages | 視需求 |
| Webhook | **Enabled** |

群組事件的 `source` 帶 `groupId`；多人聊天室帶 `roomId`；一對一只有 `userId`。本專案以 `resolveSourceId` 統一成單一 `sourceId`，優先序固定 **group → room → user**。

### 1.9 隱私

未經使用者個別授權，取得的 `userId` 只是該 Channel 內的識別碼，**無法**反查真實姓名或帳號。本專案只把它存進 `uploader_id` 供追溯，不做其他用途。

---

## 二、測試階段

目標：讓 LINE 伺服器打得到你本機跑的服務。

### 2.1 準備環境變數

```bash
cp .env.example .env
```

填入這幾項（其餘可留空）：

| 變數 | 來源 |
|---|---|
| `LINE_BOT_CHANNEL_TOKEN` | Console → Messaging API → Channel access token |
| `LINE_BOT_CHANNEL_SECRET` | Console → Basic settings → Channel secret |
| `NGROK_AUTHTOKEN` | https://dashboard.ngrok.com/get-started/your-authtoken |
| `PUBLIC_BASE_URL` | 先留空，下一步取得後回填 |
| `ASSETS_ROOT` | 資產庫根目錄，例如 `F:/資產庫`；留空則用 `./assets-store` |

### 2.2 啟動（含 ngrok 隧道）

```bash
docker compose --profile dev up --build -d
```

`--profile dev` 才會啟動 ngrok；沒有它只會起 Bot 本體。

### 2.3 取得公開網址並回填

打開 http://localhost:4040 ，複製 `https://xxxx.ngrok-free.app`，然後：

1. 填進 `.env` 的 `PUBLIC_BASE_URL`
2. 重啟讓設定生效：

```bash
docker compose --profile dev up -d
```

3. 到 LINE Developers Console 把 Webhook URL 設成 `https://xxxx.ngrok-free.app/callback`，按 **Verify**

> ⚠️ **ngrok 免費版每次重啟都會換網址。** 換了就要同時更新 `.env` 的 `PUBLIC_BASE_URL` **和** Console 的 Webhook URL——只改一邊的話，訊息收得到但圖片貼不回去，而且不會有明顯錯誤訊息。

### 2.4 驗收清單

把 Bot 加進測試群組，依序確認：

| # | 動作 | 預期 |
|---|---|---|
| 1 | 傳一張圖 | 不回話。`{ASSETS_ROOT}/未分類/20260727/20260727-224530123.jpg` 出現 |
| 2 | 引用該圖，輸入 `zd12345` | 回「已歸檔到資料夾「zd12345」」，檔案搬到 `{ASSETS_ROOT}/zd12345/20260727/` |
| 3 | 輸入 `#查 zd12345` | 回傳該張圖片 |
| 4 | 輸入 `#標籤` | 列出 `zd12345　1 張` |
| 5 | 輸入 `#說明` | 顯示用法 |

第 3 步失敗但 1、2 成功 → 幾乎必然是 `PUBLIC_BASE_URL` 沒設或設錯。

### 2.5 看記錄

```bash
docker compose logs -f linebot
```

| 記錄字樣 | 意義 |
|---|---|
| `[警告] 簽章驗證失敗` | Channel Secret 錯，或請求不是 LINE 發的 |
| `[收錄] 資產 #N 已落地` | 圖片存檔成功 |
| `[分類] 資產 #N 搬移至` | 歸檔成功 |
| `[LINE] 發送失敗，狀態碼=400` | 多半是圖片網址 LINE 連不到 |

### 2.6 不需要 LINE 也能測的部分

```bash
./mvnw test
```

9 個測試涵蓋收錄、中文歸檔、路徑穿越防護、AI 提取與錯誤處理，**完全不需要真實憑證或金鑰**。改動邏輯後請先跑過再進群組測。

---

## 三、部署階段（公司伺服器）

### 3.1 與測試階段的差異

| 項目 | 測試階段 | 部署階段 |
|---|---|---|
| 對外網址 | ngrok 臨時網址 | 固定網域 + 正式憑證 |
| ngrok 容器 | 需要（`--profile dev`） | **不啟動** |
| `PUBLIC_BASE_URL` | 每次重啟要換 | 設定一次 |
| 啟動指令 | `docker compose --profile dev up -d` | `docker compose up -d` |

### 3.2 前置作業

1. **網域與憑證**：準備 `https://assets.example.com` 之類的網域，前面掛 Nginx／Caddy 反向代理處理 TLS，轉發到容器的 8088。
2. **`.env`**：`PUBLIC_BASE_URL` 填正式網域（結尾不帶斜線），`NGROK_AUTHTOKEN` 可留空。
3. **`ASSETS_ROOT`**：填該伺服器上要存放資產的路徑，例如 `F:/資產庫` 或 `/data/assets`。確認該磁碟已掛載、空間足夠、且執行 Docker 的帳號有寫入權限。
4. **把 `ASSETS_ROOT` 納入既有備份機制。**

### 3.3 啟動

```bash
docker compose up --build -d
```

確認健康狀態：

```bash
docker compose ps
```

`STATUS` 應為 `Up (healthy)`。健康檢查打的是 `/actuator/health`。

### 3.4 更新 Webhook URL

Console → Messaging API → Webhook URL 改為 `https://assets.example.com/callback`，按 Verify。

### 3.5 安全注意事項

| 項目 | 說明 |
|---|---|
| `/media/{token}` **對公網開放** | 安全性完全建立在權杖不可預測（32 字元隨機值）。若公司政策更嚴，應在反向代理加 LINE IP 白名單 |
| `/actuator` | 只暴露 `health`，不要開放 `env`、`configprops` 等會洩漏金鑰的端點 |
| `.env` | 檔案權限設 `600`，且**絕不進版控**（已在 `.gitignore`） |
| 資料備份 | `ASSETS_ROOT` 含圖片與 `assets.db`，**整個目錄一起備份**才有意義——只備份 DB 會得到一堆指向不存在檔案的紀錄 |

### 3.6 資料搬遷

`assets.db` 存的是**相對路徑**，因此把整個 `ASSETS_ROOT` 目錄打包搬到新機器、再把新機器的 `ASSETS_ROOT` 指過去即可，資料庫內容一個字都不用改。從 `F:/資產庫` 搬到 `/data/assets` 也一樣。

搬遷後檢查：檔案數量是否與 `SELECT COUNT(*) FROM asset` 相符，中文資料夾名是否完整（若變成問號，代表新環境的 locale 沒設 UTF-8）。

### 3.7 中文檔名的必要設定

**執行階段的映像不可以用 Alpine。** musl 沒有 UTF-8 locale，JVM 的 `sun.jnu.encoding` 會退化成 ASCII，建立中文資料夾時全部變成問號。

Dockerfile 已設兩道保險，改動時請保留：

1. `ENV LANG=C.UTF-8 LC_ALL=C.UTF-8`（作業系統層）
2. `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`（JVM 層）

---

## 相關文件

- [部署與外部串接指南](01-bot-deployment.md)
- [版本、Release 與 Push SOP](03-versioning-release-sop.md)
- [類別索引](reference/index.md)
