# Excel 報價資料契約與資料庫規格

## Objective

將 LINE 私訊中的自然語言報價需求轉成可驗證的固定 JSON，依 `CNS`、`GENERAL`
（一般架）、`MARINE`（船用）、`BLANK`（空白）、`SALES`（銷售報價單）五種格式
取得品項資料、計算金額並套用既有 Excel 報價模板。

本階段已匯入上傳活頁簿中的 CNS 與一般架主檔；船用、空白與銷售報價單尚無固定品項。
複雜品項數量推算仍待規則。

## Tech Stack

- Java 25
- Spring Boot 4.1
- SQLite
- Jackson
- Excel 模板：沿用使用者提供的 `.xlsm`／`.xlsx` 格式

## Commands

- 完整測試：`.\mvnw.cmd test`
- 建置：`.\mvnw.cmd package -DskipTests`
- 本機啟動：`.\mvnw.cmd spring-boot:run`

## Project Structure

- `src/main/resources/schema.sql`：SQLite schema、五種格式、21 個共用品項與方案固定資料。
- `src/main/resources/ai/quotation-request.schema.json`：AI 固定輸出格式。
- `src/main/resources/quotation/template-definitions.json`：五種 Excel 工作表的儲存格定位。
- `src/main/resources/line/rich-menu.json`：LINE 下方六宮格選單定義。
- `src/main/resources/static/admin/`：只允許本機存取的品項管理頁面。
- `outputs/excel-templates/`：變數化活頁簿、五張預覽與提取報告。
- `docs/05-quotation-excel-spec.md`：此規格與後續欄位說明。
- `src/test/java/.../QuotationSchemaTest.java`：schema 初始化驗證。

## Responsibilities

### AI

- 判斷計價方案。
- 將使用者文字對應到系統提供的品項代碼。
- 擷取每個品項的數量。
- 對同批工程圖片評估區別度，選出一張代表圖片。
- 回報無法確定的內容及警告。

AI 不得決定單價、複價、稅額或總價。

### Application

- 驗證 AI JSON 契約。
- 以品項代碼查詢資料庫。
- 依資料庫價格及規則計算。
- 建立不可隨品項改價而變動的報價快照。
- 套用 Excel 模板並插入代表圖片。

### Excel

- 顯示報價結果與公式。
- 一般方案顯示實際使用的品項明細。
- 船用方案只顯示客戶可見的彙總項目，不顯示內部推算明細。
- 圖片採 `CONTAIN`：等比例縮放、完整顯示、不裁切。

## AI／OCR Output Contract v2

```json
{
  "schemaVersion": "2.0",
  "schemeCode": "CNS",
  "schemeConfidence": 0.98,
  "headerPatch": {
    "companyName": {
      "value": "正定工程",
      "sourceText": "公司：正定工程",
      "confidence": 0.99
    },
    "workName": {
      "value": "台中港電器設備",
      "sourceText": "工程名稱：台中港電器設備",
      "confidence": 0.98
    }
  },
  "standardItemIntents": [
    {
      "itemCode": "EXTERNAL_SCAFFOLD",
      "matchedName": "外牆鷹架",
      "quantity": 120,
      "sourceText": "外部鷹架 120 平方米",
      "confidence": 0.97
    }
  ],
  "customItems": [],
  "removedItemCodes": [],
  "selectedImageMessageId": "LINE_MESSAGE_ID",
  "imageAssessments": [
    {
      "messageId": "LINE_MESSAGE_ID",
      "qualityScore": 0.94,
      "viewpointScore": 0.9,
      "distinctivenessScore": 0.92,
      "reason": "可清楚辨識施工位置與主要結構"
    }
  ],
  "imageDeclined": false,
  "missingBaseFields": [],
  "missingItemFields": [],
  "nextAction": "SHOW_PREVIEW",
  "warnings": []
}
```

契約規則：

- `schemeCode` 只能是 `CNS`、`GENERAL`、`MARINE`、`BLANK`、`SALES`。
- 無法可靠判斷格式時，`schemeCode` 必須為 `null`、`schemeConfidence` 必須為 `0`，並列入頂部缺漏。
- `headerPatch` 只保存本次訊息明確提供且信心至少 `0.75` 的值、來源原文及信心；低信心值只能列入缺漏，不得猜測。
- 標準品項的 `quantity` 可為 `null`，有值時必須大於 0 且不超過 `1,000,000,000`。
- `itemCode` 必須來自呼叫模型時提供的可用品項清單。
- 標準品項只允許 `itemCode`、`quantity`、`sourceText`、`confidence`；出現名稱、規格、單位、單價、備註或複價會整份拒絕。
- `customItems` 的臨時／動態品項可保存使用者明確提供的固定欄位，但每個值都要附來源原文及信心，缺漏欄位必須為 `null`。
- CNS／一般架最多兩筆 `TEMPORARY`；空白／銷售使用 `DYNAMIC`，且不會自動寫回正式主檔。
- AI 不可輸出複價、小計、稅額、總價、流水號、檔案路徑或傳送狀態。
- 沒有圖片時，`selectedImageMessageId` 為 `null`；若使用者明確拒絕圖片，`imageDeclined` 才可為 `true`。
- 圖片有多張時，`selectedImageMessageId` 必須存在於 `imageAssessments`，且必須是
  `distinctivenessScore` 最高分之一；同分可選任一張。
- `missingBaseFields` 一次列出全部頂部缺漏；`missingItemFields` 以每個品項一組的方式一次列出全部缺漏。
- `nextAction` 只允許請求基礎欄位、請求品項欄位、請求圖片決策或顯示預覽；AI 只宣告，應用程式驗證後才執行。

本機可在 `/admin/` 的「從文字建立報價資料」輸入自然語言試跑 AI，也可在「AI JSON 測試」
貼入固定格式，或呼叫 `POST /api/admin/quotation-ai-parse` 與
`POST /api/admin/quotation-request-validation`。驗證成功後，完整預覽資料中的標準品項名稱、
規格、單位、單價與備註來自資料庫；這個 AI 驗證階段不計算複價、稅額或總價。

管理頁的解析與 JSON 驗證只供試跑及完整預覽，不得直接建立正式 Excel。正式檔案只能在 LINE
草稿完成完整預覽並由使用者明確確認後，由確認交易分配流水號、保存正式快照並排入背景工作。
舊版 `POST /api/admin/quotation-workbooks` 入口固定回覆 `CONFIRMATION_REQUIRED`，避免繞過確認。

## Data Model

### 計價主檔

- `quotation_scheme`：五種報價格式及明細公開方式。
- `quotation_item`：跨方案共用的品項身分、名稱與 AI 別名。
- `quotation_scheme_item`：同一品項在特定方案下的規格、單位、單價、備註及排序。
- `quotation_rule`：可版本化的未來計算規則；現階段直接計數的品項可不綁規則。

品項名稱與方案價格拆開，因為同名品項在 CNS 與一般架中可能有不同規格或單價。

### 模板

- `quotation_template`：工作表、明細列範圍、欄位位置、稅率及圖片放置規則。
- 五種格式各自使用 `outputs/excel-templates/quotation-template-{SCHEME}.xlsx` 單工作表範本。
- 原始 `templates/公司名稱-工作名稱 20260710-01.xlsm` 與 `template-analysis.json` 作為維護比對來源；正式輸出只選用拆分後的五份單工作表範本。

### 本機管理與主檔匯出

- `/admin/` 可建立或更新品項、AI 別名，以及各格式的規格、單位、單價、備註、順序與計價模式。
- 管理頁會顯示 AI 是否已設定；LINE 草稿可引用名片圖片做 OCR，並保存、評分與預覽工程候選圖。
- 管理頁及 `/api/admin/` 只接受本機 loopback 直接連線，不允許經公開代理轉送。
- 正式報價區可依條件查詢、查看完整快照、下載 Excel／PDF，並在合法狀態下重試 PDF、LINE
  傳送或撤銷公開下載連結；重試沿用原報價單號與既有快照。
- Excel／PDF 工作先保存於 SQLite `quotation_generation_job`，再由專用單工作者以租約執行；程序
  重啟會接續待辦及過期租約，失敗會保存錯誤碼並退避重試。記憶體執行器容量由
  `QUOTATION_GENERATION_QUEUE_CAPACITY` 設定，滿載只延後喚醒，不會遺失資料庫工作。
- XLSX 失敗可由管理頁重新排入同一份報價工作，沿用原報價單號、日期流水號與不可變快照。
- 管理頁的寫入 API 除限本機 loopback 外，還要求 `X-Local-Admin-Request: 1`，並驗證同源
  `Origin`／`Sec-Fetch-Site`，避免惡意網站借用使用者瀏覽器送出管理操作。
- 「匯出 Excel 主檔」會下載正式 `.xlsx`，包含凍結表頭、篩選器、欄寬、數字格式與交錯列色。
- 「另存 CSV」保留為輕量交換格式；兩種匯出都會避免文字被 Excel 當成公式執行。
- SQL 批次讀寫範例見 `docs/07-quotation-database-operations.md`。

### LINE 下方選單決策

建議正式流程使用六宮格 Rich Menu，因為建立報價、查看草稿、選擇格式、上傳圖片、說明與取消
都是高頻入口。資料庫已保存六個穩定 action，`src/main/resources/line/rich-menu.json` 也已定義
LINE 所需的 2500×1686 點擊區域。多輪草稿與按鈕 postback 已完成；Rich Menu 圖片的建立、
上傳與設為預設選單仍是部署操作，不由應用程式啟動時自動變更 LINE 帳號設定。

### Excel 範本使用原則

五份維護範本保留可讀的變數字串，方便日後用 Excel 查看欄位用途；執行期不以字串搜尋替換，
而是依 `template-definitions.json` 的固定儲存格位置寫入。這樣即使品項名稱、規格或價格在資料庫
變動，也不必改程式或重新製作範本；固定 Logo、圖片、蓋章、正定信箱與電話不在可寫入座標內。

### 執行與歷史

- `quotation_request`：LINE 原始指令、AI 原始 JSON、契約版本與處理狀態。
- `quotation_request_image`：同批圖片、AI 區別度分數、理由及最終選圖。
- `quotation`：報價單表頭、方案、模板、金額與輸出檔。
- `quotation_line`：報價品項快照；保存當時的名稱、規格、數量、單價與複價。

`quotation_line.visibility` 支援：

- `CUSTOMER`：輸出到客戶 Excel。
- `INTERNAL`：只供內部計算與稽核，船用方案可用。

## Output Directory

根目錄優先由 `QUOTATION_ROOT_PATH` 提供；為相容舊設定，未填時會使用
`QUOTATION_OUTPUT_PATH`。兩者都代表使用者指定的上層根目錄，系統自行附加 `報價單/`。
例如目標是 `E:/報價單` 時，變數應填 `E:/`。正式確認後建立：

```text
{QUOTATION_ROOT_PATH}/報價單/YYYYMMDD-XX/
```

Excel／PDF 基本檔名為 `公司名稱-工作名稱 YYYYMMDD-XX`；建立時會替換 Windows 不允許字元、
處理保留名稱並阻止路徑跳脫。銷售報價單會在未含 `S` 的報價單號前自動補上 `S`。
使用 Docker Compose 時，`QUOTATION_ROOT_PATH` 填主機目錄；系統會掛載到容器內的
`/data/quotation-root`，應用程式不會直接使用 Windows 磁碟代號。

一般圖片的 `file_path` 相對於 `ASSETS_ROOT`；正式確認後的報價圖片則相對於上述報價根目錄。
服務只根據資料庫 `quotation_asset` 關聯選擇可信的儲存範圍，再做各自根目錄的 containment 驗證，
不從可被修改的路徑前綴猜測範圍。一般資產的檔案調和作業不會把正式報價圖片誤判為遺失檔案。

## Template Definitions Extracted from Example

| Scheme | Sheet | Detail rows | Capacity | Subtotal | Pre-tax | Tax | Total |
|---|---|---:|---:|---|---|---|---|
| CNS | CNS | 11–31 | 21 | G32 | G33 | G34 | G35 |
| GENERAL | 一般架 | 11–30 | 20 | G31 | G32 | G33 | G34 |
| MARINE | 船用 | 11–23 | 13 | G24 | G25 | G26 | G27 |
| BLANK | 空白 | 11–28 | 18 | G29 | G30 | G31 | G32 |
| SALES | 銷售報價單 | 11–28 | 18 | G29 | G30 | G31 | G32 |

共同欄位：

- A：NO.
- B：品項
- C：規格／說明
- D：數量
- E：單位
- F：單價
- G：複價
- H：備註

圖片優先放入未使用的明細列空間，範圍由模板定義提供；若剩餘空間不足，產生器不得讓
圖片遮住任何可見品項或金額。

### Excel 提取結果與限制

- CNS 匯入 21 筆方案品項，一般架匯入 20 筆；共用品項去重後為 21 筆。
- 初始化採 `INSERT OR IGNORE`，因此日後從管理頁改價，不會在重啟時被原始 Excel 覆蓋。
- 上傳的 XLSM 含 VBA；輸出的 XLSX 不保留 VBA，而目前 Java 報價流程也不依賴巨集。
- 已經使用本機 Microsoft Excel 將五張表拆成五個單工作表 XLSX；每份均保留原始圖片、
  蓋章、合併儲存格、列印範圍、紙張方向、縮放與固定正定聯絡資料。
- Excel 產生器只改寫表頭、明細與合計儲存格，其餘 ZIP 內容原樣保留；五種實際輸出均已由
  artifact-tool 與 Microsoft Excel 開啟驗證，內嵌媒體雜湊與原範本一致。船用輸出不含公式，
  其餘格式以公式顯示 DIRECT 明細與合計。
- 正式模板設定分別指向 `quotation-template-CNS.xlsx`、`quotation-template-GENERAL.xlsx`、
  `quotation-template-MARINE.xlsx`、`quotation-template-BLANK.xlsx` 與
  `quotation-template-SALES.xlsx`。

## Code Style

資料庫使用小寫 snake_case；Java／JSON 使用 camelCase；方案等 enum 值使用
UPPER_SNAKE_CASE。資料庫金額使用 `NUMERIC`，Java 端使用 `BigDecimal`。

```sql
unit_price NUMERIC NOT NULL CHECK (unit_price >= 0)
```

## Testing Strategy

- schema 整合測試需使用真實 SQLite。
- 驗證所有新資料表存在、外鍵已啟用，以及五種方案與模板定義成功初始化。
- 後續實作解析與計算時，先新增契約驗證和金額計算單元測試。
- Excel 產生器完成後，需檢查公式並渲染五種模板做視覺驗證。

## Boundaries

- Always：價格由資料庫取得；歷史報價保存快照；外部 AI 回應需先驗證。
- Ask first：更改稅率、報價編號規則、同批圖片的時間範圍、船用彙總公式。
- Never：讓 AI 自行決定價格；用目前範例中的客戶資料作為系統預設；覆寫使用者原始模板。

## Success Criteria

- SQLite 可重複初始化且原資產資料表不受影響。
- 五種方案及模板定位都有穩定代碼。
- AI 固定 JSON 不包含價格，且能表示多品項、多圖片與警告。
- schema 能保存方案品項、規則版本、AI 請求、圖片選擇、報價與明細快照。
- 船用報價能區分內部計算列與客戶輸出列。

## Deferred Inputs

- 複雜品項數量推算規則，例如長寬高、周長或體積推算。
- 個別案件免稅、含稅價反推或非 5% 稅率。
- 船用內部推算規則；客戶版已固定只輸出程式提供的最終彙總。
