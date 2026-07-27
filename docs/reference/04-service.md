# Service

[← 回索引](index.md)

業務邏輯。分成三組：資產核心、LINE 通訊、報價流程。

- 資產核心：[`AssetService`](#assetservice)、[`FileStorageService`](#filestorageservice)、[`CommandService`](#commandservice)
- LINE 通訊：[`LineStorageService`](#linestorageservice)
- 報價流程：[`QuotationService`](#quotationservice)、[`AiExtractionService`](#aiextractionservice)、[`QuotationCalculator`](#quotationcalculator)、[`QuotationPdfService`](#quotationpdfservice)

---

## `AssetService`

`dev.myudog.assetsmanagerlinebot.service.AssetService`

**職責**：資產生命週期的協調者：收錄 → 歸檔 → 查詢。

這是**唯一同時碰到檔案系統與資料庫**的地方，兩者的一致性由它負責：檔案搬移與路徑更新在同一個交易內完成，避免資料庫指向一個不存在的檔案。

| 方法 | 說明 |
|---|---|
| `Optional<Asset> ingest(messageId, sourceType, sourceId, uploaderId, content, contentType)` | 收錄圖片：先寫檔、再建索引，依收錄日期落地。重複 `messageId` 直接略過。 |
| `Optional<Asset> tag(String quotedMessageId, List<String> tags)` | 掛標籤。**不搬動檔案。** 第一個標籤視為主要資產編號。 |
| `List<Asset> search(String sourceId, List<String> tags, int limit)` | 依關鍵字查詢，多個關鍵字為 AND。 |
| `Map<String,Integer> tagCounts(String sourceId)` | 標籤與數量統計。 |
| `int countBySource(String sourceId)` | 該群組收錄總數。 |
| `Optional<Asset> findByShareToken(String shareToken)` | 供 `MediaController` 取圖。 |
| `Optional<Asset> findByMessageId(String messageId)` | 供 `#報價` 找出被引用的圖。 |
| `byte[] contentOf(Asset asset)` | 讀出完整位元組。刻意不回傳串流——同一份位元組要先送模型、再貼進 PDF，串流只能讀一次。 |

### 冪等性

LINE 在未收到 200 回應時會重送 webhook，因此 `ingest` 以 `messageId` 做冪等判斷。少了這道判斷，一次網路抖動就會讓同一張圖存成好幾份。

### 打標籤絕不搬動檔案

`tag()` 只寫資料庫。磁碟只依日期分層，分類完全由標籤承擔，因此：

- 使用者改標籤時檔案路徑**永遠不變**，備份與外部引用不會失效
- 同一張圖可以**同時屬於多個資產編號**，不必在磁碟上複製或做連結
- 不存在「檔案搬到一半失敗、資料庫指向不存在的路徑」這種狀態

這也是 `tag()` 不再宣告 `throws IOException` 的原因——它根本不碰檔案系統。

---

## `FileStorageService`

`dev.myudog.assetsmanagerlinebot.service.FileStorageService`

**職責**：圖片本體在磁碟上的落地、搬移與路徑安全。

**實體結構**：`{ASSETS_ROOT}/{yyyyMMdd}/{yyyyMMdd-HHmmssSSS}.jpg`

```
F:\資產庫\
├─ assets.db
├─ 20260727\
│  ├─ 20260727-224530123.jpg
│  └─ 20260727-224612456.jpg
└─ 20260728\
   └─ 20260728-091502001.jpg
```

| 方法 | 說明 |
|---|---|
| `StoredFile save(InputStream, String contentType)` | 寫入當天的日期資料夾，缺少的目錄自動建立。 |
| `Path resolve(String relativePath)` | 相對路徑還原成實體路徑，並擋下逃出資產庫根目錄的路徑。 |
| `Path root()` | 資產庫根目錄的絕對路徑，供疑難排解使用。 |
| `Path uniquePath(...)` | private，同一毫秒兩張圖時補上流水序號避免覆蓋。 |
| `static String extensionFor(String contentType)` | 由 MIME 決定副檔名，未知一律當 JPEG。 |

### 磁碟上沒有資產編號

檔案落地之後就**不再搬動**。分類完全交給資料庫的標籤——磁碟負責保存，資料庫負責組織，兩者職責不重疊。這是「指標法」的核心。

因此本類別沒有搬移檔案的方法，也沒有 `sanitize()`：使用者輸入完全不會進入路徑，標籤的正規化由 `CommandService.normalizeTag` 負責。

### 相對路徑，不是絕對路徑

對外只回傳「相對於資產庫根目錄、以 `/` 分隔」的路徑，資料庫也只存這個。因此整個資產庫連同 `assets.db` 可以整包搬到別台機器而不失效——從 Windows 開發機的 `F:/資產庫` 搬到 Linux 伺服器的 `/data/assets`，資料庫內容一個字都不用改。

### 路徑穿越防線

現在使用者輸入完全不會進入路徑（路徑只由時間戳組成），所以攻擊面本來就關閉了。`resolve()` 的根目錄檢查保留下來當第二道防線：即使 `assets.db` 被竄改，也讀不到資產庫以外的檔案。

### 時區固定台北

`ZoneId.of("Asia/Taipei")` 是寫死的。跟著容器時區跑的話，日期資料夾會在不同機器上跳動，同一天的照片被拆到兩個日期底下。

### 檔名為什麼帶到毫秒

檔名是純時間戳（`20260727-224530123.jpg`），直接看資料夾就能依時間排序。毫秒仍碰撞時（同一毫秒兩張圖）由 `uniquePath` 補上 `-1`、`-2`，避免後者覆蓋前者。

---

## `CommandService`

`dev.myudog.assetsmanagerlinebot.service.CommandService`

**職責**：群組文字訊息的指令解析與回覆組裝。是「使用者說的話」與「領域服務」之間唯一的翻譯層，不碰檔案系統也不碰資料庫。

| 方法 | 可見性 | 說明 |
|---|---|---|
| `void handleText(text, quotedMessageId, sourceId, replyToken)` | public | 總入口，先判斷是指令還是歸檔。 |
| `void handleCommand(body, quotedMessageId, sourceId, replyToken)` | private | 井字號指令分派。未知指令**不回應**。 |
| `void archiveQuotedImage(text, quotedMessageId, replyToken)` | private | 需求 ①：把被引用的圖片登記到 `zd` 編號底下。 |
| `void replyQuotation(quotedMessageId, replyToken)` | private | 需求 ②：對被引用的規格圖跑報價流程。 |
| `void replySearch(sourceId, tags, replyToken)` | private | 查詢並把圖片貼回群組。 |
| `void replyTagList(sourceId, replyToken)` | private | 列出編號與數量。 |
| `static List<String> extraTags(text, assetCode)` | private | 取出編號以外的附加標籤。 |
| `static String normalizeTag(String token)` | package | 去掉開頭井字號與控制字元，中文完整保留。 |

### 指令一覽

| 輸入 | 需要引用圖片 | 效果 |
|---|---|---|
| `zd12345` | ✅ | 登記到資產編號 `zd12345`（檔案不搬動） |
| `zd12345 台北 機房` | ✅ | 同上，額外字詞存成附加標籤 |
| `#查 zd12345` | ❌ | 取出圖片貼回群組 |
| `#標籤`／`#清單` | ❌ | 列出本群組所有編號與數量 |
| `#報價` | ✅ | AI 提取 → 計算 → 產報價單 |
| `#說明`／`#help` | ❌ | 用法 |

### 為什麼資產編號不加井字號

**井字號開頭一律當指令**，因此需求 ① 的資產編號刻意不加井字號，兩者不會互相誤判。若編號也用井字號，`#查` 這種指令就得靠保留字清單去排除，每加一個指令就多一個踩雷點。

### 編號正規化

`(?i)\bzd\d+\b` 大小寫皆可輸入，內部一律轉小寫。不正規化的話，`ZD123` 與 `zd123` 會在資料庫裡變成兩個不同的標籤，查詢時對不起來。

### 標籤不需要防路徑穿越

標籤只進資料庫、不會變成檔案路徑，所以 `normalizeTag` 只去掉開頭井字號與控制字元，中文完整保留。

---

## `LineStorageService`

`dev.myudog.assetsmanagerlinebot.service.LineStorageService`

**職責**：與 LINE Messaging API 之間所有 HTTP 往來的唯一出口。憑證只在這裡出現。

| 方法 | 說明 |
|---|---|
| `LineContent downloadContent(String messageId)` | 下載訊息原始內容。失敗回 null，不重試。 |
| `void replyText(String replyToken, String text)` | 回覆純文字。 |
| `void reply(String replyToken, List<Map<String,Object>> messages)` | 回覆一組訊息，超過 5 則主動截斷。 |
| `static Map<String,Object> textMessage(String text)` | 組文字訊息物件。 |
| `static Map<String,Object> imageMessage(String originalUrl, String previewUrl)` | 組圖片訊息物件。 |
| `void post(String url, Map<String,Object> body)` | private，送出 JSON POST。 |

### 三個 LINE 平台限制

1. **`replyToken` 只能用一次且有時效**，過期就得改用 push（會計費）。
2. **單次 reply 最多 5 則訊息**，超過整個請求會被退回——不是只丟掉多的那幾則，而是整批失敗。所以這裡主動截斷。
3. **訊息內容有保存期限**，webhook 進來後必須盡快下載，因此 `downloadContent` 不做重試。

### JSON 一律用 Jackson 序列化

訊息內容含中文與使用者自由輸入。用字串拼接組 JSON，一個引號或換行就把整個請求打壞，而且錯誤會延遲到 LINE 回 400 才浮現。

發送失敗**只記錄不拋出**，避免一則回覆失敗導致整個 webhook 回 500 而被 LINE 重送。

---

## `QuotationService`

`dev.myudog.assetsmanagerlinebot.service.quotation.QuotationService`

**職責**：報價流程的串接者：資料提取 → 計算 → 產出 PDF。

| 方法 | 說明 |
|---|---|
| `QuotationResult quote(byte[] infoImage, String contentType)` | 執行完整流程，回傳結果與卡關資訊。 |
| `boolean isAiConfigured()` | 供指令入口先行檢查設定。 |

> **目前狀態**：三段之中只有第一段（AI 提取）是完成的。後兩段會拋出 `UnsupportedOperationException`，被這裡接住後轉成 `QuotationResult.blockedStep`。這樣安排的用意是 AI 提取現在就能單獨測試，補完公式與模板後不必再改串接邏輯。

---

## `AiExtractionService`

`dev.myudog.assetsmanagerlinebot.service.ai.AiExtractionService`

**職責**：把規格圖／資訊圖送給 AI 模型，並把回應整理成結構化欄位。只做呼叫與結果處理，不知道報價公式，也不知道 PDF 長什麼樣。

| 方法 | 可見性 | 說明 |
|---|---|---|
| `boolean isConfigured()` | public | 端點、金鑰、模型三者都有值時為 true。 |
| `ExtractedSpec extract(byte[] imageBytes, String contentType)` | public | 完整流程：組請求 → 呼叫 → 取內容 → 解析 JSON → 檢查必要欄位。 |
| `List<String> requiredFields()` | private | 解析設定字串成必要欄位清單。 |
| `String callModel(...)` | private | 實際發出 HTTP 請求。 |
| `Map<String,Object> buildRequestBody(...)` | private | 組 OpenAI 相容的請求本文。 |
| `String extractContent(String responseBody)` | private | 取出 `choices[0].message.content`。 |
| `Map<String,Object> parseJsonObject(String content)` | private | 剝掉程式碼區塊標記後解析 JSON。 |
| `void validateRequiredFields(...)` | private | 缺漏時拋 `AiExtractionException`。 |

### 設定（全部留空，需自行填入）

| 環境變數 | 說明 |
|---|---|
| `AI_API_URL` | OpenAI 相容的 chat completions 端點 |
| `AI_API_KEY` | 金鑰 |
| `AI_MODEL` | 模型名稱（需支援讀圖） |
| `AI_REQUIRED_FIELDS` | 必要欄位，逗號分隔；留空代表不檢查 |
| `AI_TIMEOUT_SECONDS` | 逾時秒數，預設 60 |

三項缺任何一項，`#報價` 會直接回報「尚未設定」而不是等到逾時才失敗。

### 換成其他廠商的 API

請求格式採用 **OpenAI 相容的 chat completions**（圖片以 base64 data URL 內嵌），這是目前相容性最廣的一種。若最終選用的服務格式不同，只需要改 `buildRequestBody` 與 `extractContent` 兩個方法，其餘流程不受影響。

### 為什麼要剝程式碼區塊

即使提示詞明確要求只回 JSON，模型仍常常包上 ` ```json ` 區塊或加一句開場白。`parseJsonObject` 先剝掉標記，再擷取第一個 `{` 到最後一個 `}` 之間的內容。溫度設為 `0`——擷取工作要的是穩定而不是創意。

### 缺漏欄位的定義

「欄位不存在」與「欄位存在但值是 null／空字串」**都算缺漏**。提示詞要求模型找不到就填 null 且不要編造，所以後者才是常見情況。

---

## `QuotationCalculator`

`dev.myudog.assetsmanagerlinebot.service.quotation.QuotationCalculator`

**職責**：把 AI 擷取出來的規格換算成報價金額。

> ⚠️ **佔位實作，公式尚未定義。** `FACTOR_A`、`FACTOR_B`、`BASE_COST`、`primaryMetric()` 都是假名，等實際公式確定後直接替換，呼叫端不需要跟著改。

| 方法 | 說明 |
|---|---|
| `QuotationAmounts calculate(ExtractedSpec spec)` | 目前一律拋 `UnsupportedOperationException`。 |

刻意拋例外而不是回傳 0——回傳一個看起來合理但其實是亂算的金額，比明確失敗危險得多。

**待補**：參與運算的欄位名稱、各係數的實際數值、運算順序、金額進位規則。

---

## `QuotationPdfService`

`dev.myudog.assetsmanagerlinebot.service.quotation.QuotationPdfService`

**職責**：把規格、金額與資訊圖套進 PDF 報價單模板，產出成品檔案。

> ⚠️ **佔位實作，模板尚未提供。** `IMAGE_PAGE_INDEX`、`IMAGE_X/Y/WIDTH/HEIGHT` 皆為佔位常數。

| 方法 | 說明 |
|---|---|
| `boolean isConfigured()` | 模板路徑有值時為 true。 |
| `Path generate(spec, amounts, infoImage)` | 目前一律拋 `UnsupportedOperationException`。 |

**待補**：

1. 模板檔本身，以及它是「可填表單的 AcroForm」還是「純版面」
2. 各欄位在模板上的名稱或座標
3. 資訊圖要貼在第幾頁、左下角座標與寬高

**為什麼還沒引入 PDF 函式庫**：若模板是 AcroForm 表單，填欄位即可；若是純版面，得靠絕對座標描繪。兩者做法差很多，先引入可能挑錯。等模板到位再決定。
