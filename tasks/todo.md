# Excel 報價資料層工作項目

- [x] 定義 AI quotation request JSON Schema
  - Acceptance：可表示三種方案、多個品項、多張圖片及警告，且不含價格。
  - Verify：測試可載入並檢查必要欄位。

- [x] 定義三種 Excel 模板座標
  - Acceptance：CNS、一般架、船用皆有明細列、欄位、合計格及圖片策略。
  - Verify：測試確認三種方案代碼與工作表名稱。

- [x] 擴充 SQLite schema
  - Acceptance：可保存主檔、規則版本、AI 請求、圖片選擇及報價快照。
  - Verify：Spring Boot 使用真實 SQLite 初始化成功。

- [x] 執行完整驗證
  - Acceptance：既有測試與新增 schema 測試全部通過。
  - Verify：`.\mvnw.cmd test`

- [x] 定義案件名稱與輸出目錄
  - Acceptance：建立 `{根目錄}/報價單/{案件名稱}`，且名稱無法跳脫根目錄。
  - Verify：目錄服務與 schema／AI 契約測試通過。
