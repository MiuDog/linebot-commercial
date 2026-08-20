# Spec：ngrok-connector

## Objective

提供可選的 ngrok 整合，讓本機 LINE Bot 能取得公開 HTTPS URL，同時不在 Setup.exe 內再散布 ngrok agent。使用者勾選 ngrok 後，App 驗證本機 agent 與 Authtoken、建立 tunnel、取得公開 URL，並在 App 結束時只停止自己啟動的 child process。

啟動順序：

1. 從 `configuration-core` 取得啟用狀態、agent 路徑、Authtoken 與本機 Port。
2. 執行 `ngrok version` 驗證可執行檔，不接受資料夾或任意命令列片段。
3. 以 ProcessBuilder 的獨立參數啟動 agent，Authtoken 只透過 child process environment 傳遞。
4. ngrok 可先監聽尚未啟動的本機 Port；connector 從 `127.0.0.1` agent API 取得 HTTPS URL。
5. 把公開 URL 提供給 desktop bootstrap，作為 Spring Boot 啟動前的 `PUBLIC_BASE_URL`。
6. Spring ready 後，畫面顯示公開網址與 `${PUBLIC_BASE_URL}/callback`。

若 ngrok 失敗，使用者可選擇重試、開啟設定或僅以本機模式啟動；不得靜默改用過期網址。

## Tech Stack

- Java 25 ProcessBuilder、ProcessHandle、HttpClient
- 使用者自行安裝的 ngrok agent
- ngrok local agent API，僅存取 `127.0.0.1`
- 專案既有 Logger 與 SensitiveDataSanitizer

## Commands

```powershell
# 執行 ngrok connector 測試
.\mvnw.cmd -Dtest="*Ngrok*Test" test

# 驗證使用者指定的 agent
ngrok version

# 執行完整驗證
.\mvnw.cmd clean verify
```

## Project Structure

```text
src/main/java/dev/miudog/linebotcommercial/desktop/ngrok/
  NgrokConnector.java
  NgrokProcess.java
  NgrokStatus.java
  NgrokTunnel.java
src/test/java/dev/miudog/linebotcommercial/desktop/ngrok/
  對應單元與整合測試
```

對外只暴露 `start`、`status` 與 `stop` 契約；其他模組不得直接建立 ngrok process 或讀取 Authtoken。

## Code Style

沿用 `SPEC-configuration-core.md` 與 `personal-code-style`。所有 ProcessBuilder、HttpClient、檔案路徑及 JSON 解析等外部 API 呼叫前，需以中文註解說明流程與目的。

## Testing Strategy

- 使用假 agent executable 測試參數陣列、environment、啟動 timeout 與結束流程。
- 使用本機 HTTP stub 測試 tunnel API 正常、空清單、HTTP 錯誤、格式錯誤及 timeout。
- 驗證 agent 路徑必須是存在的 `.exe`，不可包含額外參數或指向工作目錄外的相對 traversal。
- 驗證 Log、錯誤訊息、process command summary 不含 Authtoken。
- 驗證只終止目前 App 建立的 ProcessHandle，不停止使用者其他 ngrok session。
- 網路測試不在一般 CI 連線真實 ngrok；正式 smoke test 由人工提供專用測試 Token。

## Boundaries

- Always：使用使用者自己的 agent 與帳號；使用參數陣列防止命令注入；設定啟動與 HTTP timeout；遮罩 Token。
- Ask first：改用 ngrok Java SDK；在安裝器下載或內嵌 agent；使用供應商共用 ngrok 帳號；增加其他 tunnel 供應商。
- Never：把 Authtoken 放進命令列、Log 或 GitHub Actions；下載未驗證的 executable；停止非本 App 建立的 ngrok process。

## Success Criteria

- 未勾選 ngrok 時不搜尋、不啟動也不連線 ngrok。
- 勾選且設定有效時，App 在 Spring 啟動前取得 HTTPS URL 並映射為 `PUBLIC_BASE_URL`。
- 視窗顯示可複製的公開 URL 與 callback URL，但不顯示 Authtoken。
- ngrok 啟動失敗時提供中文原因與重試／設定／本機模式選項。
- App 結束或重新設定時，自己建立的 ngrok process 在 timeout 內正常停止，必要時才強制終止該 process。

## Open Questions

- ngrok agent 最低支援版本會在實作時依官方 CLI 與 local API 契約鎖定並寫入文件。

