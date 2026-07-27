# Record

[← 回索引](index.md)

不可變資料載體。全部是 Java `record`，沒有 setter，跨層傳遞時不會被偷改。

---

## `Asset`

`dev.myudog.assetsmanagerlinebot.domain.Asset`

**職責**：一筆資產索引的不可變快照，是資料庫列與各層之間的共同語言。

本專案採「指標法」：圖片本體永遠留在磁碟，資料庫只保存指向它的 `filePath`。**因此本紀錄不含任何影像位元組。**

| 欄位 | 型別 | 說明 |
|---|---|---|
| `id` | `Long` | 流水號；尚未寫入時為 null |
| `messageId` | `String` | LINE 訊息 id，對應引用回覆並防止重複收錄 |
| `shareToken` | `String` | 對外取圖用的不可預測權杖 |
| `sourceType` | `String` | group／room／user |
| `sourceId` | `String` | 資料以此切開，不同群組互不可見 |
| `uploaderId` | `String` | 上傳者 LINE userId |
| `filePath` | `String` | **相對**路徑，一律以 `/` 分隔 |
| `contentType` | `String` | 原始 MIME 型態 |
| `fileSize` | `Long` | 位元組數 |
| `createdAt` | `Instant` | 收錄時間 |
| `tags` | `List<String>` | 編號與標籤，**第一個是主要編號**；未載入時為空集合 |

| 方法 | 說明 |
|---|---|
| `String dateFolder()` | 檔案落在哪一天的資料夾，即 `filePath` 的第一段，形如 `20260727`。 |
| `String primaryTag()` | 主要資產編號，也就是第一個標籤；尚未打標籤時為 null。 |

`filePath` 用相對路徑且固定 `/` 分隔，是為了讓同一份 `assets.db` 在 Windows 與 Linux 容器之間搬移時不會失效。

**磁碟路徑只反映收錄日期，與分類無關。** 資產編號存在 `tags` 裡而不是路徑裡，所以 `dateFolder()` 拿到的永遠是日期，要知道歸屬哪個編號請看 `primaryTag()`。

---

## `StoredFile`

`FileStorageService.StoredFile`（巢狀 record）

落地結果。

| 欄位 | 說明 |
|---|---|
| `relativePath` | 相對於 storage root、以 `/` 分隔的路徑 |
| `size` | 實際寫入的位元組數 |
| `contentType` | 原始 MIME 型態 |

---

## `LineContent`

`LineStorageService.LineContent`（巢狀 record）

LINE 回傳的原始內容。

| 欄位 | 說明 |
|---|---|
| `stream` | 內容串流，**呼叫端負責關閉** |
| `contentType` | 回應標頭的 MIME 型態，副檔名靠它決定 |

---

## `ExtractedSpec`

`dev.myudog.assetsmanagerlinebot.service.ai.ExtractedSpec`

**職責**：AI 從規格圖／資訊圖讀出來的結構化結果。

| 欄位 | 說明 |
|---|---|
| `fields` | `Map<String,Object>`，欄位名稱到值 |
| `rawResponse` | 模型原始輸出，出問題時可直接看它回了什麼 |

| 方法 | 說明 |
|---|---|
| `String text(String key)` | 以字串取值；欄位不存在回 null。 |
| `BigDecimal number(String key)` | 以數值取值，會先濾掉數字以外的字元。 |

### 為什麼欄位放在 Map 而不是 record component

實際要提取哪些欄位由 `AI_REQUIRED_FIELDS` 與提示詞決定。寫死成 component 的話，**規格每調整一次就要改 Java 程式碼並重新編譯部署**；放 Map 則只需改環境變數。

`number()` 會濾字元是因為模型常把數字放在單位裡（例如 `"1200 mm"`、`"12 組"`），直接 `new BigDecimal()` 會炸。

---

## `QuotationAmounts`

`dev.myudog.assetsmanagerlinebot.service.quotation.QuotationAmounts`

**職責**：報價計算的結果，是計算器與 PDF 產生器之間的資料契約。

> ⚠️ **欄位組成為佔位設計**，等實際公式與報價單版面確定後再調整。

| 欄位 | 說明 |
|---|---|
| `unitPrice` | 單價（佔位） |
| `quantity` | 數量（佔位） |
| `subtotal` | 未稅小計（佔位） |
| `tax` | 稅額（佔位） |
| `total` | 含稅總計（佔位） |

先定義出來是為了讓上下游可以先串起來，而不是等公式到位才開始接。

---

## `QuotationResult`

`QuotationService.QuotationResult`（巢狀 record）

報價流程的執行結果。

| 欄位 | 說明 |
|---|---|
| `spec` | 提取出的規格；提取失敗時為 null |
| `amounts` | 計算出的金額；公式未定義時為 null |
| `pdfPath` | 產出的報價單路徑；模板未提供時為 null |
| `blockedStep` | 卡在哪一步，全部完成時為 null |

| 方法 | 說明 |
|---|---|
| `boolean isComplete()` | 沒有卡關時為 true。 |

刻意把「提取成功但後段未完成」表達成一種**結果**而非例外，讓使用者至少能看到 AI 讀出了什麼——這在調校提示詞與必要欄位設定時非常有用。
