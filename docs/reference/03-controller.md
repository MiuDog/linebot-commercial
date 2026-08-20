# Controller

[← 回索引](index.md)

HTTP 端點，系統對外的入口。**Controller 不含業務判斷**，看懂請求之後就往下丟。

---

## `LineWebhookController`

`dev.miudog.linebotcommercial.controller.LineWebhookController`

**端點**：`POST /callback`

**職責**：LINE Webhook 的接收端點，負責驗簽、拆事件、分派。這裡是所有外部輸入的入口，因此擔任兩件防守工作——驗證 HMAC 簽章確認請求真的來自 LINE，以及把單一事件的例外隔離起來。

| 方法 | 可見性 | 說明 |
|---|---|---|
| `ResponseEntity<String> handleWebhook(String signature, String payload)` | public | Webhook 端點。驗簽失敗回 401，解析失敗回 500，其餘一律回 200。 |
| `void handleEvent(JsonNode event)` | private | 處理單一事件：辨識來源與訊息型態後分派。目前只收圖片與文字。 |
| `void handleImage(...)` | private | 下載圖片並收錄。成功時**刻意不回話**，避免群組連續上傳時被洗版。 |
| `String resolveSourceId(JsonNode source)` | private | 把 group／room／user 三種識別欄位統一成單一 `sourceId`。 |
| `String getSafeText(JsonNode parent, String field)` | private | 安全地取出字串欄位，欄位不存在或非字串時回 null。 |
| `boolean verifySignature(String payload, String headerSignature)` | private | HMAC-SHA256 驗簽，常數時間比對。 |

### 為什麼一律回 200

**只要回應不是 200，LINE 就會重送整批事件。** 一顆壞事件不該拖垮同批的其他事件，也不該造成無限重送，所以 `handleEvent` 的例外在迴圈內就被攔下記錄。

配合這點，`AssetService.ingest` 以 `messageId` 做冪等判斷，重送不會產生第二份檔案。

### 驗簽的兩個陷阱

1. **必須用未經解析的原始請求本文**計算 HMAC。先反序列化成物件再序列化回去，空白與欄位順序會變，簽章必定對不上——所以參數型態是 `String` 而不是 DTO。
2. 比對用 `MessageDigest.isEqual` 而非 `String.equals`，避免以回應時間差推敲出正確簽章。

### `resolveSourceId` 的優先序

固定為 **group → room → user**，不可依事件內容變動。這個值決定資料的可見範圍，一旦浮動，同一個群組的資產會被切成兩堆再也查不回來。

---

## `MediaController`

`dev.miudog.linebotcommercial.controller.MediaController`

**端點**：`GET /media/{shareToken}`

**職責**：把磁碟上的資產圖片以 HTTP 提供給 LINE 伺服器抓取。

| 方法 | 可見性 | 說明 |
|---|---|---|
| `ResponseEntity<Resource> serve(String shareToken)` | public | 依權杖回傳圖片；權杖無效或檔案遺失時一律回 404。 |

### 安全設計

LINE 發送圖片訊息時只接受**公開 HTTPS 網址**，無法直接吃本機檔案，因此這個端點必然對公網開放。為此：

- 路徑使用每筆資產獨立、不可預測的 `shareToken`（32 字元隨機值），**不是**流水號或檔名。用流水號等於讓任何人從 1 數到 N 就把整個資產庫抓光。
- 權杖無效與檔案遺失回應相同的 404，不讓外部藉回應差異推測權杖是否存在。
- `FileStorageService.resolve()` 會檢查路徑未逃出 storage root，即使資料庫內容被竄改也讀不到目錄以外的檔案。

> **部署階段注意**：這個端點沒有其他授權機制，安全性完全建立在權杖不可預測上。若公司政策要求更嚴格的控管，應在反向代理層加上來源 IP 白名單（限定 LINE 的 IP 範圍）或短時效簽名 URL。

---

## `AdminPageController` 與 `QuotationAdminController`

`AdminPageController` 將 `/admin` 與 `/admin/` 導向靜態管理頁；`QuotationAdminController`
提供只限 loopback 存取的報價管理 API，包含五格式查詢、品項與方案對應維護、AI 文字解析、
固定 JSON 驗證，以及主檔 XLSX／CSV 匯出。

正式 Excel 只能由 LINE 完整預覽的確認 postback 建立。舊版
`POST /api/admin/quotation-workbooks` 固定回覆 `CONFIRMATION_REQUIRED`，不得採信瀏覽器指定的
抬頭、單號或固定品項欄位來繞過確認交易。

`QuotationManagementController` 提供下列同樣只限 loopback 的管理讀取與操作：

- `GET /api/admin/quotation-drafts`：依關鍵字、狀態及分頁列出草稿。
- `GET /api/admin/quotation-drafts/{id}`：完整抬頭、品項快照、欄位證據、缺漏與程式計算金額。
- `GET /api/admin/quotation-drafts/{id}/selected-image`：只讀取所屬來源與草稿一致的 `.pending` 選圖。
- `GET /api/admin/quotations/{id}`：正式快照、檔案／LINE 狀態、選圖網址及安全稽核摘要。
- `GET /api/admin/quotations/{id}/selected-image`：只讀取正式報價關聯的選定資產。
- `POST /api/admin/quotations/{id}/download-links`：建立可複製的短效 HTTPS PDF URL；不另回 raw token，稽核也不保存 URL。

兩個圖片端點均不回傳資料庫路徑、分享權杖或 LINE 擁有者識別碼，並拒絕路徑逃逸與符號連結。
