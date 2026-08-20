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

**正式歸檔結構**：`{ASSETS_ROOT}/{部門代碼}/{yyyyMMdd}/{yyyyMMdd-流水號}.jpg`

```
F:\資產庫\
├─ assets.db
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

| 方法 | 說明 |
|---|---|
| `StoredFile savePending(InputStream, String contentType)` | 寫入 `.pending`，等待合法部門代碼。 |
| `StoredFile archivePending(...)` | 寫入部門與當天日期資料夾，分配該日獨立流水號。 |
| `Path resolve(String relativePath)` | 相對路徑還原成實體路徑，並擋下逃出資產庫根目錄的路徑。 |
| `Path root()` | 資產庫根目錄的絕對路徑，供疑難排解使用。 |
| `Path uniquePath(...)` | private，同一毫秒兩張圖時補上流水序號避免覆蓋。 |
| `static String extensionFor(String contentType)` | 由 MIME 決定副檔名，未知一律當 JPEG。 |

### 部門與日期分層

正式圖片依完整部門代碼建立第一層資料夾，再依台北日期建立第二層。流水號只掃描當天資料夾，所以每個部門每天都從 `01` 開始，超過 `99` 才擴充位數。

### 相對路徑，不是絕對路徑

對外只回傳「相對於資產庫根目錄、以 `/` 分隔」的路徑，資料庫也只存這個。因此整個資產庫連同 `assets.db` 可以整包搬到別台機器而不失效——從 Windows 開發機的 `F:/資產庫` 搬到 Linux 伺服器的 `/data/assets`，資料庫內容一個字都不用改。

### 路徑穿越防線

只有通過嚴格格式驗證的大寫部門代碼會進入路徑；`resolve()` 仍會正規化並阻擋逃出資產庫根目錄的結果。

### 時區固定台北

`ZoneId.of("Asia/Taipei")` 是寫死的。跟著容器時區跑的話，日期資料夾會在不同機器上跳動，同一天的照片被拆到兩個日期底下。

### 每日流水號

檔名格式為 `yyyyMMdd-流水號`。`01` 到 `99` 固定兩位數，之後依序使用 `100`、`101`；隔天建立新的日期資料夾並重新從 `01` 開始。

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
| 標記機器人 + `ping` | ❌ | 回覆 `pong` 與本次事件的延遲毫秒數 |

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

**職責**：舊版相容入口。正式 LINE 報價已改由 `QuotationLineWorkflowService` 與
`QuotationGenerationCoordinator` 執行；新功能不可再接回這個三段式介面。

| 方法 | 說明 |
|---|---|
| `QuotationResult quote(byte[] infoImage, String contentType)` | 執行完整流程，回傳結果與卡關資訊。 |
| `boolean isAiConfigured()` | 供指令入口先行檢查設定。 |

> **相容邊界**：此類別仍可能回傳舊版 `blockedStep`，但 LINE webhook 不再呼叫它。第一階段正式流程
> 已支援直接數量計價、五格式 Excel、圖片、PDF 與交付；只有長寬高等第二階段數量推算尚未實作。

---

## 本機報價管理與 Excel 服務

- `QuotationAdminService`：驗證並協調品項與方案固定資料異動。
- `QuotationAiPromptService`：建立只含代碼、名稱與別名的 AI 目錄，不暴露價格。
- `QuotationAiParsingService`：呼叫 OpenAI 相容端點後，立即套用固定 JSON 契約與資料庫解析。
- `QuotationRequestValidationService`：拒絕未知欄位，解析主檔固定欄位，並以 `BigDecimal` 計算 DIRECT 複價。
- `QuotationMasterDataCsvService`：輸出 UTF-8 CSV，並避免儲存格內容被 Excel 當成公式執行。
- `QuotationMasterDataXlsxService`：輸出正式 XLSX 主檔，以文字儲存格隔離公式並保留數值欄位型別。
- `QuotationWorkbookService`：只改寫範本中的表頭、明細與合計 XML；圖片、蓋章、列印設定與其餘套件內容保持原樣。船用格式只寫最終數字，不保留計算公式。
- `QuotationConversationService`：控制多輪缺漏、圖片詢問、完整預覽、確認與取消狀態。
- `QuotationCalculationService`：以正式主檔與 `BigDecimal` 建立五格式直接計價結果。
- `QuotationAssetArchiveService`：將全部候選原圖由 `.pending` 搬入正式日期流水號資料夾並補償失敗。
- `QuotationGenerationLauncher`：喚醒專用單工作者；工作本體由 SQLite 租約保存，可在重啟後恢復。
- `QuotationGenerationCoordinator`：依序完成圖片歸檔、Excel、PDF 與 LINE 最終交付。
- `QuotationPdfService`：以本機 Microsoft Excel 的既有列印設定匯出 PDF，失敗保留 Excel 與重試狀態。
- `QuotationDeliveryService`：由正式快照建立 Flex 摘要與 HTTPS PDF 下載按鈕。

這組服務已由 LINE 一對一 webhook 與 `/admin/` 管理頁共同使用。LINE 確認先同步配置流水號，
再提交背景產出，避免 webhook 等待 Excel COM。

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

**職責**：舊版第二階段工程尺寸推算的保留介面，不參與第一階段正式報價。

> ⚠️ 長寬高、周長、體積等第二階段數量推算仍待業務規則。第一階段直接數量的複價、5% 稅額與
> 含稅總額已由 `QuotationCalculationService` 完成，不得把這個舊介面的例外誤認為正式流程未完成。

| 方法 | 說明 |
|---|---|
| `QuotationAmounts calculate(ExtractedSpec spec)` | 目前一律拋 `UnsupportedOperationException`。 |

刻意拋例外而不是回傳 0——回傳一個看起來合理但其實是亂算的金額，比明確失敗危險得多。

**延後**：工程尺寸推算使用的欄位、係數與數量換算規則。

---

## `QuotationPdfService`

`dev.myudog.assetsmanagerlinebot.service.quotation.QuotationPdfService`

**職責**：把正式 XLSX 交給本機 Microsoft Excel，沿用原列印設定匯出同名 PDF。

| 方法 | 說明 |
|---|---|
| `boolean isConfigured()` | 報價輸出根目錄已設定時為 true。 |
| `PdfExportResult export(long quotationId)` | 以既有正式 XLSX 初次匯出 PDF。 |
| `PdfExportResult retry(long quotationId)` | 只重試 `PDF_FAILED`，沿用原流水號與路徑。 |

服務只呼叫固定的 `scripts/export-quotation-pdf.ps1`，不執行使用者文字。未安裝 Excel、無可用
印表機、COM 或逾時失敗時會清理不完整 PDF、保留 XLSX 並保存 `PDF_FAILED`。專用背景執行器
固定單一工作者，佇列容量由 `QUOTATION_GENERATION_QUEUE_CAPACITY` 控制。
