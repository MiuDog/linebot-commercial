# 本地 Secret 檔案

Windows 客戶不需要手動建立下列檔案。請雙擊根目錄的 `linebot.cmd`，選擇「首次設定／修改設定」；控制台會自動產生內部密碼與報價簽章，只在畫面隱藏輸入 LINE、AI 與 Cloudflare 憑證，並於啟動前逐項驗證。

以下清單只供自動化部署與維運人員使用。檔案可保留一般編輯器加入的單一尾端換行，但不得包含空白行、前後空白或引號：

- `database-password`
- `object-storage-access-key`
- `object-storage-secret-key`
- `line-channel-token`
- `line-channel-secret`
- `admin-token`（至少 32 個字元）
- `ai-api-key`（不啟用 AI 報價時建立空檔）
- `quotation-postback-secret`（至少 32 bytes）
- `quotation-image-link-secret`（至少 32 bytes）
- `cloudflared-token`（只有啟用 `tunnel` profile 才需要）

正式雲端環境請改用雲端 Secret Manager 或 Kubernetes Secret 掛載，不要複製本機檔案。
