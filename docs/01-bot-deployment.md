# Doc 1: 部署與外部串接指南

適用版本：`@assets-manager-linebot@0.1.0`

本文件說明如何在一台全新機器上把 `assets-manager-linebot` 跑起來。

> 各階段的差異、驗收清單與安全注意事項在 [02-linebot-rules.md](02-linebot-rules.md)，本文只講「怎麼架起來」。

---

## 1. 架構

本服務是**自給自足**的單一容器，不依賴其他應用服務：

| 項目 | 位置 |
|---|---|
| 圖片本體 | `{ASSETS_ROOT}/{資產編號}/{yyyyMMdd}/` |
| 資產索引 | `{ASSETS_ROOT}/assets.db`（SQLite，同一個目錄） |
| 對外取圖 | 容器內 `GET /media/{token}` |
| Webhook | 容器內 `POST /callback` |

**資產庫位置完全由你決定**：`.env` 的 `ASSETS_ROOT` 可以指向任意路徑，例如 `F:/資產庫`，不需要放在專案目錄內。用 docker compose 啟動時，該主機路徑會被掛到容器的 `/data/assets`。

> **歷史說明**：早期版本規劃把檔案串流轉發到獨立的 `cloudstorage-service`，並掛在共享網路 `my-shared-network` 上。**該設計已完全移除**——本服務不依賴任何外部服務，也不需要預先建立 Docker 網路。若你看到舊文件提到 `CLOUD_STORAGE_API_URL` 或 `docker network create`，那是過時的。

---

## 2. 前置需求

| 項目 | 說明 |
|---|---|
| Docker 與 Docker Compose | — |
| LINE Channel | Messaging API Channel，取得 token 與 secret |
| 對外 HTTPS 網址 | 測試用 ngrok；正式用固定網域 |
| 磁碟空間 | 圖片會持續累積，確認 `ASSETS_ROOT` 所在磁碟足夠 |

不需要預先建立 Docker 網路，compose 會自行處理。

---

## 3. 設定環境變數

```bash
cp .env.example .env
```

`.env.example` 內每一項都有說明。最少要填的是：

| 變數 | 必填 | 說明 |
|---|---|---|
| `LINE_BOT_CHANNEL_TOKEN` | ✅ | Console → Messaging API |
| `LINE_BOT_CHANNEL_SECRET` | ✅ | Console → Basic settings |
| `PUBLIC_BASE_URL` | ✅ | 對外 HTTPS 網址，**結尾不帶斜線** |
| `ASSETS_ROOT` | 建議 | 資產庫根目錄，例如 `F:/資產庫`；留空則用 `./assets-store` |
| `NGROK_AUTHTOKEN` | 測試階段 | ngrok dashboard |
| `AI_API_URL` / `AI_API_KEY` / `AI_MODEL` | 用 `#報價` 才需要 | 三項缺一就不啟用 |

`.env` **絕不進版控**，已列在 `.gitignore`。

---

## 4. 啟動

**測試階段**（含 ngrok 隧道）：

```bash
docker compose --profile dev up --build -d
```

**部署階段**（不啟 ngrok）：

```bash
docker compose up --build -d
```

確認狀態：

```bash
docker compose ps
```

`STATUS` 應為 `Up (healthy)`。健康檢查打的是 `/actuator/health`，`start_period` 給了 40 秒讓 JVM 起來。

---

## 5. 設定 Webhook

到 LINE Developers Console → Messaging API → Webhook URL，填入：

```
{PUBLIC_BASE_URL}/callback
```

按 **Verify**，並確認 **Use webhook** 已啟用。

同時確認：`Auto-reply messages` 設為 **Disabled**（否則官方罐頭訊息會蓋掉 Bot 的回覆），`Allow bot to join group chats` 設為 **Enabled**。

---

## 6. Docker 映像說明

多階段建置：

| 階段 | 映像 | 用途 |
|---|---|---|
| build | `maven:3.9.16-eclipse-temurin-25-alpine` | 編譯打包，產出 `app.jar` |
| runtime | `eclipse-temurin:25-jre` | 執行 |

兩個關鍵設計，改 Dockerfile 時請保留：

1. **執行階段不用 Alpine。** musl 沒有 UTF-8 locale，JVM 的 `sun.jnu.encoding` 會退化成 ASCII，中文分類資料夾會全部變成問號。編譯階段不碰中文檔名，可以繼續用 Alpine。
2. **產出檔名固定為 `app.jar`**（由 `pom.xml` 的 `<finalName>` 指定），升版本號時不需要回頭改 Dockerfile。

依賴下載獨立成一層（`mvn dependency:go-offline`），只要 `pom.xml` 沒變，改 Java 原始碼時會直接命中快取。

---

## 7. 常用指令

看即時記錄：

```bash
docker compose logs -f linebot
```

重啟：

```bash
docker compose restart linebot
```

停止：

```bash
docker compose down
```

`docker compose down` **不會**刪除 `ASSETS_ROOT` 底下的資料，圖片與 `assets.db` 都安全。

---

## 8. 疑難排解

| 症狀 | 可能原因 |
|---|---|
| Webhook Verify 失敗 | `PUBLIC_BASE_URL` 錯、容器沒起來、網址少了 `/callback` |
| 記錄出現「簽章驗證失敗」 | `LINE_BOT_CHANNEL_SECRET` 填錯 |
| 傳圖沒反應 | 看記錄是否有 `[收錄]`；沒有的話是 webhook 沒進來 |
| 圖片存了但 `#查` 沒回圖 | `PUBLIC_BASE_URL` 沒設或 LINE 連不到（ngrok 換網址了？） |
| 中文資料夾變成問號 | 執行階段映像被改成 Alpine，或 `LANG` 沒設 |
| 啟動失敗 `path does not exist` | `ASSETS_ROOT` 權限不足或磁碟未掛載，目錄無法建立 |
| 圖片存到意料之外的位置 | `.env` 的 `ASSETS_ROOT` 沒填，落到預設的 `./assets-store` |

---

## 相關文件

- [LINE Bot 規則與各階段處理](02-linebot-rules.md)
- [版本、Release 與 Push SOP](03-versioning-release-sop.md)
- [類別索引](reference/index.md)
