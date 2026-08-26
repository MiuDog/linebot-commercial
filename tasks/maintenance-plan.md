# Implementation Plan：商用機維護強化

## Architecture Decisions

- 以 `AppConfigurationField` 作為桌面設定的唯一產品契約，其他設定介面由測試核對。
- Cloudflare 維持 child process 架構，加入受限 protocol、可清理診斷與較可靠的啟動觀察，不把 Token 放入參數。
- 保留 AOP 全方法追蹤能力但改為明確啟用、DEBUG 輸出；業務事件負責正式環境流程追蹤。
- 桌面 Log 在 buffer 邊界將 JSON 轉為人類可讀摘要，保留固定容量與搜尋。
- 將網路逾時與報價商業規則設定化；協定上限與安全規則維持程式內固定值。

## Dependency Order

產品設定契約 → Tunnel process 契約 → Log 摘要 → 追蹤成本 → LINE 韌性 → 報價規則 → 打包驗證。

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| VPN 仍攔截 TCP 7844 或 DNS | Log 顯示 protocol 與 cloudflared 安全診斷，文件列出必要出口 |
| Cloudflare 診斷洩漏 Token | allowlist 診斷、共用 sanitizer、Token 片段測試 |
| 關閉預設方法追蹤降低可見性 | 保留重大事件 INFO、HTTP／外部依賴事件與可切換完整追蹤 |
| 設定 migration 遇到舊欄位 | repository 忽略未知欄位並以 schema 測試保護 |

## Checkpoints

- 設定契約：focused tests 與 `.env.example`／Spring property 搜尋通過。
- 運行品質：Tunnel、Log、追蹤 focused tests 通過，效能重新量測。
- Release：`clean verify`、App Image、Setup 與安裝生命週期通過。
