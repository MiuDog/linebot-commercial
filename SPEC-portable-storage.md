# SPEC：Portable Storage

狀態：Approved，2026-09-02 核准。

## User Story

身為維護者，我希望雲端與本地 Docker 使用相同的 PostgreSQL 與 S3 相容儲存契約，避免容器重建造成資產、報價或狀態遺失。

## Acceptance Criteria

1. 結構化資料使用 PostgreSQL；公司資產、圖片、XLSX、PDF 與大型檔案使用 S3 相容物件儲存。
2. App 容器檔案系統僅可保存可重建的暫存檔，程序重啟後不得依賴容器內舊檔案。
3. 本地 Compose 啟動 PostgreSQL 與 S3 相容服務；雲端使用外部連線資訊而不更改應用程式碼。
4. 所有 object key 由程式產生並限制在 `companies/{companyId}/`，不得接受使用者提供完整 key 或 URL。
5. 資料庫與物件儲存跨系統操作使用 staging／補償／可重試狀態，禁止出現 DB 成功但正式物件不可追蹤的靜默成功。
6. 提供可重跑的 SQLite＋本機檔案遷移工具；先驗證、再複製、核對數量與 SHA-256，成功後才切換服務。
7. 遷移工具不刪除來源資料，並產生不含個資與 Secret 的結果報告。
8. Schema 由版本化 migration 管理，禁止依賴 `spring.sql.init.mode=always` 修改正式資料庫。

## Out of Scope

- 跨公司共享物件去重。
- CDN 作為真實資料來源。
- 自動刪除舊 SQLite 或舊檔案目錄。

## Data Model Changes

- SQLite schema 轉為 PostgreSQL migration，保留業務識別碼與唯一性語意。
- 檔案欄位改存 storage scope、object key、版本、MIME、大小與 SHA-256，不保存主機絕對路徑。
- 新增 migration ledger，記錄來源識別、目標識別與校驗結果。

## API Changes

- 媒體及下載 API 改由儲存介面串流物件或產生短效簽名 URL。
- 不公開 Bucket 名稱、長效 object URL 或底層憑證。

## Edge Cases

- PostgreSQL transaction rollback 後必須清理由該次操作建立的 staging object。
- S3 timeout 可重試，但不得重複建立資產資料列或報價流水號。
- 遷移遇到重複、缺檔或 hash 不符時必須停止切換並保留完整問題清單。

## Dependencies

`company-asset-contract`。
