# 效能維護紀錄

## 2026-08-25 方法追蹤常駐

### 基準與證據

- 同機 Document 閒置程序量測 10 秒，總 CPU 時間增加 `15.6 ms`，約單核心 `0.16%`；此程序不是當下 CPU 熱點。
- 已安裝 Commercial 設定為 `METHOD_TRACING_ENABLED=true`、`LOG_LEVEL_ROOT=INFO`；Document 則為 `false`。Commercial 因隱藏設定保留舊值，會讓所有 Spring 公開方法持續經過 Aspect，但 INFO 模式不會產生一般方法追蹤 Log。

### 保留的修正

- 設定 schema 由 1 升為 2；舊設定升級時把永久方法追蹤重設為 `false`。
- FLOW_TRACE 未開 DEBUG 時，成功路徑只呼叫原方法，不解析簽章、不建立 UUID、不操作 MDC、不計時。
- 自動測試驗證 INFO 快速路徑不呼叫 `getSignature()`，DEBUG 整合測試仍產生完整進入與完成事件。

### 未採用的猜測

- 沒有調高 Log 輪詢間隔：現場逐執行緒量測未顯示 500 ms 增量讀檔為 CPU 熱點，修改它只會降低 Log 即時性。
- 沒有限制 JVM 記憶體：使用者回報的是 CPU，直接壓低 heap 可能增加 GC 與 CPU，缺乏證據時不採用。
