# 商用機維護任務

- [x] Task 1：移除 Voice 與 Assets Sync 的設定／文件殘留
  - Acceptance：所有支援設定介面只含商用報價必要欄位。
  - Verify：產品環境契約測試與 repository-wide 搜尋。
- [x] Task 2：強化 Cloudflare VPN 連線與診斷
  - Acceptance：protocol 受驗證、預設 http2、Token 不在命令列、提前退出有安全診斷。
  - Verify：CloudflareProcessTest、CloudflareConnectorTest。
- [x] Task 3：將 JSON Log 轉為中文可讀摘要
  - Acceptance：桌面顯示時間、等級、元件、事件與重點欄位；原始機密被清除。
  - Verify：DesktopLogBufferTest、LogTailServiceTest。
- [x] Task 4：降低預設追蹤成本
  - Acceptance：預設不建立 AOP trace bean；明確啟用仍涵蓋所有專案公開方法。
  - Verify：MethodTraceLogger tests、兩次 Maven test 前後量測。
- [x] Task 5：完成 Windows 發佈驗證
  - Acceptance：clean verify、App Image、Setup install/edit/uninstall 通過。
  - Verify：既有 packaging 與 installer scripts。
  - Result：clean verify、App Image、Setup 靜態驗證，以及 install／repair／uninstall／資料保留／reinstall 真實生命週期均已通過。

效能結果：同條件完整測試由平均 27.348 秒降至 23.324 秒，改善約 14.7%。

- [x] Task 6：設定 LINE 連線與請求逾時
  - Acceptance：桌面設定、環境變數與每次 LINE 請求使用一致且可驗證的逾時。
  - Verify：LineStorageServicePushTest、AppConfiguration tests、UnifiedEnvironmentConfigurationTest。
- [x] Task 7：設定報價稅率與有效天數
  - Acceptance：所有報價入口共用同一稅率，正式報價保存稅率快照並使用設定的有效天數。
  - Verify：QuotationCalculationServiceTest、QuotationConfirmationServiceTest、QuotationManagementControllerTest。
