# Spec：desktop-host

狀態：Superseded。桌面執行模式已由 `SPEC-headless-runtime.md` 與 `SPEC-desktop-retirement.md` 取代。

## Objective

把既有 Spring Boot LINE Bot 包在 Windows 桌面殼層中。使用者啟動 App 後能看到「已啟動」狀態；關閉視窗後 App 留在系統匣持續執行；再次開啟相同 App 時只顯示既有視窗，不建立第二個後端或 SQLite 寫入程序。

主視窗提供：

- 啟動中、執行中、設定錯誤、ngrok 錯誤及停止中等狀態。
- 本機管理頁、健康檢查、Webhook 與 ngrok 公開網址的複製按鈕。
- 既有 JSON Log 的即時尾端檢視、暫停捲動、等級篩選、搜尋及開啟 Log 資料夾。
- 設定、隱藏、重新啟動服務與結束程式操作。
- 系統匣選單提供顯示、設定、重新啟動及結束。

生命週期：

1. Bootstrap 取得單一執行個體鎖。
2. 第二個執行個體透過 loopback IPC 要求第一個執行個體顯示視窗，然後正常結束。
3. 第一個執行個體完成設定與 ngrok 前置處理後建立 Spring ApplicationContext。
4. 收到 `ApplicationReadyEvent` 後才顯示「已啟動」。
5. 關閉視窗只隱藏；明確選擇結束才停止 Spring、ngrok、IPC 及系統匣。

## Tech Stack

- Java 25 Swing、AWT SystemTray
- Spring Boot 4.1.0 ApplicationContext lifecycle
- Java NIO FileLock
- 僅綁定 `127.0.0.1` 的隨機 Port IPC，加上每次啟動產生的 nonce
- 專案既有 Logback JSON rolling file

## Commands

```powershell
# 執行桌面生命週期測試
.\mvnw.cmd -Dtest="*Desktop*Test,*SingleInstance*Test,*LogViewer*Test" test

# 執行完整驗證
.\mvnw.cmd clean verify

# 從開發環境啟動桌面模式
java -jar target\app.jar --app.desktop.enabled=true
```

## Project Structure

```text
src/main/java/dev/miudog/linebotcommercial/desktop/
  DesktopApplication.java
  DesktopLifecycleCoordinator.java
  DesktopStatus.java
  SingleInstanceCoordinator.java
  tray/
  ui/
  log/
src/test/java/dev/miudog/linebotcommercial/desktop/
  對應單元與整合測試
```

桌面 UI 不得直接呼叫 Repository 或業務 Service；它只操作 lifecycle、設定與狀態介面。

## Code Style

沿用 `SPEC-configuration-core.md` 的 Java 範例與 `personal-code-style`。每個事件處理方法都有中文說明；每筆狀態 Log 上方都有中文目的註解；Swing 外部 API 呼叫前說明 EDT 或系統匣操作目的。

## Testing Strategy

- Headless 單元測試驗證狀態機、關閉行為、重啟順序及 Log buffer 上限。
- IPC 整合測試啟動兩個 coordinator，證明第二次開啟只送出 `SHOW_WINDOW`。
- 驗證錯誤 nonce、非 loopback 來源及格式錯誤訊息會被拒絕且不洩漏細節。
- Windows UI smoke test 驗證系統匣可用時的顯示／隱藏／結束流程。
- 系統匣不可用時維持主視窗，不允許關閉後形成無法操作的背景程序。
- 使用暫存 Log 測試尾端讀取、rotation、搜尋與敏感資料遮罩。

## Boundaries

- Always：Swing 元件只在 EDT 更新；狀態轉換有 Log；IPC 只綁 loopback 並驗證 nonce；停止順序可重入。
- Ask first：改成 Windows Service；加入開機自動啟動；增加遙測或外部錯誤回報服務；變更管理頁存取邊界。
- Never：使用 `System.out` 或 `System.err`；因關閉視窗直接終止；在 UI 顯示未清理的機密；允許第二個 Spring context 同時執行。

## Success Criteria

- 啟動成功後主視窗與系統匣都顯示「執行中」，健康檢查為成功。
- 點擊視窗關閉按鈕後程序與 webhook 仍持續運作。
- 再次執行 App 會在 3 秒內顯示既有視窗，程序清單中仍只有一個 App 後端。
- Log 視窗能持續顯示最新記錄，rotation 後不需重啟 UI。
- 明確結束後，Spring context、ngrok child process、IPC socket 與 file lock 全部釋放。
- 系統匣不可用時，App 不會進入無法再次顯示的隱藏狀態。

## Open Questions

- 正式 icon 與視窗品牌素材尚待提供；開發階段使用專案自有的中性 placeholder。

