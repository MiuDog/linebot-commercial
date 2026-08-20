# 報價單 Excel 範本分析

## 輸出結果

- 原始活頁簿含 CNS、一般架、船用、空白、銷售報價單五種格式，已各自拆成單一工作表的 XLSX 範本。
- 各範本保留原始格式、公式、列印範圍、頁面設定、公司標誌、蓋章及固定聯絡資訊。
- 原始 XLSM 的 VBA 不會保留於 XLSX；目前 Java 產製流程不依賴 VBA。

| schemeCode | 工作表 | 範本檔案 | 列印範圍 | 品項配置 |
| --- | --- | --- | --- | --- |
| CNS | CNS | quotation-template-CNS.xlsx | A1:H51 | 21 |
| GENERAL | 一般架 | quotation-template-GENERAL.xlsx | A1:H50 | 20 |
| MARINE | 船用 | quotation-template-MARINE.xlsx | A1:H41 | 13 個動態列 |
| BLANK | 空白 | quotation-template-BLANK.xlsx | A1:H47 | 18 個動態列 |
| SALES | 銷售報價單(報價單號前會多一個S) | quotation-template-SALES.xlsx | A1:H48 | 18 個動態列 |

## 變數與計算

- 共用抬頭欄位使用 `{{camelCase}}` 變數，例如 `{{customerName}}`、`{{quoteNo}}`、`{{quoteDate}}`。
- CNS 與 GENERAL 保留固定品項、規格、單位、單價與備註，只將數量改為 `{{qtyNN}}`。
- MARINE、BLANK、SALES 使用動態品項列，品項名稱、規格、數量、單位、單價與備註均可在產製時帶入。
- 複價與未稅、稅額、含稅總計保留 Excel 公式；公式錯誤掃描結果為 0。
- F7:G8 為固定公司聯絡資訊，不可由執行期變數覆寫。

## 資料庫匯入

`template-analysis.json` 內含全域去重品項（`UPPER_SNAKE_CASE` itemCode）、各方案的 specification、unit、unitPrice、remark、displayOrder、變數位置與公式位置，可作為資料庫匯入來源。

## 圖片與限制

- 每份範本保留 4 個圖形物件（3 個圖片與 1 個文字方塊）；內嵌圖片雜湊與原始 XLSM 相同。
- 使用者回覆圖片的動態插入區仍應由產製程式依實際未使用品項列與空白區判斷，且不得覆蓋公司標誌、蓋章或 F7:G8。
- artifact-tool 對最終 CNS XLSX 的預覽未顯示圖片，但同一份檔案由 Microsoft Excel 重新開啟時 4 個圖形皆存在；原始圖片雜湊、圖形數量與 Excel 顯示結果均已另外核對。
