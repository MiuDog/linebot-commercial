# Spec：windows-distribution

## Objective

把 Spring Boot App、桌面殼層與 Java Runtime 封裝成單一 Windows Setup.exe。使用者以同一份 Setup 完成首次安裝；再次執行時可編輯設定、修復／升級或移除 App。

安裝模式：

- 每位使用者安裝，不要求 UAC。
- 預設位置：`%LOCALAPPDATA%\Programs\AssetsManagerLinebot`。
- 建立目前使用者的開始功能表捷徑；桌面捷徑為可選項。
- 安裝完成後啟動 `AssetsManagerLinebot.exe --configure-first-run`。
- 安裝時檢查 Microsoft Excel COM 與可用印表機；缺少時顯示 PDF 功能不可用的警告，但不阻擋其他功能安裝。

維護模式：

- 偵測相同或既有版本後顯示「編輯設定」、「修復／升級」、「移除」。
- 編輯設定呼叫已安裝 App 的 `--configure`，由 App 負責 DPAPI 與驗證。
- 修復／升級先要求執行中的 App 正常停止，保留使用者設定與資料，再替換產品檔案。
- 移除預設只移除程式；另提供明確且預設不勾選的「刪除設定、資料、輸出與 Log」。

封裝流程：

1. Maven 產生 `target/app.jar`。
2. `jpackage --type app-image` 產生包含 Java Runtime 的 App image。
3. NSIS 將完整 App image 包進 `AssetsManagerLinebot-Setup-<version>.exe`。
4. 安裝器、App launcher 及 uninstaller 在正式版本完成可信任簽章。

## Tech Stack

- JDK 25 `jpackage`、`jlink`
- NSIS 3.x，版本與下載 SHA-256 鎖定
- PowerShell build orchestration
- Windows Authenticode / SignTool 或核准的雲端簽章服務

## Commands

```powershell
# 建立 Spring Boot JAR
.\mvnw.cmd clean verify package

# 建立自包含 App image
powershell.exe -NoProfile -File scripts\package-windows-app.ps1 -Version 0.1.0

# 建立單一 Setup.exe
powershell.exe -NoProfile -File scripts\build-windows-installer.ps1 -Version 0.1.0

# 執行安裝器驗收測試
# 不帶開關只做靜態檢查；-ExecuteLifecycle 才會實際安裝、解除安裝並驗證資料保留，
# -TestPurge 另外驗證明確清除。缺少開關時證據檔的生命週期欄位會是 false 而 exit code 仍為 0。
powershell.exe -NoProfile -File scripts\test-windows-installer.ps1 -InstallerPath dist\AssetsManagerLinebot-Setup-0.1.0.exe -ExecuteLifecycle -TestPurge
```

## Project Structure

```text
packaging/windows/
  installer.nsi
  license.rtf
  assets/
scripts/
  package-windows-app.ps1
  build-windows-installer.ps1
  test-windows-installer.ps1
dist/
  本機產物，加入 .gitignore
```

安裝器只負責產品檔案與維護導向；機密設定的保存永遠由 `configuration-core` 處理。

## Code Style

- Java 沿用 `personal-code-style`。
- PowerShell 與 NSIS 依同一風格使用對應語言的 region marker、中文流程註解及單一 Tab 換行縮排。
- 外部工具呼叫前說明用途，並檢查 exit code、輸入路徑與輸出路徑。
- 所有遞迴刪除只可對解析後且位於產品專屬根目錄內的明確路徑執行。

## Testing Strategy

- Package smoke test 直接執行 app image，驗證不依賴系統已安裝的 Java。
- 在乾淨 Windows Sandbox／VM 測試首次安裝、取消設定、有效設定、關閉至系統匣及第二次開啟。
- 在有／無 Microsoft Excel 與可用印表機的環境分別測試先決條件提示及 PDF 功能狀態。
- 再次執行同一 Setup，逐項測試編輯設定、修復及移除。
- 測試舊版升級至新版，設定、SQLite、輸出與 Log 均保留。
- 測試預設解除安裝保留資料；只有明確勾選清除資料才刪除產品專屬使用者目錄。
- 驗證 Release 檔案只有一份 Setup.exe，且安裝後不需額外 JDK/JRE。
- 驗證檔案與簽章、產品版本、Publisher、uninstall registry entry 一致。

## Boundaries

- Always：使用 per-user 安裝；先測試 app image 再封裝；鎖定 NSIS 來源與 checksum；升級與移除前正常停止 App。
- Ask first：改成 per-machine／需要管理員權限；加入開機自動啟動；內嵌或下載 ngrok；變更產品資料清除政策；改用其他安裝器。
- Never：把真實 `.env` 或 Token 包進 Setup；靜默刪除使用者資料；用未解析的變數或寬廣目錄作遞迴刪除；將未簽章檔標示為正式商用版。

## Success Criteria

- GitHub Release 只需下載一份 Setup.exe 即可安裝完整 App 與 Java Runtime。
- Windows「已安裝的應用程式」可找到產品並正常解除安裝。
- 同一 Setup.exe 在已安裝狀態提供編輯設定、修復／升級與移除三種操作。
- 首次安裝結束會進入 App 設定精靈，設定失敗不會留下正在執行但不可用的後端。
- 未安裝 Microsoft Excel 或沒有可用印表機時，安裝器與 App 狀態頁都會顯示中文警告，其他功能仍可啟動。
- 升級不遺失設定、資料庫、報價輸出或 Log。
- 預設解除安裝保留使用者資料；選擇完整清除後只刪除經驗證的產品專屬目錄。

## Open Questions

- 正式 ProductName、Publisher、網站、支援聯絡方式、icon、EULA 及著作權文字需在公開 Release 前提供。
- 正式簽章可採 PFX/SignTool 或可用地區支援的雲端簽章；實作先建立 provider-neutral 簽章介面。
