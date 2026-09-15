# SPEC：Headless Runtime

狀態：Approved，2026-09-02 核准。

## User Story

身為部署維護者，我希望商用機器人只是一個標準 Spring Boot 服務，使同一個建置產物能在雲端與本地 Docker 啟動，而不依賴 Windows 桌面環境。

## Acceptance Criteria

1. 應用程式只有一個 server 啟動路徑，直接以 `SpringApplication.run` 建立 Context。
2. 正式 runtime 不載入 AWT、Swing、SystemTray、DPAPI、JNA、IPC、ngrok 或 cloudflared 類別。
3. `java -jar app.jar` 與容器啟動使用相同的 Spring 設定契約。
4. 必要設定缺漏時啟動失敗，錯誤只列設定名稱，不輸出 Secret 值。
5. 收到 SIGTERM 後停止接收新工作，等待既有請求與報價工作在設定期限內結束，再關閉資料來源。
6. `/actuator/health/liveness` 與 `/actuator/health/readiness` 可分別反映程序存活及必要外部依賴可用性。
7. 自動測試不得建立桌面視窗或依賴 Windows-only API。

## Out of Scope

- 桌面設定精靈、系統匣、Log 視窗與單一執行個體 UI。
- 由應用程式自行建立公開 Tunnel。
- 在本能力內更改 LINE 對話與報價業務規則。

## Data Model Changes

無直接資料表變更；runtime mode、桌面設定與 DPAPI 檔案不再是有效執行狀態。

## API Changes

- 新增標準 liveness／readiness 健康端點。
- 移除桌面 IPC 與本機控制端點。

## Edge Cases

- readiness 在資料庫或物件儲存不可用時必須失敗，但 liveness 不得因短暫外部故障而重啟程序。
- 背景工作關閉逾時後必須留下可重試狀態，不得將執行中工作誤標成功。
- 本地 Docker 與雲端傳入相同設定時，功能結果必須一致。

## Dependencies

無。
