# Doc 4: LINE Bot 建置流程

適用版本：`@linebot-commercial@0.1.0`

從**完全沒有 LINE Bot** 到**群組可用**的完整流程。分成四段：

1. [在 LINE 平台建立 Bot](#一在-line-平台建立-bot)（一次性，約 15 分鐘）
2. [取得憑證](#二取得憑證)
3. [建置專案](#三建置專案)
4. [串接與上線](#四串接與上線)

> 已經有 Bot、只是要在新機器上架服務 → 直接看 [01-bot-deployment.md](01-bot-deployment.md)。

---

## 一、在 LINE 平台建立 Bot

### 1.1 建立 LINE 開發者帳號

前往 [LINE Developers Console](https://developers.line.biz/console/)，用 LINE 帳號登入。首次登入需填開發者名稱與電子郵件。

> 建議用**公司共用帳號**而非個人帳號。個人帳號的持有者離職時，Channel 的所有權轉移會很麻煩。

### 1.2 建立 Provider

Provider 是「發布 Bot 的組織」，一個 Provider 底下可以有多個 Channel。

**Console → Create a new provider →** 填入公司或團隊名稱。

> Provider 名稱**建立後無法更改**，也會顯示給加入 Bot 的使用者看，命名時想清楚。

### 1.3 建立 Messaging API Channel

在該 Provider 底下 **→ Create a new channel → Messaging API**。

| 欄位 | 說明 |
|---|---|
| Channel name | Bot 在群組裡顯示的名稱，例如「資產管理助手」 |
| Channel description | 用途說明 |
| Category / Subcategory | 依實際用途選，不影響功能 |
| Email address | 聯絡信箱 |

> **Channel name 建立後 7 天內不能改**，且會直接顯示在群組成員的聊天列表裡。

### 1.4 調整 Channel 設定

建立完成後，有四項設定**必須調整**，否則 Bot 在群組裡不會如預期運作。

**Messaging API 分頁：**

| 設定 | 應設為 | 不改的後果 |
|---|---|---|
| Use webhook | **Enabled** | 收不到任何訊息，Bot 完全沒反應 |
| Allow bot to join group chats | **Enabled** | Bot 無法被加進群組 |

**「LINE Official Account features」區塊**（點該區塊的 Edit 會跳轉到 LINE Official Account Manager）：

| 設定 | 應設為 | 不改的後果 |
|---|---|---|
| Auto-reply messages | **Disabled** | 官方罐頭訊息會蓋掉 Bot 的回覆，使用者只看得到「感謝您的訊息」 |
| Greeting messages | 視需求 | 只影響 Bot 剛加入群組時的問候語 |

> `Auto-reply messages` 沒關掉是最常見的「Bot 好像壞了」原因——程式正常運作，但回覆被官方訊息蓋掉了。

---

## 二、取得憑證

需要兩個值，都填進專案的 `.env`。

| 憑證 | 取得位置 | 對應環境變數 |
|---|---|---|
| Channel secret | **Basic settings** 分頁 | `LINE_BOT_CHANNEL_SECRET` |
| Channel access token | **Messaging API** 分頁 → Channel access token (long-lived) → **Issue** | `LINE_BOT_CHANNEL_TOKEN` |

**用途差異**：

- **Channel secret** 用來驗證 webhook 請求真的來自 LINE（HMAC 簽章）。填錯的症狀是記錄一直出現「簽章驗證失敗」。
- **Channel access token** 用來呼叫 LINE API（下載圖片、回覆訊息）。填錯的症狀是收得到訊息但 Bot 不回話，記錄出現 401。

> ⚠️ 兩者都是**機密**。不要貼進聊天室、不要寫進程式碼、不要 commit。專案的 `.gitignore` 已排除 `.env`。
>
> 若不慎外洩：Channel secret 可在 Basic settings 重新產生；access token 可在 Messaging API 分頁 Revoke 後重新 Issue。

---

## 三、建置專案

### 3.1 取得原始碼

```bash
git clone https://github.com/M4ng0D0g/linebot-commercial.git
```

```bash
cd linebot-commercial
```

正式部署時應該 checkout 特定版本標籤，而不是用 `main`：

```bash
git fetch --tags && git checkout "@linebot-commercial@0.1.0"
```

### 3.2 建置方式擇一

#### 方式 A：Docker（建議）

不需要在機器上裝 Java 或 Maven，映像會自己處理。

```bash
docker compose build
```

多階段建置的流程：

1. `maven:3.9.16-eclipse-temurin-25-alpine` 下載依賴並編譯，產出 `target/app.jar`
2. `eclipse-temurin:25-jre` 只複製那份 jar，得到精簡的執行映像

**執行階段刻意不用 Alpine**——musl 沒有 UTF-8 locale，JVM 的 `sun.jnu.encoding` 會退化成 ASCII，中文資料夾名會全部變成問號。這是硬性要求，不要為了縮小映像改回 Alpine。

#### 方式 B：本機 Maven

需要 **JDK 25**。專案附了 Maven Wrapper，不必另外裝 Maven。

```bash
./mvnw clean package
```

產出 `target/app.jar`，直接執行：

```bash
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar target/app.jar
```

> Windows 上直接執行時，`-Dsun.jnu.encoding=UTF-8` 同樣建議加上，否則中文路徑在某些系統語言設定下仍可能出問題。

### 3.3 驗證建置

```bash
./mvnw test
```

應顯示 `Tests run: 9, Failures: 0, Errors: 0`。這些測試**不需要 LINE 憑證或 AI 金鑰**，涵蓋收錄、中文歸檔、路徑穿越防護與 AI 回應解析。

若這一步就失敗，先解決再往下走——後面的問題會很難分辨是設定錯還是程式錯。

### 3.4 設定環境變數

```bash
cp .env.example .env
```

| 變數 | 必填 | 值 |
|---|---|---|
| `LINE_BOT_CHANNEL_TOKEN` | ✅ | 第二節取得的 access token |
| `LINE_BOT_CHANNEL_SECRET` | ✅ | 第二節取得的 channel secret |
| `PUBLIC_BASE_URL` | ✅ | 對外 HTTPS 網址，**結尾不帶斜線**。下一節取得後回填 |
| `ASSETS_ROOT` | 建議 | 資產庫根目錄，例如 `F:/資產庫` |
| `NGROK_AUTHTOKEN` | 測試階段 | [ngrok dashboard](https://dashboard.ngrok.com/get-started/your-authtoken) |
| `AI_API_URL` / `AI_API_KEY` / `AI_MODEL` | 用 `#報價` 才需要 | 三項缺一就不啟用 |

---

## 四、串接與上線

### 4.1 啟動服務

**測試階段**（含 ngrok 隧道）：

```bash
docker compose --profile dev up --build -d
```

**部署階段**（用自己的網域，不啟 ngrok）：

```bash
docker compose up --build -d
```

確認健康狀態，`STATUS` 應為 `Up (healthy)`：

```bash
docker compose ps
```

### 4.2 取得對外網址

| 階段 | 做法 |
|---|---|
| 測試 | 打開 http://localhost:4040 ，複製 `https://xxxx.ngrok-free.app` |
| 部署 | 用公司網域，前面掛 Nginx／Caddy 反向代理處理 TLS，轉發到容器的 8088 |

填進 `.env` 的 `PUBLIC_BASE_URL` 後重啟服務讓設定生效。

> LINE 要求 **公開的 HTTPS 且憑證受信任**，自簽憑證不接受。這也是為什麼測試階段需要 ngrok——LINE 的伺服器連不到你家的 localhost。

### 4.3 設定 Webhook URL

Console → **Messaging API** 分頁 → Webhook URL，填入：

```
{PUBLIC_BASE_URL}/callback
```

按 **Verify**。成功會顯示 `Success`。

| Verify 的結果 | 原因 |
|---|---|
| Success | 正常 |
| 404 | 網址少了 `/callback` |
| 連線失敗 | 容器沒起來、ngrok 掛了、或防火牆擋住 |
| 401 | 不會發生在 Verify（LINE 的驗證請求帶正確簽章）；若正式訊息出現 401，是 secret 填錯 |

### 4.4 把 Bot 加進群組

Console → **Messaging API** 分頁底部有 QR code，用 LINE 掃描加為好友，再從好友列表把它邀請進群組。

### 4.5 驗收

| # | 動作 | 預期 |
|---|---|---|
| 1 | 群組傳一張圖 | 不回話；圖片先進入 `{ASSETS_ROOT}/.pending/` |
| 2 | 引用該圖，輸入 `ZD12345` | 回報存入張數與流水號，建立 `ZD12345/yyyyMMdd/yyyyMMdd-01.jpg` |
| 3 | 輸入 `#查 ZD12345` | 回傳該張圖片 |
| 4 | 輸入 `#標籤` | 列出 `ZD12345　1 張` |
| 5 | 輸入 `#說明` | 顯示用法 |

看記錄排查：

```bash
docker compose logs -f linebot
```

---

## 五、常見卡關點

依「症狀 → 先查什麼」排列：

| 症狀 | 最可能的原因 |
|---|---|
| Bot 完全沒反應 | `Use webhook` 沒開；或 Webhook URL 沒設 |
| 只回「感謝您的訊息」之類的罐頭訊息 | `Auto-reply messages` 沒關 |
| 記錄一直出現「簽章驗證失敗」 | `LINE_BOT_CHANNEL_SECRET` 填錯 |
| 收得到訊息但 Bot 不回話，記錄有 401 | `LINE_BOT_CHANNEL_TOKEN` 填錯或已 revoke |
| 傳圖有存檔，但 `#查` 不回圖 | `PUBLIC_BASE_URL` 沒設、設錯、或 ngrok 換網址了 |
| 無法把 Bot 加進群組 | `Allow bot to join group chats` 沒開 |
| 中文資料夾變成問號 | 執行階段映像被改成 Alpine，或 `LANG` 沒設 |
| 啟動失敗 `path does not exist` | `ASSETS_ROOT` 磁碟未掛載或無寫入權限 |
| 圖片跑到專案目錄底下 | `.env` 的 `ASSETS_ROOT` 沒填，落到預設的 `./assets-store` |
| 找不到某個編號的資料夾 | 先確認是否已引用圖片並輸入合法大寫代碼；只有成功歸檔才建立部門資料夾 |

### ngrok 換網址的連鎖反應

ngrok 免費版**每次重啟都會換網址**，換了之後要**同時**更新兩個地方：

1. `.env` 的 `PUBLIC_BASE_URL`
2. Console 的 Webhook URL

只改 Console → 訊息收得到，但圖片貼不回去（`#查` 給出的網址指向舊隧道）。
只改 `.env` → Bot 完全沒反應。

兩種情況都不會有明顯錯誤訊息，很容易誤判成程式壞掉。正式環境用固定網域就沒有這個問題。

---

## 相關文件

- [部署與外部串接指南](01-bot-deployment.md)
- [LINE Bot 規則與各階段處理](02-linebot-rules.md)
- [版本、Release 與 Push SOP](03-versioning-release-sop.md)
- [類別索引](reference/index.md)
# ⚠️ 歷史文件

本文件的舊本機部署段落不再適用；現行建置與部署請使用 [根 README](../README.md) 與 [部署 Runbook](deployment-runbook.md)。
