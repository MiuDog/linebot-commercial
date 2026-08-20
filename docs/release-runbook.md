# Windows 商用發佈 Runbook

## 兩種發佈模式

Tag workflow 依 `WINDOWS_SIGNING_CERTIFICATE_BASE64` 是否存在自動選擇模式：

| 模式 | 觸發條件 | 行為 |
| --- | --- | --- |
| 未簽章個人發佈 | 未設定簽章 secret | 略過商用欄位與 Authenticode 閘門；Release 標記 pre-release，Notes 附 SmartScreen 說明與 SHA-256 |
| 商用簽章發佈 | 已設定簽章 secret | 套用下方所有必要條件；任何一項未通過即中止，不建立 Release |

未簽章模式仍會執行完整測試、產生 SBOM 與第三方 notices，並驗證版本一致性；放寬的只有法律／品牌欄位與簽章要求。

## 商用發佈前必要條件

1. `packaging/windows/release.properties` 不得含 `REPLACE_BEFORE_RELEASE` 或 `PRE_RELEASE`。
2. `packaging/windows/license.rtf` 必須替換為核准 EULA，且不得含 `Pre-release` 或 `internal verification only`。
3. GitHub Environment `commercial-release` 必須設定必要 reviewer。
4. Environment secrets 必須包含 `WINDOWS_SIGNING_CERTIFICATE_BASE64` 與 `WINDOWS_SIGNING_CERTIFICATE_PASSWORD`。
5. 憑證 Subject、Publisher 與公司法律名稱必須一致，且憑證在發佈日有效。
6. 乾淨 Windows 10／11 x64 VM、LINE 測試 Channel、AI Key、ngrok Token、Excel 與印表機驗收必須完成。

## Dry run

1. 在 GitHub Actions 手動執行 `Windows Release`。
2. Dry run 只建立未簽章 Actions artifact，不建立 GitHub Release，也不讀取簽章 secrets。
3. 下載 artifact 後執行 `scripts/test-windows-installer.ps1 -InstallerPath <setup> -ExecuteLifecycle -TestPurge`，確認安裝、修復、預設保留與 purge。不帶 `-ExecuteLifecycle` 只會做靜態檢查，證據檔的 `lifecycleExecuted` 會是 `false` 而 exit code 仍為 `0`，不可據此宣稱已驗收。
4. `-TestPurge` 在產品資料目錄已存在時會拒絕執行，避免刪除使用者資料；請改用乾淨 VM 或先確認該目錄無實際設定。
5. 保存 workflow URL、commit、Setup SHA-256 與 installer evidence。

## 正式發佈

1. 將 Maven 版本設定為三段 SemVer，例如 `0.1.0`。
2. 建立 annotated tag `v0.1.0` 並推送。
3. Tag workflow 會重跑測試、產生 SBOM、封裝 Setup、匯入暫時 PFX、簽章並驗證 Authenticode。
4. 只有所有 gate 通過後才以 GitHub CLI 建立 Release；Release asset 僅有一份 Setup.exe，SHA-256 寫入 Notes。
5. 在另一台乾淨 VM 從 GitHub Release 下載並再次驗證簽章與 SHA-256。

## 憑證輪替

1. 在憑證到期前完成新憑證的組織驗證與測試簽章。
2. 將新 PFX 轉為 Base64，更新 Environment secrets；不得提交 PFX、Base64 或密碼。
3. 執行 dry run 與受保護的測試 Tag；確認 Subject、時間戳與信任鏈。
4. 撤銷舊憑證 secret，保留憑證指紋及有效期稽核紀錄。

## 撤回與回復

1. 若發現安全或資料風險，先將 GitHub Release 標記為 draft 或刪除公開資產，保留 tag 與 workflow 證據供調查。
2. 公告受影響版本、停止使用方式與資料保留行為。
3. 預設解除安裝保留 `%LOCALAPPDATA%\AssetsManagerLinebot`；不得要求使用者手動刪除不相關路徑。
4. 重新發佈修正版，或解除安裝新版後安裝上一個已簽章 Setup；先備份產品資料目錄。
5. 重新執行 webhook、Excel/PDF、ngrok、Log 與升級矩陣。

## ngrok 授權邊界

Setup 不包含、不下載也不再散布 ngrok。使用者自行取得 ngrok agent、帳號與符合其使用情境的授權；App 僅啟動使用者選擇的 executable，並只停止自己建立的 child process。
