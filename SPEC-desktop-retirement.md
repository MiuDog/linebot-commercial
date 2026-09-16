# SPEC：Desktop Retirement

狀態：Approved，2026-09-02 核准。

## User Story

身為維護者，我希望移除已停止採用的 Windows 桌面交付鏈，避免同時維護兩套啟動、設定、Tunnel 與 Release 行為。

## Acceptance Criteria

1. 移除 `desktop` production／test packages、runtime mode 與所有桌面資源。
2. 移除 JNA／DPAPI 相依、desktop 專用 JVM 參數及 Windows-only 測試。
3. 移除 jpackage、NSIS、Setup.exe、簽章及 Windows Release scripts／workflow／packaging。
4. 移除 App 內建 ngrok／cloudflared lifecycle；Tunnel 只可作為外部基礎設施。
5. 舊能力地圖與規格標記 `Superseded`，保留決策歷史但從現行文件索引移出。
6. CI 只驗證 server、容器、migration、資產契約及部署 manifest。
7. 全 repository 搜尋不得存在可執行的 desktop mode 或 Windows installer 入口。

## Out of Scope

- 刪除既有 Git 歷史或已發布的 GitHub Release。
- 自動移除使用者電腦上已安裝的舊桌面 App。

## Data Model Changes

無；DPAPI 設定檔不再讀取，遷移文件負責把必要值轉入新的 Secret 機制。

## API Changes

移除桌面 IPC／control API；LINE 與管理業務 API 不因本能力改名。

## Edge Cases

- 舊 CLI 參數出現時應明確報告已移除，不得靜默切換錯誤模式。
- 移除桌面碼後 Maven test／package 與 Docker build 必須在非 Windows 環境通過。

## Dependencies

`headless-runtime`、`local-docker`、`cloud-deployment`。
