# Changelog

## [0.3.0] - 2026-08-26

### Added

- 新增獨立背景 service launcher、Windows 登入自動啟動與桌面控制器探測。
- 新增只綁定 loopback 的 nonce 認證控制通道，可查詢狀態、重新載入設定與安全停止。
- 新增跨程序 service 鎖，避免登入自動啟動與手動開啟 App 同時建立兩組 Tunnel。
- 新增 LINE 連線逾時、報價稅率與有效天數等可注入且受驗證的營運設定。

### Changed

- 桌面 App 改為純控制器，不再持有 Spring、ngrok 或 cloudflared；重新開啟只顯示狀態與 Log。
- Log 視窗將 JSON 事件轉為中文摘要，完整方法追蹤預設關閉以降低 CPU 使用。
- Cloudflare 預設採 HTTP/2 並保留安全診斷，改善公司 VPN 環境下的可用性。

### Security

- 控制 nonce 以 DPAPI 保護後原子發布，不寫入 Log，命令與回應都限制為固定列舉。
- Cloudflare Token 只透過 child environment 傳遞，不出現在命令列或診斷內容。
