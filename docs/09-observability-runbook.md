# 本機日誌與 AI 成本稽核操作手冊

## 日誌位置與保留

程式預設將 JSON Lines 日誌寫入專案根目錄的 `log/application.json`。輪替檔位於
`log/archive/`，預設單檔 20 MB、保留 30 天、全部檔案上限 2 GB。`log/` 已列入
`.gitignore`，不得提交至版本庫。

可用以下環境變數調整：

| 變數 | 預設值 | 用途 |
| --- | --- | --- |
| `LOG_PATH` | `./log` | 日誌根目錄 |
| `LOG_LEVEL_ROOT` | `INFO` | 根日誌層級 |
| `LOG_MAX_FILE_SIZE` | `20MB` | 單一輪替檔上限 |
| `LOG_MAX_HISTORY` | `30` | 保留天數 |
| `LOG_TOTAL_SIZE_CAP` | `2GB` | 歷史日誌總容量上限 |
| `RESOURCE_LOG_INTERVAL_MS` | `60000` | 資源快照週期（毫秒） |

## 日常查詢

每筆 HTTP 互動都會回傳 `X-Request-ID`，並以相同 `requestId` 串起 webhook、AI、
資料庫、Excel／PDF 與 LINE 呼叫。調查單一案件時，先取得 LINE webhook 對應的
`requestId`，再於 `application.json` 篩選同一值。

主要事件：

| event | 用途 |
| --- | --- |
| `http_request_completed` | HTTP 狀態、路由與延遲 |
| `network_request_started` | 外部依賴呼叫次數（Rate） |
| `network_request_completed` | 外部依賴狀態類別與延遲（Duration） |
| `network_request_failed` | 外部依賴例外（Error） |
| `resource_snapshot` | JVM 記憶體、執行緒、日誌磁碟容量 |
| `ai_attempt_audited` | 每次報價／圖片／語音 AI 嘗試的模型、結果、token、費率與估算成本 |

`ai_attempt_audited` 的 `status` 可為 `SUCCESS`、`HTTP_ERROR`、`NETWORK_ERROR`、
`TIMEOUT` 或 `NOT_CONFIGURED`。`usageStatus` 可為 `AVAILABLE`、`PARTIAL` 或
`UNAVAILABLE`；供應商沒有回傳的 `inputTokens`、`cachedInputTokens`、
`outputTokens` 會明確保留為 `null`，不以零代替。報價 AI、語音轉錄與語音任務
Responses API 都沿用同一事件，並與相鄰的 `network_request_*` 使用相同 `requestId`。

日誌不應包含 API Key、LINE token、安全下載 token、完整個資、訊息全文、prompt、
AI 回應全文或圖片內容。安全下載路徑中的 token 會顯示成 `[REDACTED]`。若發現敏感
資料，應立即停止散布該檔案、輪替受影響憑證，再修正允許清單。

## AI 成本公式

在 `.env` 依 `AI_MODEL` 的實際官方價格填寫：

```dotenv
AI_PRICE_CURRENCY=USD
AI_INPUT_RATE_PER_MILLION=
AI_CACHED_INPUT_RATE_PER_MILLION=
AI_OUTPUT_RATE_PER_MILLION=
```

本機使用 `BigDecimal` 計算：

```text
inputTokens / 1,000,000 × inputRate
+ cachedInputTokens / 1,000,000 × cachedInputRate
+ outputTokens / 1,000,000 × outputRate
```

三種費率任一未設定時，系統仍記錄模型與供應商實際提供的 token，但 `priceStatus` 為
`UNCONFIGURED` 且不猜測 `totalCost`。即使費率完整，只要任一種 token 未知，
`totalCost` 仍為 `null`。費率會寫入每次事件作為快照；日後修改 `.env` 不會改變既有
日誌中的費率資料。

## 資源異常初查

- `heapUsedBytes` 長期接近 `heapCommittedBytes`：比對同一時段請求量與圖片處理。
- `liveThreadCount` 持續增加且不回落：檢查外部 HTTP timeout 或背景工作。
- `diskUsableBytes` 快速下降：檢查報價、圖片資產與日誌保留上限。
- 外部依賴錯誤增加：依 `dependency`、`operation`、`statusClass` 與 `requestId` 追查。

日誌只提供診斷證據，不作為保存客戶資料或訊息內容的替代資料庫。
