# Spec：release-pipeline

## Objective

在 GitHub 建立可重現且權限最小化的 CI/CD。Pull Request 與 main push 自動驗證 Java 專案；符合 `v<major>.<minor>.<patch>` 的 Tag 自動在 Windows 建置、測試、封裝、簽章，並建立只含單一 Setup.exe 的 GitHub Release。

工作流程：

- `ci.yml`：Pull Request 與 main push 執行 Maven Wrapper 驗證，Windows 另執行桌面／DPAPI 相容測試。
- `release-windows.yml`：Tag 觸發，先重跑完整測試，再建立 app image 與 Setup.exe。
- Tag、Maven project version、Windows file version 與 Setup 顯示版本必須一致。
- 正式工作使用受保護的 GitHub Environment 取得簽章秘密；PR job 永遠無法取得 Release secrets。
- 簽章驗證成功後才建立 Release，SHA-256 寫入 Release Notes，不另上傳 checksum 檔。
- 失敗時不建立半完成 Release；可保留 Actions artifact 供授權人員診斷。

## Tech Stack

- GitHub Actions Windows 與 Ubuntu hosted runners
- Maven Wrapper、JDK 25
- JDK `jpackage`、NSIS
- Windows Authenticode 驗證
- GitHub Release 與 artifact attestations

## Commands

```powershell
# 本機執行與 CI 相同的完整驗證
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify

# 本機驗證 Release 組裝
powershell.exe -NoProfile -File scripts\build-windows-installer.ps1 -Version 0.1.0

# 驗證 Authenticode
Get-AuthenticodeSignature -LiteralPath dist\AssetsManagerLinebot-Setup-0.1.0.exe

# 建立 Release Tag，由 GitHub Actions 接手
git tag -a v0.1.0 -m "Release v0.1.0"
git push origin v0.1.0
```

## Project Structure

```text
.github/workflows/
  ci.yml
  release-windows.yml
.github/
  release.yml
docs/
  release-runbook.md
  third-party-notices.md
scripts/
  verify-release.ps1
```

Workflow 只呼叫版本庫內已測試的 build scripts，不在 YAML 中重複大量封裝邏輯。

## Code Style

- YAML 使用 2 個空格，因語法不允許 Tab。
- PowerShell 沿用 `personal-code-style` 的中文註解、region 與換行規則。
- GitHub Actions 第三方 Action 鎖定完整 commit SHA，旁邊標註對應版本。
- 任何外部下載步驟在上方以中文說明來源、用途與完整性驗證方式。

## Testing Strategy

- 使用 action lint／schema 驗證 workflow 結構。
- PR 測試證明 Release secrets 不可用，且 workflow 權限只有 `contents: read`。
- Release dry run 建立不公開的 Actions artifact，驗證版本、檔名、app image、Setup 安裝與簽章步驟。
- 正式 Tag job 使用 `contents: write`，其他 job 不授予寫入權限。
- 對 Setup 執行 Authenticode、SHA-256、病毒掃描及安裝 smoke test。
- Dependency／license 清單與第三方 notices 在 Release 前重新產生並驗證。
- 回復測試保留上一版 Setup，確認可卸載新版並重新安裝上一版。

## Boundaries

- Always：使用 Maven Wrapper；Action 鎖定 SHA；採最小權限；Release 前完整測試與簽章驗證；保留上一版回復路徑。
- Ask first：新增 GitHub secret 或 Environment；改變 Tag 格式；改變公開 Release 資產；新增外部發佈平台；提高 workflow 權限。
- Never：在 PR 執行簽章秘密；把 PFX、密碼或 Token 寫入版本庫或 Log；從未驗證來源下載建置工具；測試失敗仍發佈正式版。

## Success Criteria

- 每個 Pull Request 與 main push 都會自動執行 Java 完整驗證，失敗時阻止合併或 Release。
- 推送合法且版本一致的 Tag 後，Windows runner 產生一份已簽章 Setup.exe。
- GitHub Release 資產只有 `AssetsManagerLinebot-Setup-<version>.exe`，Release Notes 包含 SHA-256。
- 非 Tag workflow 無法取得簽章秘密，也沒有 GitHub Release 寫入權限。
- 發佈失敗不留下公開的半成品 Release。
- Release runbook 能讓維護者完成憑證輪替、重跑、撤回及回復上一版。

## Open Questions

- GitHub repository 的 branch protection、Environment reviewer 與簽章供應商帳號需由 repository owner 在正式上線前設定。
- 是否公開原始碼與採用何種產品授權不由本模組決定，但 EULA、第三方 notices 與 repository license 必須在正式商用 Release 前完成法律審閱。

