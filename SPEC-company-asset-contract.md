# SPEC：Company Asset Contract

狀態：Approved，2026-09-02 核准。

## User Story

身為公司資產管理者，我希望在不取得原始碼的情況下新增、更新、審核及回復公司資產，使應用程式映像保持通用且公司持續擁有自己的資料。

## Acceptance Criteria

1. 每套部署綁定唯一 `companyId`，不得在同一資料庫或 Bucket prefix 混入其他公司資料。
2. 公司資產至少包含 Logo、印章、報價 XLSX 範本、範本定義、品項主檔及固定聯絡資料。
3. 每個資產版本都有 manifest，包含 `schemaVersion`、`companyId`、`assetSetVersion`、物件用途、MIME、大小與 SHA-256。
4. 匯入流程依序執行 stage、格式驗證、完整性驗證、人工核准、原子啟用；驗證失敗不得改變 active 版本。
5. 已啟用版本不可原地覆寫；更新建立新版本，管理者可回復至上一個通過驗證的版本。
6. OCI 映像、JAR、Git repository 與 Log 不得包含公司資產內容或可下載公司資產的長效憑證。
7. 管理入口不對公網匿名開放，必須同時受網路邊界與管理憑證保護，並留下不含 Secret 的稽核紀錄。
8. 本地 Docker 可匯入相同 manifest 與物件；雲端可從公司控制的 S3 相容儲存取得相同版本。

## Out of Scope

- 多租戶共用部署。
- 直接編輯 XLSX 儲存格的完整線上設計器。
- 將 LINE Token、AI Key 或資料庫密碼當作公司資產保存。

## Data Model Changes

- 新增 `company_asset_set`：版本、狀態、manifest hash、建立／核准／啟用時間。
- 新增 `company_asset_object`：用途、object key、MIME、大小、SHA-256。
- 新增 `company_asset_activation`：啟用與回復稽核。
- 既有範本及主檔需能追溯至產生報價當時的 asset set 版本。

## API Changes

- 管理 API：上傳或註冊 staging 資產包、驗證、核准、啟用、列出版本及回復。
- 查詢 API 不回傳物件儲存憑證、內部 Bucket 或未遮蔽 object key。

## Edge Cases

- manifest 宣告檔案遺失、雜湊不符、重複用途或未知 schema 時整包拒絕。
- 啟用期間發生程序中止時，active 指標必須維持舊版本或完整切到新版本，不得半套生效。
- 正在產生的報價固定使用啟動時鎖定的 asset set，不受後續切版影響。

## Dependencies

無。
