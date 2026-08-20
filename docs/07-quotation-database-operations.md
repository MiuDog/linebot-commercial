# 報價資料庫安全讀寫與匯出

報價主檔存放於 `{ASSETS_ROOT}/assets.db` 的 SQLite 資料庫。日常修改建議優先開啟
`http://localhost:8088/admin/`（目前 `server.port=8088`），因為管理頁會驗證欄位、使用參數化 SQL，並寫入
`admin_audit_log`。本文件提供查詢、批次調價或除錯時可使用的 SQL 範例。

## 執行前保護

1. 修改前停止應用程式，避免 SQLite 同時寫入。
2. 先備份資料庫；SQLite CLI 可使用 `.backup assets-before-price-update.db`。
3. 所有修改包在 `BEGIN IMMEDIATE` 與 `COMMIT` 之間，檢查錯誤時改用 `ROLLBACK`。
4. 不直接修改 `quotation`、`quotation_line`。它們是已建立報價的歷史快照，改主檔不應回寫歷史價格。

## 讀取五種報價格式

```sql
SELECT
    s.code,
    s.name,
    s.calculation_visibility,
    s.is_active,
    t.sheet_name,
    t.summary_only
FROM quotation_scheme s
LEFT JOIN quotation_template t
    ON t.scheme_id = s.id
    AND t.is_active = 1
ORDER BY s.id;
```

`MARINE` 應保持 `calculation_visibility = 'SUMMARY_ONLY'` 且模板
`summary_only = 1`；其內部計算規則尚未提供，不應自行改成明細輸出。

## 讀取某一格式的完整品項主檔

將 `CNS` 改成 `GENERAL`、`MARINE`、`BLANK` 或 `SALES` 即可查其他格式。

```sql
SELECT
    s.code AS scheme_code,
    i.code AS item_code,
    i.name AS item_name,
    i.aliases_json,
    si.specification,
    si.unit,
    si.unit_price,
    si.remark,
    si.display_order,
    si.calculation_mode,
    si.is_customer_visible,
    si.is_active
FROM quotation_scheme_item si
JOIN quotation_scheme s
    ON s.id = si.scheme_id
JOIN quotation_item i
    ON i.id = si.item_id
WHERE s.code = 'CNS'
ORDER BY si.display_order, si.id;
```

## 安全更新單價或固定欄位

範例將 CNS 的 `EXTERNAL_SCAFFOLD` 單價更新為 `220`。執行 `COMMIT` 前先確認
`changes()` 是 `1`，否則應執行 `ROLLBACK` 並檢查代碼。

```sql
BEGIN IMMEDIATE;

UPDATE quotation_scheme_item
SET
    unit_price = 220,
    updated_at = CURRENT_TIMESTAMP
WHERE scheme_id = (
    SELECT id
    FROM quotation_scheme
    WHERE code = 'CNS'
)
AND item_id = (
    SELECT id
    FROM quotation_item
    WHERE code = 'EXTERNAL_SCAFFOLD'
);

SELECT changes() AS updated_rows;

COMMIT;
```

若要由程式呼叫，`schemeCode`、`itemCode`、價格與文字一律用 `?` 參數綁定，禁止把
LINE 或 AI 文字串接進 SQL。AI 固定 JSON 只允許品項代碼、數量、來源文字與信心；
規格、單位、單價、備註及複價皆由後端解析或計算。

## 新增品項與格式關聯

```sql
BEGIN IMMEDIATE;

INSERT INTO quotation_item (
    code,
    name,
    aliases_json,
    is_active
)
VALUES (
    'NEW_ITEM_CODE',
    '新項目',
    json_array('新項目別名'),
    1
);

INSERT INTO quotation_scheme_item (
    scheme_id,
    item_id,
    specification,
    unit,
    unit_price,
    remark,
    display_order,
    calculation_mode,
    is_customer_visible,
    is_active
)
SELECT
    s.id,
    i.id,
    '規格內容',
    '式',
    0,
    '待確認價格',
    999,
    'DIRECT',
    1,
    0
FROM quotation_scheme s
CROSS JOIN quotation_item i
WHERE s.code = 'CNS'
AND i.code = 'NEW_ITEM_CODE';

SELECT changes() AS inserted_scheme_rows;

COMMIT;
```

新建的格式品項關聯預設 `is_active = 0`，應先在管理頁確認規格與價格，再啟用該關聯。

## 主檔匯出

管理頁的「匯出 Excel 主檔」會輸出正式 XLSX：

```text
GET /api/admin/quotation-master-data.xlsx
```

它包含五種格式的主檔欄位、凍結表頭、篩選器與數字格式。若需文字交換格式，可使用：

```text
GET /api/admin/quotation-master-data.csv
```

此檔案可用 Excel 開啟，但它是 `.csv`，不是保留格式、圖片、公式或工作表的 `.xlsx`。
真正報價 Excel 仍須由五種模板產生器輸出。XLSX 會將文字存成文字儲存格；CSV 匯出會保護以 `=`、`+`、`-`、`@`
開頭的文字，避免 Excel 將主檔內容當成公式執行。

## 主檔匯入

管理頁的「上傳 CSV 覆蓋主檔」以同一份匯出欄位整批取代主檔：

```text
POST /api/admin/quotation-master-data.csv
Content-Type: text/csv
```

日常維護流程是「匯出 CSV → 在 Excel 編輯 → 上傳覆蓋」；資料庫仍是執行時的唯一來源，
草稿與已確認報價的外鍵、唯一性與交易保護都不受影響。匯入規則：

- 標題必須與匯出完全相同，欄位錯位一律拒絕。
- 整份檔案在單一交易內套用；任何一列不合法就全部不套用，錯誤訊息指出實際列號。
- 匯入時先將全部主檔停用，再依檔案內容重新啟用，因此**未出現在檔案中的品項會被停用而不是刪除**，
  已確認報價的歷史快照與外鍵關聯完全保留。
- 品項代碼必須符合 `^[A-Z][A-Z0-9_]{0,99}$`；`AI別名`以「、」分隔，供 AI 對應使用者的近似說法。
- `MARINE`、`BLANK`、`SALES` 不使用固定品項，這三種格式的資料列會被拒絕。
- 空白「報價格式代碼」代表尚未指派到任何格式的共用品項，只更新品項主檔本身。

## 本機管理 API

| 方法與路徑 | 用途 |
|---|---|
| `GET /api/admin/quotation-schemes` | 列出五種格式與模板狀態 |
| `GET /api/admin/quotation-items` | 列出共用品項主檔 |
| `POST /api/admin/quotation-items` | 新增品項 |
| `PATCH /api/admin/quotation-items/{itemId}` | 部分更新品項 |
| `GET /api/admin/quotation-schemes/{schemeCode}/items` | 列出格式固定欄位 |
| `PUT /api/admin/quotation-schemes/{schemeCode}/items/{itemId}` | 新增或更新格式品項 |
| `GET /api/admin/quotation-ai-status` | 檢查 AI 是否已設定，不回傳金鑰或設定值 |
| `POST /api/admin/quotation-ai-parse` | 將純文字指令轉成固定格式並以主檔補齊欄位（需帶 `schemeCode`） |
| `POST /api/admin/quotation-request-validation?schemeCode=` | 以指定格式嚴格驗證 AI JSON 並預覽解析結果 |
| `GET /api/admin/quotation-master-data.xlsx` | 匯出正式 Excel 主檔 |
| `GET /api/admin/quotation-master-data.csv` | 匯出 UTF-8 主檔 CSV |
| `POST /api/admin/quotation-master-data.csv` | 以同一份 CSV 格式整批覆蓋主檔 |

上述 `/admin/` 與 `/api/admin/` 僅接受本機 loopback 直接連線，帶有轉送來源標頭或來自
非本機位址的請求會被拒絕，避免管理功能經公開通道暴露。
