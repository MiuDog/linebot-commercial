# Capability Map：Windows 商用 App 發佈

## 能力模組

| Module id | 責任 | Depends on |
|---|---|---|
| `configuration-core` | 管理首次設定、設定編輯、輸入驗證及 Windows 使用者層級的機密資料 | — |
| `desktop-host` | 提供單一執行個體、狀態視窗、系統匣、背景執行及 Log 檢視 | `configuration-core` |
| `ngrok-connector` | 控制使用者自行安裝的 ngrok agent，取得公開網址並回報狀態 | `configuration-core` |
| `windows-distribution` | 建立自包含 Java App、單一 Setup.exe，以及安裝、編輯、升級與移除流程 | `configuration-core`, `desktop-host`, `ngrok-connector` |
| `release-pipeline` | 執行 CI 品質檢查、Windows 建置、簽章與 GitHub Release 發佈 | `windows-distribution` |

## 建置順序

`configuration-core` → (`desktop-host`, `ngrok-connector`) → `windows-distribution` → `release-pipeline`

`desktop-host` 與 `ngrok-connector` 可在設定契約穩定後平行實作。模組間不得形成循環依賴。

## 共用邊界

- 安裝後的設定與執行資料屬於目前 Windows 使用者，不寫入 Windows 全域環境變數。
- GitHub Release 對外只提供一份 `AssetsManagerLinebot-Setup-<version>.exe`。
- Setup.exe 內含 App 與 Java Runtime；ngrok agent 不隨產品再散布。
- 正式公開版本必須完成可信任的 Windows 程式碼簽章。
- 所有模組沿用專案既有中文註解、Logger、Tab 縮排及個人化程式碼風格。

