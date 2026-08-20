# 文件樹

`assets-manager-linebot` 的內部維護文件。版本 `@assets-manager-linebot@0.1.0`。

---

## 導覽

| 文件 | 什麼時候看 |
|---|---|
| [01 部署與外部串接指南](01-bot-deployment.md) | 要在一台新機器上把服務跑起來 |
| [02 LINE Bot 規則與各階段處理](02-linebot-rules.md) | 動任何跟訊息收發有關的程式碼之前；測試或部署卡住時 |
| [03 版本、Release 與 Push SOP](03-versioning-release-sop.md) | 要 commit、push、發版本、部署或回滾 |
| [04 LINE Bot 建置流程](04-linebot-build-guide.md) | 第一次從零建立 LINE Bot，或要重新建一個 Channel |
| [05 Excel 報價規格](05-quotation-excel-spec.md) | 要理解報價資料表、AI 契約與五種 Excel 模板 |
| [06 事件起點與完整呼叫鏈](06-event-call-chains.md) | 要從 LINE／HTTP／啟動事件一路追到資料庫、磁碟與外部 API |
| [07 報價資料庫安全讀寫](07-quotation-database-operations.md) | 要查詢、批次更新主檔或匯出 XLSX／CSV |
| [08 LINE AI 自動化報價 SRS](08-quotation-automation-srs.md) | 正式報價功能、對話流程、Excel／PDF、圖片與驗收規格 |
| [10 驗收證據矩陣](10-acceptance-verification-matrix.md) | 查 AC-01 至 AC-22 的自動測試、實機證據與環境限制 |
| [類別索引](reference/index.md) | 要改程式碼，想先知道該動哪個檔案 |

---

## 文件結構

```
docs/
├── README.md                      ← 你在這裡
├── 01-bot-deployment.md           部署與串接
├── 02-linebot-rules.md            LINE 平台規則、測試階段、部署階段
├── 03-versioning-release-sop.md   版本編號、Push SOP、Release SOP
├── 04-linebot-build-guide.md      從零建立 LINE Bot 的完整流程
├── 05-quotation-excel-spec.md     Excel 報價資料層與模板規格
├── 06-event-call-chains.md        由事件起點追蹤所有功能呼叫鏈
├── 07-quotation-database-operations.md  報價資料庫安全讀寫與 XLSX／CSV 匯出
├── 08-quotation-automation-srs.md LINE AI 自動化報價 SRS
├── 09-observability-runbook.md    日誌、資源與 AI 成本操作
├── 10-acceptance-verification-matrix.md AC-01 至 AC-22 驗收證據
└── reference/                     類別參考（內部維護）
    ├── index.md                   ← Navigator，所有類別由此進入
    ├── 01-application.md          Application
    ├── 02-configuration.md        Configuration
    ├── 03-controller.md           Controller
    ├── 04-service.md              Service
    ├── 05-repository.md           Repository
    ├── 06-record.md               Record
    ├── 07-exception.md            Exception
    └── 08-test.md                 Test
```

`reference/` 依 **Java 類別型態**分類，不依功能模組分。理由是找檔案時通常已經知道要找的是 controller 還是 service，按型態分最快收斂。

---

## 維護規則

1. **類別文件與程式碼在同一個 commit 內同步。** 新增或刪除類別時，`reference/index.md` 的兩張表與對應分類頁都要更新。分開做的結果一定是文件永遠落後。
2. **記錄「為什麼」，不是「是什麼」。** 方法簽章看程式碼就有；文件要寫的是為什麼這樣設計、不這樣做會壞在哪。
3. **升版本時同步版本標記**，位置見 [SOP 1.4 節](03-versioning-release-sop.md#14-需要同步修改的位置)。

---

## 三十秒理解這個專案

LINE 群組是資產的收件與取件窗口：

1. 群組傳圖 → 先下載到 `{ASSETS_ROOT}/.pending/`，並記錄 LINE `imageSet`
2. 引用其中一張輸入 `zd20260728` → 檢查整組圖片是否收齊，等待使用者確認
3. 輸入「確定」→ 整組存入 `{ASSETS_ROOT}/20260728/`，SQLite 建立資產並掛上 `zd20260728`
4. 輸入 `#查 zd20260728` → 從資料庫查出指向，透過對外端點把圖片貼回群組
5. LINE 一對一 `#報價` 會分輪補件、顯示完整預覽，確認後在背景產出 Excel／PDF 並推送下載連結

核心設計仍是「**指標法**」：**磁碟只負責保存，資料庫負責組織**。
圖片確認歸檔後不再因標籤變更而搬動；完整方法級呼叫順序見
[事件起點與完整呼叫鏈](06-event-call-chains.md)。

資產庫位置由 `ASSETS_ROOT` 指定，可以是任意路徑，例如 `F:/資產庫`：

```
F:\資產庫\
├─ assets.db
├─ .pending\
├─ 20260727\
│  ├─ 20260727-001.jpg
│  └─ 20260727-002.jpg
└─ 20260728\
   └─ 20260728-001.jpg
```
