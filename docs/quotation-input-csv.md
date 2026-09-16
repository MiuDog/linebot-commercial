# 上傳 CSV 建立報價草稿

在 Commercial 的 LINE **一對一私訊**上傳 `.csv` 檔，機器人會解析成草稿，再要求補齊資料、預覽與確認。此入口不使用 AI 抽取；仍需啟用公司資產、主檔與範本才能正式產檔。

下載 [自訂品項範例](examples/quotation-input-sales.csv)，用 Excel 編輯後另存為 **CSV UTF-8**。這是報價輸入，不是維護主檔的 CSV。

## 欄位

第一列必須保持以下順序與名稱：

```csv
schemeCode,companyName,workName,salesRepresentative,contactName,phone,itemCode,itemName,specification,unit,unitPrice,quantity,remark
```

| 欄位 | 規則 |
| --- | --- |
| schemeCode | 每列必填且相同：CNS、GENERAL、MARINE、BLANK、SALES |
| companyName、workName、salesRepresentative | 表頭可只填第一筆；缺值由草稿流程要求補齊 |
| contactName、phone | 選填；多列填寫不得互相衝突 |
| itemCode | CNS／GENERAL 固定品項填啟用代碼，其他品項留空 |
| itemName、specification、unit、unitPrice | 自訂品項必填；固定代碼品項須留空，由主檔提供 |
| quantity | 每列必填，大於 0 |
| remark | 自訂品項選填；固定代碼品項留空 |

價格允許 0；數量與價格上限均為 1,000,000,000，最多 6 位小數，不接受千分位或科學記號。CNS／GENERAL 自訂品項沿用最多兩筆的業務限制；MARINE／BLANK／SALES 全部使用自訂品項。圖片格式仍要求圖片或明確略過。

## 檔案與更新規則

- 上限 1 MiB、200 筆品項，UTF-8 可含 BOM。
- 逗號、換行或雙引號所在儲存格需以雙引號包住，內部雙引號寫成兩個；Excel 匯出會處理。
- 錯誤列號是含表頭的 CSV 紀錄序號，儲存格換行不另算一列。
- 同一 LINE 事件重送不重複套用。另傳檔案會合併到目前草稿，**不是整份取代**；省略的舊品項不會自動刪除。
- 自訂品項以資料列序號識別；修改草稿時保持列順序。完全新的一份應先取消舊草稿。
- 草稿格式鎖定後不能匯入不同格式，請先結束原草稿。
- 群組 CSV 不建立報價。先在私訊測試範例，確認預覽後再確認正式產檔。
