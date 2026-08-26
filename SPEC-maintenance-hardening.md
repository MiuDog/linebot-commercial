# Spec：商用機報價系統維護強化

## Objective

將 `linebot-commercial` 收斂為供商用機執行的報價系統。保留報價流程必要的圖片附件、LINE、AI、SQLite 與 Windows 桌面宿主，移除文書機專屬的資產同步、語音及 MCP 設定槽位；改善公司 VPN 環境下的 Cloudflare Tunnel 可診斷性與連線韌性，並降低預設追蹤負擔。

## Commands

- 測試：`.\mvnw.cmd --batch-mode --no-transfer-progress test`
- 完整驗證：`.\mvnw.cmd --batch-mode --no-transfer-progress clean verify`
- App Image：`powershell.exe -NoProfile -File scripts\package-windows-app.ps1 -Version 0.2.6`
- Setup：`powershell.exe -NoProfile -File scripts\build-windows-installer.ps1 -Version 0.2.6`

## Product Boundary

- 必要能力：LINE Webhook、報價草稿、AI 報價解析、圖片附件、Excel／PDF、SQLite、Windows 桌面宿主。
- 不屬於本產品：資產同步排程、文書圖片查詢命令、語音命令、Voice MCP。
- 設定精靈、`.env.example`、Spring properties、Docker Compose 與文件必須遵守同一份環境變數契約。

## Network Requirements

- Cloudflare Tunnel Token 只能透過 child environment 傳遞，不可出現在命令列或 Log。
- 公司 VPN 或防火牆可能阻擋 UDP／QUIC；預設使用 HTTP/2，並提供 `auto`、`http2`、`quic` 三種受驗證選項。
- cloudflared 的診斷輸出必須被有限容量保留、清除敏感資料並寫入 App Log；啟動必須在穩定觀察期內偵測提前退出。
- Tunnel 公開網址與本機服務埠必須分離；本機服務只需由 cloudflared 連到 loopback。

## Logging Requirements

- 正式環境預設關閉逐方法追蹤，但保留可由設定精靈啟用的完整流程測試能力。
- 逐方法追蹤使用 DEBUG，錯誤使用 ERROR；重大業務事件維持 INFO。
- 桌面 Log 顯示時間、等級、元件、事件與重點欄位，不直接顯示整行 JSON。
- Token、Secret、Header、完整請求本文與使用者訊息不得寫入 Log。

## Performance Budget

- 同機 Maven test 暖機基準為 27.469 秒與 27.226 秒，平均 27.348 秒。
- 預設設定的兩次平均測試時間必須較基準改善，且不得以跳過測試換取改善。
- 預設不得建立逐方法 AOP 攔截器；Log UI 只解析新增行並限制記憶體筆數。

## Testing Strategy

- 單元測試：產品設定欄位集合、Tunnel 命令與診斷清理、JSON Log 摘要解析。
- 整合測試：Spring 預設不建立 MethodTraceLogger，明確啟用後仍可追蹤。
- 完整驗證：所有 Maven 測試、SBOM、App Image 自包含 Runtime、Setup 安裝生命週期。

## Boundaries

- Always：先寫失敗測試；只記錄允許欄位；外部連線具 timeout；保留 DPAPI 機密儲存。
- Ask first：變更報價資料庫 schema、LINE 對外契約、加入新外部服務。
- Never：提交 `.env`、把 Token 放入程序參數、為效能刪除必要驗證或測試。

## Success Criteria

- commercial 的任何受支援設定介面都不出現 Voice 或 Assets Sync 欄位。
- VPN 受限環境可選 HTTP/2，Tunnel 失敗能從安全且可讀的 Log 找到原因。
- Log 視窗不再顯示原始 JSON，方法追蹤預設不造成 INFO 洪水。
- 前後效能數據、完整測試與 Windows 打包證據均被記錄。

## 可注入功能維護

- LINE API 的連線與單次請求逾時必須分開設定；VPN 半斷線時不得無限等待。
- 報價稅率與有效天數由同一份受驗證的商業規則設定提供，正式報價必須保存當次規則快照。
- 新設定缺省時維持既有行為：LINE 連線 10 秒、請求 30 秒、稅率 5%、有效 15 天。
- LINE 官方每次最多 5 則訊息、HMAC 驗證與資料庫狀態集合屬協定／安全邊界，不開放客戶修改。
