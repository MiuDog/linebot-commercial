# Commercial 舊 SQLite／本機檔案遷移

遷移器會把舊 SQLite 可對應的資料表複製到已由 Flyway 建好的 PostgreSQL，並將 asset、pending image、quotation XLSX／PDF 複製到 S3 相容儲存。來源資料庫及來源檔案永遠不刪除。預設 `MIGRATION_EXECUTE=false`，只驗證 schema、檔案、路徑、數量與 SHA-256。

## 前置條件

1. 停止舊 App，複製一份 SQLite 與資產／報價目錄快照；對快照執行，不直接讀正在寫入的資料庫。
2. 啟動空白的目標 PostgreSQL／Bucket，讓正式 App 先執行 Flyway 後停止 App。
3. 準備目標 DB／S3／company 設定與 Secret；Bucket 應啟用 versioning。
4. 確認 `MIGRATION_ASSET_ROOT` 是舊 `asset.file_path` 與 `pending_image.staging_path` 的共同根目錄；`MIGRATION_OUTPUT_ROOT` 是舊 `quotation_file.relative_path` 的共同根目錄。

## Dry-run

以打包後的同版本 JAR 執行，並設定：

```text
SPRING_MAIN_WEB_APPLICATION_TYPE=none
MIGRATION_ENABLED=true
MIGRATION_EXECUTE=false
MIGRATION_LEGACY_DATABASE=<SQLite 快照絕對路徑>
MIGRATION_ASSET_ROOT=<舊圖片根目錄>
MIGRATION_OUTPUT_ROOT=<舊報價輸出根目錄>
MIGRATION_REPORT_PATH=<安全的報告路徑>
DATABASE_URL=<目標 PostgreSQL JDBC URL>
DATABASE_USERNAME=<目標帳號>
COMPANY_ID=<公司 ID>
OBJECT_STORAGE_ENDPOINT=<S3 或 MinIO endpoint>
OBJECT_STORAGE_BUCKET=<目標 Bucket>
```

Secret 使用 `/run/secrets` config tree 或部署平台 Secret 注入，不寫入命令歷史。報告只含模式、狀態、各表筆數、發現／複製檔案數與錯誤代碼，不含客戶內容、路徑、Token 或個資。

Dry-run 必須為 `SUCCEEDED`，且人工核對 SQLite 表筆數、缺檔清單、根目錄與目標 companyId。任何 `SOURCE_FILE_NOT_FOUND`、absolute locator、schema 欄位或 hash 問題都先修正來源副本／mapping，再重跑。

## 正式複製

1. 再次確認舊正式服務停止且快照沒有改變。
2. 將 `MIGRATION_EXECUTE=true`；其他設定與成功 dry-run 完全相同。
3. 遷移器以外鍵可成功的順序複製資料表，衝突時保持冪等；檔案採內容雜湊 key，上傳後重新讀 metadata 驗證。
4. PostgreSQL `storage_migration_ledger` 記錄每個來源識別、目標 key、hash 與 `VERIFIED`；重跑時已驗證且 hash 相同者跳過。
5. 任一步驟失敗會 rollback DB 並刪除本次建立的目標物件；來源不變。

## 切換 Gate

- 報告為 `SUCCEEDED`，source table counts 均不大於目標筆數。
- ledger 的 `VERIFIED` 數等於發現檔案數，抽樣下載後 SHA-256 一致。
- 五種公司範本另以公司資產包上傳／啟用，不從程式 Git 取得。
- staging 啟動後完成舊圖片、舊 XLSX／PDF、報價重試及 LINE sandbox 驗收。
- 保存來源快照至少一個核准保存期；不要由遷移器自動刪除。

失敗回復只需保持舊服務仍指向原 SQLite／檔案快照，並停止新服務。切換後若要回復舊服務，須先凍結新寫入並處理反向資料差異，不能假設 SQLite 已包含切換後資料。

