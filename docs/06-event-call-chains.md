# 事件起點與完整呼叫鏈

本文件以「哪一個事件先發生」為起點，追蹤事件如何穿過 Controller、Service、
Repository、SQLite、磁碟與外部 API。若要理解單一類別的欄位與方法，再搭配
[類別索引](reference/index.md) 閱讀。

---

## 狀態圖例

| 標記 | 意義 |
|---|---|
| ✅ | 目前已接通且有測試覆蓋 |
| ⚠️ | 已進入流程，但會因必要設定、外部程式或環境限制而受控中止 |
| 🧩 | 已核准但刻意延後的第二階段擴充，不屬於目前正式事件 |
| 🕰️ | 保留的舊式／程式化入口，目前沒有 Controller 呼叫 |

---

## 所有事件起點

| 事件起點 | 第一個專案類別 | 最終效果 | 狀態 |
|---|---|---|---|
| Spring Boot 啟動 | `AssetsManagerLinebotApplication` | 建立目錄、SQLite、資料表與啟動狀態日誌 | ✅ |
| 任意 HTTP 請求 | `RequestCorrelationFilter` | 建立 Request ID、交給後續端點、記錄結果 | ✅ |
| 任一 Spring 公開方法 | `MethodTraceLogger` | 記錄方法進入、完成／失敗與耗時 | ✅ |
| LINE 圖片訊息 | `LineWebhookController` | 下載圖片並暫存到 `.pending` | ✅ |
| 引用圖片輸入 `zdYYYYMMDD` | `LineWebhookController` | 檢查整組圖片並建立待確認歸檔 | ✅ |
| 輸入「確定／確認」 | `LineWebhookController` | 將整組圖片正式歸檔並建立資產索引 | ✅ |
| 輸入「取消」 | `LineWebhookController` | 清除待確認操作，圖片仍留在暫存區 | ✅ |
| 輸入 `#說明` | `LineWebhookController` | 回覆使用方式 | ✅ |
| 輸入 `#標籤` | `LineWebhookController` | 統計目前群組的標籤和圖片數 | ✅ |
| 輸入 `#查 標籤` | `LineWebhookController` | 查出圖片並回覆公開圖片網址 | ✅ |
| 標記機器人並輸入 `ping` | `LineWebhookController` | 回覆 `pong` 與事件到處理的延遲毫秒數 | ✅ |
| LINE 伺服器抓取圖片網址 | `MediaController` | 從磁碟串流圖片 | ✅ |
| 引用圖片輸入 `#報價` | `LineWebhookController` | AI 擷取規格，計算與輸出階段目前中止 | ⚠️ |
| `GET /actuator/health` | Spring Boot Actuator | 回覆容器健康狀態 | ✅ |
| 程式直接呼叫 `AssetService.ingest/tag` | `AssetService` | 直接收錄或替既有資產掛標籤 | 🕰️ |
| 程式直接建立報價輸出目錄 | `QuotationOutputDirectoryService` | 建立安全的案件資料夾 | 🧩 |

---

## 全域事件分派

```mermaid
flowchart TD
	LINE["LINE 平台"]
	HTTP["任意 HTTP 用戶端"]
	START["Spring Boot 啟動"]

	FILTER["RequestCorrelationFilter"]
	WEBHOOK["POST /callback<br/>LineWebhookController"]
	MEDIA["GET /media/{shareToken}<br/>MediaController"]
	HEALTH["GET /actuator/health<br/>Actuator"]

	IMAGE["圖片事件"]
	TEXT["文字事件"]
	OTHER["其他 LINE 事件"]

	START --> BOOT["建立 DataSource、初始化 schema、輸出就緒狀態"]
	LINE --> FILTER
	HTTP --> FILTER
	FILTER --> WEBHOOK
	FILTER --> MEDIA
	FILTER --> HEALTH

	WEBHOOK --> VERIFY{"HMAC-SHA256<br/>簽章有效？"}
	VERIFY -->|"否"| UNAUTHORIZED["401 Invalid Signature"]
	VERIFY -->|"是"| DISPATCH{"message.type"}
	DISPATCH -->|"image"| IMAGE
	DISPATCH -->|"text"| TEXT
	DISPATCH -->|"其他"| OTHER
	OTHER --> IGNORE["安靜忽略"]
```

每個 HTTP 請求都先經過 `RequestCorrelationFilter`。因此 LINE webhook、媒體抓圖與
健康檢查都會取得 `X-Request-ID`；但健康檢查不寫完成日誌，避免固定探測造成洗版。
進入 Controller、Service 或 Repository 公開方法時，`MethodTraceLogger` 會以 Aspect
包住方法執行，沿用同一個 Request ID 串起 `method_entered`、`method_completed`
或 `method_failed`。它不記錄參數或回傳值。

### 跨越所有功能的 AOP 方法追蹤

```text
RequestCorrelationFilter
└─ MDC.requestId
	└─ MethodTraceLogger.trace
		├─ event=method_entered
		├─ ProceedingJoinPoint.proceed
		│	└─ Controller／Service／Repository 原方法
		├─ event=method_completed
		└─ event=method_failed
```

沒有 HTTP Request ID 的啟動或背景方法會暫時使用 `background-{UUID}`。
設定 `METHOD_TRACING_ENABLED=false` 可停用這層追蹤。

---

## 事件 1：應用程式啟動

```text
AssetsManagerLinebotApplication.main
└─ SpringApplication.run
	├─ StorageConfig.dataSource
	│	├─ Files.createDirectories(ASSETS_ROOT)
	│	└─ HikariDataSource
	│		├─ SQLite JDBC URL
	│		├─ maximumPoolSize = 1
	│		└─ PRAGMA foreign_keys=ON
	├─ Spring SQL initializer
	│	└─ schema.sql
	│		├─ 資產與暫存資料表
	│		├─ 報價主檔與規則資料表
	│		├─ AI 請求與圖片選擇資料表
	│		└─ 報價快照與三種模板種子資料
	└─ ApplicationReadyEvent
		└─ OperationalStatusLogger.logApplicationReady
			├─ AiExtractionService.isConfigured
			├─ QuotationOutputDirectoryService.isConfigured
			└─ 記錄三項布林就緒狀態
```

`StorageConfig` 必須先建立資產根目錄，SQLite 才能建立 `assets.db`。連線池限制為
一條連線，是因為 SQLite 同一時間只有一個寫入者；外鍵則必須逐連線啟用。

---

## 事件 2：LINE 圖片訊息進入暫存區

```mermaid
sequenceDiagram
	participant LINE as LINE 平台
	participant Filter as RequestCorrelationFilter
	participant Webhook as LineWebhookController
	participant LineAPI as LineStorageService
	participant Archive as ImageArchiveService
	participant Files as FileStorageService
	participant Pending as PendingImageRepository
	participant DB as SQLite

	LINE->>Filter: POST /callback
	Filter->>Webhook: 已附 Request ID 的請求
	Webhook->>Webhook: 驗證簽章、解析 source 與 imageSet
	Webhook->>LineAPI: downloadContent(messageId)
	LineAPI->>LINE: GET /v2/bot/message/{id}/content
	LINE-->>LineAPI: 圖片串流 + Content-Type
	LineAPI-->>Webhook: LineContent
	Webhook->>Archive: stage(...)
	Archive->>Pending: findByMessageId
	Pending->>DB: 查暫存紀錄
	Archive->>DB: 透過 AssetRepository 檢查正式資產
	Archive->>Files: savePending
	Files-->>Archive: .pending 相對路徑
	Archive->>Pending: insert(PendingImage)
	Pending->>DB: INSERT pending_image
	Webhook-->>LINE: HTTP 200 OK
```

完整方法鏈：

```text
RequestCorrelationFilter.doFilterInternal
└─ LineWebhookController.handleWebhook
	└─ handleEvent
		└─ handleImage
			├─ LineStorageService.downloadContent
			└─ ImageArchiveService.stage
				├─ PendingImageRepository.findByMessageId
				├─ AssetRepository.findByMessageId
				├─ FileStorageService.savePending
				└─ PendingImageRepository.insert
```

`messageId` 同時在暫存與正式資料中檢查，LINE 重送相同 webhook 時不會產生第二份檔案。
若暫存檔已寫入，但 SQLite 寫入失敗，`stage()` 會刪除該暫存檔，避免孤兒檔案。

---

## 事件 3：引用圖片輸入 `zdYYYYMMDD`

```text
LineWebhookController.handleEvent
└─ CommandService.handleText
	├─ ARCHIVE_CODE 驗證「zd + 8 位日期」
	└─ CommandService.requestArchive
		└─ ImageArchiveService.requestArchive
			├─ 驗證日期是否為真實日曆日期
			├─ PendingImageRepository.findByMessageId
			├─ 比對 quoted image 的 sourceId
			├─ PendingImageRepository.findSet
			├─ 比對已收到張數與 imageTotal
			└─ PendingImageRepository.saveConfirmation
				└─ UPSERT pending_archive_confirmation
```

成功後的回覆鏈：

```text
CommandService.requestArchive
└─ LineStorageService.replyText
	└─ LineStorageService.reply
		└─ LineStorageService.post
			└─ POST https://api.line.me/v2/bot/message/reply
```

可能分支：

| 狀態 | 原因 | LINE 回覆 |
|---|---|---|
| `READY` | 圖片已收齊、日期有效 | 顯示張數與目標日期，等待「確定」 |
| `INVALID_DATE` | 不是有效的日曆日期 | 提示正確格式 |
| `INCOMPLETE_SET` | `imageSet` 尚未收齊 | 顯示目前張數／預期張數 |
| `WRONG_SOURCE` | 引用的圖片不屬於目前來源 | 統一回覆找不到待歸檔圖片 |
| `NOT_FOUND` | 暫存資料不存在 | 提示重新上傳 |

---

## 事件 4：輸入「確定／確認」

```mermaid
sequenceDiagram
	participant User as LINE 使用者
	participant Command as CommandService
	participant Archive as ImageArchiveService
	participant Pending as PendingImageRepository
	participant Files as FileStorageService
	participant Asset as AssetRepository
	participant DB as SQLite
	participant LINE as LineStorageService

	User->>Command: 確定
	Command->>Archive: confirm(sourceId, requesterId)
	Archive->>Pending: findConfirmation
	Pending->>DB: SELECT pending_archive_confirmation
	Archive->>Pending: findSet
	Pending->>DB: SELECT pending_image
	Archive->>Asset: upsertTag("zd" + archiveDate)
	loop 每一張圖片
		Archive->>Files: archivePending
		Files-->>Archive: 正式日期路徑
		Archive->>Asset: insert(asset)
		Archive->>Asset: linkTag(assetId, tagId)
	end
	Archive->>Files: delete(.pending 原檔)
	Archive->>Pending: deleteSet + deleteConfirmation
	Archive-->>Command: ARCHIVED
	Command->>LINE: replyText
```

完整方法鏈：

```text
CommandService.handleText
└─ CommandService.confirmArchive
	└─ ImageArchiveService.confirm
		├─ PendingImageRepository.findConfirmation
		├─ PendingImageRepository.findSet
		├─ AssetRepository.upsertTag
		├─ 每一張 PendingImage
		│	├─ FileStorageService.archivePending
		│	├─ AssetRepository.insert
		│	└─ AssetRepository.linkTag
		├─ FileStorageService.delete
		├─ PendingImageRepository.deleteSet
		└─ PendingImageRepository.deleteConfirmation
```

正式檔案名稱以圖片在 `imageSet` 內的順序產生，例如
`20260728/20260728-001.jpg`。若正式歸檔中途失敗，已建立的正式檔會被清除，
原始 `.pending` 檔案仍保留供重試。

---

## 事件 5：輸入「取消」

```text
CommandService.handleText
└─ ImageArchiveService.cancel
	├─ PendingImageRepository.findConfirmation
	└─ PendingImageRepository.deleteConfirmation
		└─ LineStorageService.replyText
```

取消只刪除「等待確認」狀態，不刪除 `.pending` 圖片或 `pending_image`。這能避免使用者
誤按取消後立即失去尚未歸檔的原圖。

---

## 事件 6：輸入 `#說明`

```text
CommandService.handleText
└─ CommandService.handleCommand
	└─ LineStorageService.replyText(HELP)
		└─ POST LINE reply API
```

別名包括 `#help` 與 `#?`。未知的井字號指令不回覆，避免在群組內洗版。

---

## 事件 7：輸入 `#標籤` 或 `#清單`

```text
CommandService.handleText
└─ CommandService.handleCommand
	└─ CommandService.replyTagList
		├─ AssetService.tagCounts
		│	└─ AssetRepository.tagCounts
		│		└─ SELECT tag + asset_tag + asset WHERE source_id = ?
		├─ AssetService.countBySource
		│	└─ AssetRepository.countBySource
		│		└─ SELECT COUNT(*) WHERE source_id = ?
		└─ LineStorageService.replyText
```

所有統計都帶 `sourceId`，因此同一個標籤名稱可以存在於不同群組，但查詢結果不會互通。

---

## 事件 8：輸入 `#查 標籤`

### 第一段：Bot 查詢並回覆圖片網址

```text
CommandService.handleText
└─ CommandService.handleCommand
	└─ CommandService.replySearch
		├─ 檢查 tags 非空
		├─ 檢查 PUBLIC_BASE_URL
		├─ 將查詢標籤轉成小寫
		├─ AssetService.search
		│	└─ AssetRepository.searchByTags
		│		├─ WHERE source_id = ?
		│		├─ WHERE tag IN (...)
		│		├─ HAVING COUNT(DISTINCT tag) = 查詢標籤數
		│		└─ AssetRepository.withTags
		└─ LineStorageService.reply
			├─ 文字摘要
			└─ 每筆資產的 /media/{shareToken} 圖片訊息
```

多個標籤是 AND，不是 OR。單次 reply 最多五則，因此預設是一則摘要加四張圖片。

### 第二段：LINE 伺服器抓取每張圖片

```mermaid
sequenceDiagram
	participant LINE as LINE 圖片伺服器
	participant Filter as RequestCorrelationFilter
	participant Media as MediaController
	participant Asset as AssetService
	participant Repo as AssetRepository
	participant Paths as AssetPathResolver
	participant Disk as ASSETS_ROOT／QUOTATION_ROOT_PATH

	LINE->>Filter: GET /media/{shareToken}
	Filter->>Media: 已附 Request ID 的請求
	Media->>Asset: findByShareToken
	Asset->>Repo: findByShareToken
	Repo-->>Media: Asset 或 empty
	Media->>Paths: resolve(asset)
	Paths-->>Media: 依 quotation_asset 關聯解析安全絕對路徑
	Media->>Disk: Files.isReadable
	Disk-->>Media: 圖片檔案
	Media-->>LINE: Content-Type + FileSystemResource
```

`shareToken` 是每筆資產獨立的隨機值，不使用可預測的資料庫流水號。即使 SQLite 的
`file_path` 被竄改，一般資產與正式報價資產的 containment 驗證仍會阻止讀取各自根目錄外的檔案。
儲存範圍只由可信的 `quotation_asset` 關聯決定，不採信路徑文字前綴。

---

## 事件 9：LINE 一對一 `#報價`

```text
LineWebhookController
└─ QuotationLineWorkflowService
	├─ SqliteQuotationDraftWorkflowPort
	│	├─ QuotationAiParsingService：AI／OCR 2.x patch
	│	├─ QuotationConversationService：缺漏、圖片、預覽狀態機
	│	└─ QuotationCalculationService：DIRECT 複價、5% 稅額與總額
	├─ QuotationLineMessageBuilder：缺漏清單、預覽、確認／取消按鈕
	└─ CONFIRM postback
		├─ QuotationConfirmationService：同一交易配置流水號、正式快照與 generation job
		├─ QuotationReplyOutboxService：持久化處理中回覆，reply 失敗可冪等 push
		└─ QuotationGenerationLauncher：喚醒 SQLite 租約工作者
			└─ QuotationGenerationJobWorker
				└─ QuotationGenerationCoordinator
				├─ QuotationAssetArchiveService：全部候選原圖移入日期流水號資料夾
				├─ QuotationWorkbookService：五格式 Excel、分頁與唯一選圖
				├─ QuotationPdfService：Microsoft Excel COM 匯出 PDF
				└─ QuotationDeliveryService：HTTPS 下載連結與 LINE Flex 推播
```

只有 `source.type=user` 可建立或修改草稿；群組與 room 的報價指令只提示改用私訊。AI 只提供
欄位 patch、缺漏及圖片評分，應用程式驗證後才轉移狀態，金額、流水號與檔案路徑均由程式決定。
確認請求不等待最長 90 秒的 Excel COM：流水號交易完成後立即提交背景工作並回覆處理中。

背景執行器固定單一工作者，避免同時操作 Excel COM；待辦本體保存在
`quotation_generation_job`，工作者以租約、退避時間與嘗試次數接續處理。程序啟動及排程都會掃描
待辦與過期租約；記憶體執行器滿載時只略過本次喚醒，資料庫工作仍可於下一次喚醒恢復。XLSX 失敗
可由管理頁沿用同一報價單號與快照重排；PDF 失敗保留 Excel 及 `PDF_FAILED`，LINE 傳送失敗保留
READY PDF。

LINE 的 AI／OCR 網路呼叫在資料庫交易外完成；其後的對話狀態轉換、
`quotation_reply_outbox` 入列與 event receipt 完成則以同一個短交易提交。若程序在入列前中止，
租約到期後可由相同 webhook 重領，並依已提交的 message id 重建相同回覆而不重跑 AI。
reply token 失效或程序在送出前重啟時，以事件與目的地衍生的穩定 retry key 改走 push；
已送出的 outbox 不會再次傳送。

---

## 事件 10：健康檢查

```text
Docker / docker compose
└─ GET /actuator/health
	├─ RequestCorrelationFilter
	│	└─ 不寫 http_request_completed 日誌
	└─ Spring Boot Actuator
		└─ 回覆 UP / DOWN
```

健康檢查由 Spring Boot Actuator 提供，不會進入專案自訂 Controller。

---

## 保留但未由目前事件呼叫的入口

### `AssetService.ingest`

```text
外部 Java 呼叫者
└─ AssetService.ingest
	├─ AssetRepository.findByMessageId
	├─ FileStorageService.save
	├─ AssetRepository.insert
	└─ AssetRepository.findByMessageId
```

這是圖片「立即落入當天正式資料夾」的舊式入口。目前 LINE webhook 改走
`ImageArchiveService.stage()`，先暫存、再等待日期確認。

### `AssetService.tag`

```text
外部 Java 呼叫者
└─ AssetService.tag
	├─ AssetRepository.findByMessageId
	├─ AssetRepository.upsertTag
	├─ AssetRepository.linkTag
	└─ AssetRepository.findByMessageId
```

目前日期歸檔流程會自動掛上 `zdYYYYMMDD`，所以 Controller 沒有直接呼叫 `tag()`；
此方法仍保留「同一張圖片掛多個標籤」的程式化能力。

---

## 故障邊界與回覆責任

| 層級 | 主要責任 | 失敗時行為 |
|---|---|---|
| `RequestCorrelationFilter` | Request ID 與請求完成日誌 | `finally` 仍會寫狀態與耗時 |
| `MethodTraceLogger` | 公開 Spring 方法的進入、完成與失敗追蹤 | 不記錄參數與回傳值，避免敏感資料進入日誌 |
| `LineWebhookController` | 驗簽、JSON 解析、事件分派 | 單一事件失敗不拖垮整批 webhook |
| `CommandService` | 將業務狀態翻成 LINE 文案 | 普通聊天與未知指令安靜忽略 |
| `ImageArchiveService` | 檔案與 SQLite 的歸檔協調 | 清理部分建立的正式檔，保留暫存來源 |
| `AssetRepository` | SQL 與群組資料隔離 | 例外交給上層交易處理 |
| `FileStorageService` | 路徑安全與檔案操作 | 阻擋根目錄外路徑 |
| `LineStorageService` | LINE 外部 HTTP 呼叫 | 記錄狀態碼或例外，不向上拋出 |
| `AiExtractionService` | AI 呼叫、解析、必要欄位驗證 | 統一拋出 `AiExtractionException` |
| `QuotationLineWorkflowService` | 草稿、確認與背景工作提交 | 缺漏可繼續補件；佇列滿載回覆安全忙碌訊息 |
| `QuotationGenerationCoordinator` | 正式圖片、Excel、PDF 與交付串接 | 各階段保存可重試狀態，不倒退破壞已完成檔案 |

---

## 閱讀程式碼的建議順序

1. `LineWebhookController`：先看事件如何進入。
2. `CommandService`：看文字如何分派。
3. `ImageArchiveService`：看圖片生命週期。
4. `PendingImageRepository`、`AssetRepository`：看資料如何保存與隔離。
5. `FileStorageService`：看磁碟結構與路徑安全。
6. `MediaController`、`LineStorageService`：看 LINE 如何取回圖片。
7. `QuotationLineWorkflowService`、`QuotationGenerationCoordinator`：看報價確認與背景完成邊界。
8. `schema.sql`：最後看完整資料模型與新版報價資料層。
