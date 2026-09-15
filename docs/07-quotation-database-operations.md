# 報價主檔 CSV 與範本檔案操作

## 現行維護方式

品項／別名／單價以 CSV 維護，報價版面以 XLSX 與 TEMPLATE_DEFINITIONS 維護。網頁逐筆新增、改價及格式品項編輯已撤銷為產品需求。正式執行使用 PostgreSQL／Flyway 與 S3；舊版 SQLite、assets.db、BEGIN IMMEDIATE 操作不適用現行部署。

資料庫保存執行資料與歷史報價快照；公司保管可編輯母檔及版本。不要直接改 quotation、quotation_line 來套用新價格。

## 檔案分工

| 內容 | 檔案與操作 |
| --- | --- |
| 品項代碼、名稱、AI 別名、規格、單位、單價、排序及啟用狀態 | 匯出 UTF-8 CSV → Excel／試算表軟體編輯 → 存回 UTF-8 CSV |
| 合併儲存格、圖片、列印範圍、紙張方向、公司版面 | 編輯五種 XLSX 範本，保留必要儲存格與列印設定 |
| 工作表、明細範圍、欄位座標、圖片放置規則 | 維護 TEMPLATE_DEFINITIONS，須與 XLSX 同版本匹配 |
| Logo、印章與聯絡資料 | 公司資產包中的對應檔案 |

CSV 不保存 Excel 版面、圖片、公式或多工作表。quotation-master-data.xlsx 是主檔匯出檔，不是報價版面範本；目前只有 CSV 主檔匯入端點，不能把 XLSX 改副檔名後上傳。

## 正式發布流程

1. 匯出目前 CSV 並另存備份；從公司母檔取得對應 XLSX／範本定義。
2. 在檔案內修改，保留完整主檔、欄位名稱與順序。
3. 建立新的公司資產版本，將 CSV 作為 ITEM_MASTER，連同全部必要範本／資產放入 manifest，填寫正確大小及 SHA-256。
4. 透過受保護管理 API 依序 stage → validation → approval → activation；操作及 manifest 形式見 [公司資產治理](company-asset-governance.md)。stage 會驗證 CSV，activation 在交易中套用主檔並切換版本。
5. 檢查五種格式預覽及 XLSX／LibreOffice PDF；保留版本、核准者與驗收紀錄。需回復時使用資產版本 rollback。

確認過的正式報價保存資產版本與品項快照；更新主檔不應改寫歷史報價。

## CSV 欄位與限制

請以實際匯出檔為起點，標題必須逐字且順序一致：

```csv
報價格式代碼,報價格式,品項代碼,品項,AI別名,規格/說明,單位,單價,備註,顯示順序,計價模式,顯示於客戶,格式品項啟用,品項主檔啟用
```

- 編碼為 UTF-8，可包含 BOM；中文別名以「、」分隔。含逗號或換行的儲存格須使用 CSV 引號規則。
- 最大檔案大小 4 MiB；以小於 5,000 列（含標題）的批次維護，避免觸及解析上限。
- 無格式的共用品項列不可重複品項代碼；格式品項的「格式代碼＋品項代碼」組合不可重複。
- CNS／GENERAL 使用固定品項；MARINE／BLANK／SALES 不接受固定格式品項列，報價內容使用 DYNAMIC 品項。
- 格式品項須有單位、合法單價及排序；計價模式接受 DIRECT、DERIVED、MANUAL，空白預設 DIRECT。接受模式不代表每種推算規則皆已實作。
- **匯入為整批取代：未出現在檔案中的品項與格式關聯會停用，並非刪除；保留列依檔案的啟用欄位設定。** 不要只上傳改價的幾列。
- 檔案會完整解析後才寫入，寫入在交易中執行；驗證錯誤先修檔再重送。
- 匯出有公式注入保護。請保留文字型別，不要將代碼、電話等轉成數值或公式。

## 既有管理 API 與實作落差

本地 Compose API 位址預設為 http://127.0.0.1:8088/api/admin，需 X-Admin-Token；寫入另需 X-Local-Admin-Request: 1 並通過同源檢查。資產版本 API 還需 X-Admin-Actor。不要經公開 Tunnel 暴露管理 API。

| 端點 | 目前行為與用途 |
| --- | --- |
| GET /quotation-master-data.csv | 匯出 CSV 母檔起點 |
| GET /quotation-master-data.xlsx | 匯出可閱讀的 XLSX 主檔 |
| POST /quotation-master-data.csv，Content-Type: text/csv | 既有直接整批匯入；本文為 CSV 位元組，非 multipart。會直接改目前主檔，**不建立資產版本或執行核准**；正式發布請走資產包生命週期 |
| GET /quotation-schemes、GET /quotation-items、GET /quotation-schemes/{schemeCode}/items | 查閱格式與主檔 |
| POST /quotation-items、PATCH /quotation-items/{itemId}、PUT /quotation-schemes/{schemeCode}/items/{itemId} | 舊版逐筆編輯 API 仍存在，待退役；不得作為檔案維護需求的替代方案 |
| POST /quotation-ai-parse、POST /quotation-request-validation?schemeCode= | AI 解析／固定 JSON 驗證，非 CSV 建立報價入口 |

/admin/ 目前仍包含逐筆新增品項及格式品項編輯表單；本次修訂撤銷的是規格，尚未移除程式或封鎖端點。後續退役應保留 CSV 交換、報價查詢／預覽與工作重試能力，並確認所有寫入都符合公司資產版本治理。
