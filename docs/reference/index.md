# 專案類別索引（Class Reference Index）

> 內部維護文件。新增或刪除類別時，**同一個 PR 內**必須更新本頁與對應的分類頁，
> 規則見 [../03-versioning-release-sop.md](../03-versioning-release-sop.md)。
>
> 若要由 LINE、HTTP 或啟動事件一路追蹤到外部 API、資料庫與磁碟，請先看
> [事件起點與完整呼叫鏈](../06-event-call-chains.md)。該文件是目前執行流程的主索引。

適用版本：`@linebot-commercial@0.1.0`

---

## Navigator

依 Java 類別型態分類，點擊進入該型態的詳細頁。

| 型態 | 說明 | 數量 | 詳細頁 |
|---|---|---|---|
| Application | 應用程式進入點 | 1 | [01-application.md](01-application.md) |
| Configuration | Spring bean 定義與基礎設施組裝 | 1 | [02-configuration.md](02-configuration.md) |
| Controller | HTTP 端點，系統對外的入口 | 6 | [03-controller.md](03-controller.md) |
| Component | 請求追蹤、方法追蹤與啟動狀態 | 3 | [事件呼叫鏈](../06-event-call-chains.md) |
| Service | 業務邏輯 | 17 | [04-service.md](04-service.md) |
| Repository | 資料庫存取 | 3 | [05-repository.md](05-repository.md) |
| Record | 不可變資料載體 | 10 | [06-record.md](06-record.md) |
| Exception | 自訂例外 | 1 | [07-exception.md](07-exception.md) |
| Test | 測試類別 | 23 | [08-test.md](08-test.md) |

---

## 全類別快速索引（依字母）

| 類別 | 型態 | 一句話職責 |
|---|---|---|
| [`AiExtractionException`](07-exception.md#aiextractionexception) | Exception | 表達 AI 資料提取失敗與缺漏欄位 |
| [`AiExtractionService`](04-service.md#aiextractionservice) | Service | 把規格圖送給 AI 模型並整理回應 |
| `AdminPageController` | Controller | 將 `/admin/` 導向本機報價管理頁 |
| [`Asset`](06-record.md#asset) | Record | 一筆資產索引的不可變快照 |
| [`AssetRepository`](05-repository.md#assetrepository) | Repository | 資產索引的唯一資料庫出入口 |
| [`AssetService`](04-service.md#assetservice) | Service | 資產生命週期協調：收錄→歸檔→查詢 |
| [`LinebotCommercialApplication`](01-application.md#assetsmanagerlinebotapplication) | Application | 應用程式進入點 |
| [`CommandService`](04-service.md#commandservice) | Service | 群組文字指令解析與回覆組裝 |
| [`ExtractedSpec`](06-record.md#extractedspec) | Record | AI 讀出的結構化規格欄位 |
| [`FileStorageService`](04-service.md#filestorageservice) | Service | 圖片在磁碟上的落地、搬移與路徑安全 |
| [`LineStorageService`](04-service.md#linestorageservice) | Service | 與 LINE Messaging API 的唯一往來出口 |
| [`LineWebhookController`](03-controller.md#linewebhookcontroller) | Controller | LINE Webhook 接收、驗簽、分派 |
| [`MediaController`](03-controller.md#mediacontroller) | Controller | 對外提供資產圖片給 LINE 抓取 |
| `ImageArchiveService` | Service | 多圖暫存、確認與正式歸檔 |
| `MethodTraceLogger` | Component | 以 AOP 追蹤所有 Spring 公開方法的進入、完成與失敗 |
| `OperationalStatusLogger` | Component | 啟動完成後輸出安全的設定就緒狀態 |
| `PendingImageRepository` | Repository | 暫存圖片組與待確認操作的資料庫出入口 |
| `QuotationAdminController` | Controller | 本機主檔、AI 驗證、XLSX／CSV 與報價 Excel 產出 API |
| `QuotationAdminRepository` | Repository | 報價方案、品項、對應與稽核資料的 SQLite 出入口 |
| `QuotationAdminService` | Service | 驗證並協調本機報價主檔異動 |
| `QuotationAiParsingService` | Service | 將文字交給 AI 並以固定契約驗證回應 |
| `QuotationAiPromptService` | Service | 從資料庫建立不含價格的 AI 品項目錄提示 |
| [`QuotationAmounts`](06-record.md#quotationamounts) | Record | 舊版相容報價金額資料契約；正式流程使用 `QuotationCalculationResult` |
| [`QuotationCalculator`](04-service.md#quotationcalculator) | Service | 舊版第二階段工程尺寸推算保留介面；DIRECT 正式計價不使用 |
| `QuotationCalculationService` | Service | 以主檔與明確數量建立五格式精確計價結果 |
| `QuotationConversationService` | Service | LINE 多輪缺漏、圖片、預覽、確認與取消狀態機 |
| `QuotationGenerationCoordinator` | Service | 串接正式圖片、Excel、PDF 與 LINE 最終交付 |
| `QuotationGenerationLauncher` | Service | 喚醒以 SQLite 租約保存的背景 Excel COM 工作 |
| `QuotationOutputDirectoryService` | Service | 建立無法跳脫根目錄的報價案件資料夾 |
| `QuotationRequestValidationService` | Service | 嚴格驗證 AI JSON 並從資料庫解析固定欄位與 DIRECT 複價 |
| `QuotationMasterDataCsvService` | Service | 匯出具公式注入防護的 UTF-8 主檔 CSV |
| `QuotationMasterDataXlsxService` | Service | 匯出具格式、篩選與文字公式隔離的正式 XLSX 主檔 |
| `QuotationWorkbookService` | Service | 將已驗證報價寫入五種 XLSX 範本且保留媒體與格式 |
| [`QuotationPdfService`](04-service.md#quotationpdfservice) | Service | 以本機 Microsoft Excel 匯出正式 XLSX 為 PDF並保存重試狀態 |
| [`QuotationService`](04-service.md#quotationservice) | Service | 舊版相容入口；正式 LINE 報價不再使用 |
| `RequestCorrelationFilter` | Component | 為每個 HTTP 請求建立 Request ID 並記錄結果 |
| [`StorageConfig`](02-configuration.md#storageconfig) | Configuration | 建立 SQLite 資料來源並確保目錄存在 |

---

## 分層關係

```mermaid
flowchart TD
    LINE[LINE 平台]

    subgraph Controller
        WH[LineWebhookController]
        MC[MediaController]
    end

    subgraph Service
        CS[CommandService]
        AS[AssetService]
        IAS[ImageArchiveService]
        FS[FileStorageService]
        LS[LineStorageService]
        QS[QuotationService]
        AI[AiExtractionService]
        QC[QuotationCalculator]
        QP[QuotationPdfService]
    end

    subgraph Repository
        AR[AssetRepository]
        PIR[PendingImageRepository]
    end

    DB[(SQLite assets.db)]
    DISK[/磁碟 ASSETS_ROOT//]
    MODEL[AI 模型端點]

    LINE -->|Webhook| WH
    LINE -->|抓圖| MC
    WH --> CS
    WH --> IAS
    WH --> LS
    CS --> AS
    CS --> IAS
    CS --> QS
    CS --> LS
    QS --> AI
    QS --> QC
    QS --> QP
    AI --> MODEL
    AS --> AR
    AS --> FS
    IAS --> AR
    IAS --> PIR
    IAS --> FS
    MC --> AS
    MC --> FS
    AR --> DB
    FS --> DISK
    LS --> LINE
```

### 分層規則

1. **Controller 不含業務判斷**。看懂請求之後就往下丟，錯誤處理與回覆文案屬於 Service。
2. **`ImageArchiveService` 負責目前正式的暫存與確認歸檔**；`AssetService` 保留查詢、
   內容讀取與舊式直接收錄入口。
3. **只有 `LineStorageService` 對 LINE 發 HTTP**，channel token 也只出現在那裡。
4. **SQL 集中在 `AssetRepository` 與 `PendingImageRepository`**。其他層只看 Java 型別。
5. **所有查詢必須帶 `sourceId`**，否則不同群組的資產會互相看見。
