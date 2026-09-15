# 本地部署驗證（2026-09-15）

## 已完成

- Commercial：`mvnw.cmd clean verify`，373 tests，0 failures／errors／skipped。
- Document：`mvnw.cmd verify`，94 tests，0 failures／errors／skipped。
- 兩個專案 Docker 映像建置成功，`docker compose up -d --wait` 成功；PostgreSQL、MinIO 與 App healthy，Bucket 初始化退出碼 0。
- `http://127.0.0.1:8088/readyz` 與 `http://127.0.0.1:8089/readyz` 回應 `{"status":"UP"}`。
- CSV 測試覆蓋五種格式、事件冪等、不呼叫 AI、非法 UTF-8、實際／宣告大小、引號、數量、格式衝突與群組限制。
- AI 測試驗證實際 HTTP 請求的 strict Schema、候選裁切、null patch、截斷／拒絕、最多一次修復與 500 品項共用 enum。
- S3 圖片引用／簽名預覽回歸測試通過，文字修正不重送圖片。
- PowerShell 設定工具語法檢查通過；重跑前後 Secret hash 相同。
- 檢查待提交檔案未包含現有 Secret 值；機密、本地資產與驗證暫存已排除 Git／Docker context。

## 尚未完成的營運驗收

- Document 尚缺有效 LINE Channel 憑證。兩個服務的公開 HTTPS／Tunnel 與 LINE Webhook 尚未驗收。
- Commercial 管理 API 顯示尚無公司資產版本；需準備 Logo、印章、五種範本、範本定義、品項 CSV 與聯絡資料，完成驗證、核准、啟用。
- 尚未執行真實 LINE 對話 → 確認 → 正式 XLSX／PDF → 下載；本地健康檢查不能取代此驗收。
- 尚未以真實模型重複抽樣量測成功率或每成功報價 token；不宣稱模型穩定性提升幅度。

兩個本地服務保持運行，後續補上外部設定與公司資產後再完成上述驗收。停止服務用 `docker compose stop`，不刪除資料卷。
