# LINE AI 自動化報價驗收證據矩陣

依據：[08 LINE AI 自動化報價 SRS](08-quotation-automation-srs.md)。本表區分「自動驗證」、
「實機／檔案驗證」及「待補人工證據」；功能已實作不等於沙盒或特定主機已實測。

## 狀態定義

| 狀態 | 意義 |
| --- | --- |
| 通過 | 已有可重跑測試或可定位的實機／檔案證據 |
| 部分通過 | 核心行為已自動驗證，但 SRS 另要求的人工或外部環境證據尚未齊全 |
| 待重驗 | 尚缺對應自動測試、實機結果，或已知阻擋尚未解除 |
| 受環境限制 | 程式已進入受控失敗狀態，當前主機缺少完成成功路徑所需環境 |

## AC-01 至 AC-22

| AC | 狀態 | 自動測試證據 | 實機／檔案證據與備註 |
| --- | --- | --- | --- |
| AC-01 | 通過 | `LineWebhookControllerTest`、`QuotationLineWorkflowServiceTest`、`SqliteQuotationDraftWorkflowPortTest` | 私訊限定、事件冪等及多輪 patch merge 均已重跑通過；既有欄位不會被局部補件清空。 |
| AC-02 | 通過 | `QuotationAiParsingServiceTest`、`QuotationRequestValidationServiceTest`、`SqliteQuotationDraftWorkflowPortTest` | 低信心缺漏、OCR 抬頭與既有欄位合併均有測試。 |
| AC-03 | 通過 | `QuotationConversationServiceTest`、`QuotationLineMessageBuilderTest`、`SqliteQuotationDraftWorkflowPortTest` | 基礎與品項缺漏一次列出，局部補件重驗及取消按鈕均有測試。 |
| AC-04 | 通過 | `QuotationCalculationServiceTest`、`QuotationWorkbookServiceTest` | 固定鎖價列保留；數量與複價空白列不進合計。 |
| AC-05 | 通過 | `QuotationRequestValidationServiceTest`、`QuotationCalculationServiceTest` | CNS／一般架第三筆臨時品項被拒絕，動態格式採不同上限。 |
| AC-06 | 通過 | `QuotationRequestValidationServiceTest`、`QuotationConfirmationServiceTest` | 動態品項只進草稿／正式快照，未回寫主檔。 |
| AC-07 | 通過 | `QuotationCalculationServiceTest`、`QuotationWorkbookServiceTest` | 船用客戶列只含彙總，工作簿不暴露內部公式。 |
| AC-08 | 通過 | `QuotationConversationServiceTest`、`QuotationLineMessageBuilderTest` | 船用／空白必問圖片，明確拒絕後才進預覽。 |
| AC-09 | 通過 | `QuotationAiParsingServiceTest`、`SqliteQuotationDraftWorkflowPortTest`、`QuotationAssetArchiveServiceTest`、`MediaControllerQuotationAssetTest` | 最高區別度候選可更換；全部原圖搬入正式資料夾且仍可安全公開預覽。 |
| AC-10 | 通過 | `QuotationWorkbookServiceTest` | 唯一選圖等比例放在品項後，滿頁時移到新工作表；範本既有媒體雜湊不變。 |
| AC-11 | 通過 | `QuotationConversationServiceTest`、`QuotationConfirmationServiceTest`、`QuotationConfirmationConcurrencyTest` | 必要抬頭、並行不重號、同草稿冪等及失敗時回滾狀態／事件／流水號均已測試。 |
| AC-12 | 通過 | `QuotationConfirmationServiceTest`、`QuotationWorkbookServiceTest` | 報價日、15 天期限、日期流水資料夾與正式檔名均由程式產生。 |
| AC-13 | 通過 | `QuotationContractTest`、`QuotationRequestValidationServiceTest`、`QuotationCalculationServiceTest` | AI 金額欄位被拒絕；程式以 `BigDecimal` 計算 DIRECT、5% 稅額與總額。 |
| AC-14 | 通過 | `QuotationWorkbookServiceTest` | 超量品項分頁，合計只在最後一頁且圖片滿頁時獨立成頁。 |
| AC-15 | 通過 | `QuotationAssetArchiveServiceTest`、`SqliteQuotationDraftWorkflowPortTest`、`QuotationGenerationCoordinatorTest`、`MediaControllerQuotationAssetTest` | 絕對／跳脫暫存路徑會先被拒絕；預檢失敗不建正式資料夾，成功後每張原圖只有一個正式實體。 |
| AC-16 | 部分通過 | `QuotationPdfServiceTest`、`QuotationGenerationJobWorkerTest`、`QuotationXlsxRetryControllerTest`、`QuotationManagementControllerTest` | SQLite job 可跨重啟恢復、失敗退避且 XLSX 沿用原單號重排；本機無印表機，因此真實 Excel PDF 只能驗證保留 XLSX 的受控 `PDF_FAILED`，尚無成功實機 PDF。 |
| AC-17 | 部分通過 | `QuotationDeliveryServiceTest`、`JdbcQuotationDeliveryRepositoryTest`、`QuotationDownloadControllerTest` | Flex 摘要取自正式快照，HTTPS 權杖只保存雜湊並有期限／撤銷；目前未提供真實 LINE／AI 憑證，尚無 LINE 沙盒交付畫面。 |
| AC-18 | 通過 | `QuotationConversationServiceTest`、`QuotationPostbackSignerTest`、`QuotationConfirmationServiceTest`、`QuotationReplyOutboxServiceTest`、`QuotationDurableReplyIntegrationTest`、`LineWebhookControllerTest` | HMAC、取消／確認冪等、確認失敗交易回滾，以及真 SQLite 的文字／postback 當機重送、不重跑 AI、同 payload 與慢 AI 期間可讀均已測試。 |
| AC-19 | 通過 | `QuotationWorkbookServiceTest`、`QuotationContractTest` | `outputs/excel-templates/previews/final-single/` 有五格式預覽；五份範本曾由 Microsoft Excel 開啟核對，原圖、蓋章、正定信箱與電話保留。 |
| AC-20 | 部分通過 | 完整 Maven `309/309` 測試、package、各 Controller MockMvc、`LocalAdminAccessFilterTest`、packaged app 瀏覽器檢查 | 完整測試、打包、管理頁 reload／console、桌面與窄螢幕版面均通過；尚缺使用真實 LINE／AI 憑證的沙盒流程及有印表機的 PDF 成功路徑。 |
| AC-21 | 通過 | `LogbackConfigurationTest`、`RequestCorrelationFilterTest`、`MethodTraceLoggerIntegrationTest`、`SensitiveDataSanitizerTest`、`OperationalStatusLoggerTest` | `docs/09-observability-runbook.md` 記錄輪替、correlation ID 與敏感資料處理。 |
| AC-22 | 通過 | `AiUsageCostCalculatorTest`、`AiUsageAuditServiceTest`、`AiExtractionServiceTest`、`OpenAiVoiceGatewayTest`、`ObservabilitySecurityConfigurationTest` | 報價、圖片提取、語音轉錄與語音任務的成功、HTTP 錯誤、逾時／網路錯誤、未設定皆留下同 correlation ID 的安全稽核；未知 token 為 `null`，缺費率為 `UNCONFIGURED`，不猜價。 |

## Packaged app 瀏覽器證據

- 打包後的應用程式在本機開啟管理頁回應 HTTP 200，頁面共有 5 個主要 sections。
- 桌面 viewport 為 1280 px、頁面 scroll width 為 1265 px；窄螢幕 viewport 為 390 px、scroll width
  為 375 px，兩者均無水平溢位。
- reload 後新增 console logs 為 0；報價預覽驗證通過，CNS「外部鷹架」數量 2 正確顯示，且
  workbook 產生功能為 enabled。
- 草稿與正式報價清單 API 均回應 HTTP 200 與 JSON；頁面所有按鈕都有可辨識名稱。
- skip link 已設定 `href="#main-content"`。目前瀏覽器 dispatch 工具無法可靠模擬完整實體鍵盤流程，
  因此只把連結設定列為證據，不宣稱完整按鍵操作已驗證。

## 外部環境與實機證據限制

目前 Windows 主機可啟動 Microsoft Excel，但沒有可用印表機。Excel 的 PDF 匯出因此無法完成
成功路徑；系統已實機進入受控 `PDF_FAILED`，保留同一份 XLSX、報價識別碼、日期流水號與重試
路徑。這是環境限制，不是以其他 PDF 引擎替代的理由；配置可用印表機後應以管理頁「重試 PDF」
補做成功實機證據。

此外，目前未提供真實 LINE／AI 憑證，所以不宣稱已完成 LINE 沙盒與真實 AI/OCR 呼叫；這不影響
無外部憑證的 309 項自動測試結果。

## 尚待保存的人工證據

- 真實 LINE 沙盒的一對一補件、取消、圖片預覽、確認與 PDF 下載畫面。
- 使用實體鍵盤完成 skip link 與全部管理操作的焦點順序驗證。
- 配置可用印表機後，五格式 PDF 的 Microsoft Excel 匯出與頁面渲染結果。

Task 17 的實作、文件同步、自動驗證與打包已完成。上述外部／人工項目未完成前，AC-16、AC-17
與 AC-20 維持「部分通過」，不得據此宣稱真實 LINE／AI、成功 PDF 匯出或完整實體鍵盤流程已驗證。

## 2026-08-11 增補驗證證據

| 範圍 | 狀態 | 自動化證據 |
| --- | --- | --- |
| 業務承辦 G6 全鏈路 | 通過 | 已定案為每張報價由使用者輸入；`QuotationContractTest`、`QuotationRequestValidationServiceTest`、`SqliteQuotationDraftWorkflowPortTest`、`QuotationConfirmationServiceTest`、`QuotationGenerationSnapshotRepositoryTest`、`QuotationWorkbookServiceTest` |
| 第一階段商業規則 | 通過 | 已核准 PDF 連結 7 天、金額 `HALF_UP` 至兩位小數、動態品項最多 200 筆；`QuotationCalculationServiceTest`、`QuotationRequestValidationServiceTest`、`QuotationContractTest` |
| 同 owner 唯一進行中草稿 | 通過 | `QuotationSchemaMigratorTest`（舊資料保留與雙連線並行）、`SqliteQuotationDraftWorkflowPortTest` |
| 圖片安全改選／移除／翻頁 | 通過 | `QuotationPostbackSignerTest`、`QuotationLineMessageBuilderTest`、`QuotationLineWorkflowServiceTest`、`QuotationConversationServiceTest`、`QuotationAssetArchiveServiceTest` |
| LINE 控制器至 SQLite 縱向流程 | 通過 | `LineQuotationVerticalIntegrationTest`（有效 webhook 簽章、真 workflow／port／receipt／reply outbox／SQLite，AI 為固定測試輸出） |

## 2026-08-20 Windows App 發佈證據

| 範圍 | 狀態 | 證據與限制 |
| --- | --- | --- |
| 桌面設定與 DPAPI | 通過 | `AppConfiguration*Test`、`DpapiSecretStoreTest`、`DesktopSpringPropertiesTest`；一般 properties 不含秘密原文。 |
| 單一執行個體與背景視窗 | 部分通過 | IPC、nonce、FileLock、EDT、系統匣 fallback 與 Log viewer 自動測試通過；實體鍵盤、系統匣恢復與真 webhook 背景持續仍待人工。 |
| ngrok | 部分通過 | process、local API、HTTPS URL 注入、timeout 與只停止 child process 測試通過；真實專用 Token callback 尚未執行。 |
| 自帶 Java app image | 部分通過 | `test-windows-app-image.ps1` 已移除 `JAVA_HOME`／`JDK_HOME` 並從 PATH 排除 Java，使用 app image Runtime 執行 `--shutdown`，exit code 0 且無殘留程序；尚待無系統 Java 的乾淨 VM 複驗。 |
| Setup 生命週期 | 通過（目前主機） | `installer-smoke.json` 證明 install、repair、預設保留資料及 `/PURGE=1` 清除成功；程式、staging、backup 與資料目錄無殘留。 |
| SBOM 與第三方聲明 | 通過 | CycloneDX 1.6 共 62 個 runtime 元件；Setup 實際安裝 `sbom.cdx.json` 與 `THIRD-PARTY-NOTICES.md`。 |
| CI/CD policy | 本機通過 | 兩份 workflow YAML 可解析，第三方 Actions 鎖定 40 碼 SHA，PR/main 只有 `contents: read`；實際 GitHub run 尚未建立。 |
| 商用 Release gate | 如預期阻擋 | 預發佈 EULA／Publisher／support URL 尚未核准，Setup 尚未簽章；`verify-release.ps1 -RequireCommercialMetadata` 已拒絕公開條件。 |

最新本機 Setup：`dist/AssetsManagerLinebot-Setup-0.1.0.exe`，SHA-256 為 `9BEE780994D53B7565D7AB05F6469C4802F22D69616D1AB9B6D4D05EA5959659`。Setup 每次重建後雜湊會改變，正式值必須由 Tag workflow 在簽章後寫入 Release Notes。
