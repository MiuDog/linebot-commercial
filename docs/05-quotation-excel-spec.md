# Excel 報價資料契約與資料庫規格

## Objective

將 LINE 群組中的自然語言報價需求轉成可驗證的固定 JSON，依 `CNS`、`GENERAL`
（一般架）、`MARINE`（船用）三種計價方案取得品項資料、計算金額並套用既有 Excel
報價模板。

本階段只定義資料契約、SQLite schema 與模板定位，不匯入尚未提供的完整參數，也不實作
複雜的品項數量推算。

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

- `src/main/resources/schema.sql`：SQLite schema 與三種方案的基礎資料。
- `src/main/resources/ai/quotation-request.schema.json`：AI 固定輸出格式。
- `src/main/resources/quotation/template-definitions.json`：三種 Excel 模板的儲存格定位。
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

## AI Output Contract v1

```json
{
  "schemaVersion": "1.0",
  "quotationName": "台中港電器設備",
  "schemeCode": "CNS",
  "schemeConfidence": 0.98,
  "items": [
    {
      "itemCode": "SCAFFOLD_EXTERNAL",
      "quantity": 120,
      "sourceText": "外部鷹架 120 平方米",
      "confidence": 0.97
    }
  ],
  "selectedImageMessageId": "LINE_MESSAGE_ID",
  "imageAssessments": [
    {
      "messageId": "LINE_MESSAGE_ID",
      "distinctivenessScore": 0.92,
      "reason": "可清楚辨識施工位置與主要結構"
    }
  ],
  "warnings": []
}
```

契約規則：

- `schemeCode` 只能是 `CNS`、`GENERAL`、`MARINE`。
- `quotationName` 是使用者提供的工程／案件名稱，供報價快照及輸出資料夾使用。
- `quantity` 必須大於 0；AI 不可產生價格欄位。
- `itemCode` 必須來自呼叫模型時提供的可用品項清單。
- 無法可靠對應的輸入不得猜測成其他品項，應放入 `warnings`。
- 沒有圖片時，`selectedImageMessageId` 為 `null` 且 `imageAssessments` 為空陣列。
- 圖片有多張時，`selectedImageMessageId` 必須存在於 `imageAssessments`。

## Data Model

### 計價主檔

- `quotation_scheme`：三種計價方案及明細公開方式。
- `quotation_item`：跨方案共用的品項身分、名稱與 AI 別名。
- `quotation_scheme_item`：同一品項在特定方案下的規格、單位、單價、備註及排序。
- `quotation_rule`：可版本化的未來計算規則；現階段直接計數的品項可不綁規則。

品項名稱與方案價格拆開，因為同名品項在 CNS 與一般架中可能有不同規格或單價。

### 模板

- `quotation_template`：工作表、明細列範圍、欄位位置、稅率及圖片放置規則。
- 模板檔路徑可在稍後取得空白 Excel 後設定，不把範例檔名當成永久識別碼。

### 執行與歷史

- `quotation_request`：LINE 原始指令、AI 原始 JSON、契約版本與處理狀態。
- `quotation_request_image`：同批圖片、AI 區別度分數、理由及最終選圖。
- `quotation`：報價單表頭、方案、模板、金額與輸出檔。
- `quotation_line`：報價品項快照；保存當時的名稱、規格、數量、單價與複價。

`quotation_line.visibility` 支援：

- `CUSTOMER`：輸出到客戶 Excel。
- `INTERNAL`：只供內部計算與稽核，船用方案可用。

## Output Directory

根目錄由 `QUOTATION_ROOT_PATH` 提供。每份報價輸出前，系統建立：

```text
{QUOTATION_ROOT_PATH}/報價單/{quotationName}/
```

`quotationName` 在資料庫保存原始名稱；建立資料夾時會替換 Windows 不允許的字元、
處理保留名稱並阻止路徑跳脫。Excel 檔名稍後依正式報價編號規則決定。
使用 Docker Compose 時，`QUOTATION_ROOT_PATH` 填主機目錄；系統會掛載到容器內的
`/data/quotation-root`，應用程式不會直接使用 Windows 磁碟代號。

## Template Definitions Extracted from Example

| Scheme | Sheet | Detail rows | Capacity | Subtotal | Pre-tax | Tax | Total |
|---|---|---:|---:|---|---|---|---|
| CNS | CNS | 11–28 | 18 | G29 | G30 | G31 | G32 |
| GENERAL | 一般架 | 11–30 | 20 | G31 | G32 | G33 | G34 |
| MARINE | 船用 | 11–23 | 13 | G24 | G25 | G26 | G27 |

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

## Code Style

資料庫使用小寫 snake_case；Java／JSON 使用 camelCase；方案等 enum 值使用
UPPER_SNAKE_CASE。資料庫金額使用 `NUMERIC`，Java 端使用 `BigDecimal`。

```sql
unit_price NUMERIC NOT NULL CHECK (unit_price >= 0)
```

## Testing Strategy

- schema 整合測試需使用真實 SQLite。
- 驗證所有新資料表存在、外鍵已啟用，以及三種方案與模板定義成功初始化。
- 後續實作解析與計算時，先新增契約驗證和金額計算單元測試。
- Excel 產生器完成後，需檢查公式並渲染三種模板做視覺驗證。

## Boundaries

- Always：價格由資料庫取得；歷史報價保存快照；外部 AI 回應需先驗證。
- Ask first：更改稅率、報價編號規則、同批圖片的時間範圍、船用彙總公式。
- Never：讓 AI 自行決定價格；用目前範例中的客戶資料作為系統預設；覆寫使用者原始模板。

## Success Criteria

- SQLite 可重複初始化且原資產資料表不受影響。
- 三種方案及模板定位都有穩定代碼。
- AI 固定 JSON 不包含價格，且能表示多品項、多圖片與警告。
- schema 能保存方案品項、規則版本、AI 請求、圖片選擇、報價與明細快照。
- 船用報價能區分內部計算列與客戶輸出列。

## Deferred Inputs

- 各方案完整品項、代碼、別名、規格、單位、單價、備註與排序。
- 報價單根目錄的正式路徑。
- 空白 Excel 模板的正式檔名及最終圖片預留範圍。
- 客戶資訊來源及報價編號產生規則。
- 同批工程圖片的收集界線。
- 複雜品項數量推算規則及船用彙總規則。
