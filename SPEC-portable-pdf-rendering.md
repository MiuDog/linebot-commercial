# SPEC：Portable PDF Rendering

狀態：Approved，2026-09-02 核准。

## User Story

身為商用機器人使用者，我希望雲端與本地 Docker 都能由同一個 Linux 相容流程將報價 XLSX 轉成 PDF，而不需要 Windows 或 Microsoft Excel。

## Acceptance Criteria

1. 正式 PDF exporter 使用固定參數陣列呼叫 LibreOffice headless，不執行 PowerShell 或 Excel COM。
2. LibreOffice 版本在映像建置時固定，且雲端與本地 Docker 使用相同版本。
3. 每個轉檔工作使用獨立暫存目錄與使用者設定目錄，避免平行工作互鎖。
4. 輸入、輸出檔名由程式產生；不得把使用者文字拼成 Shell 指令。
5. 轉檔有逾時、輸出大小限制、程序終止與可重試錯誤狀態。
6. 五種正式範本各有 XLSX 結構測試及 PDF 渲染 smoke test；固定 Logo、印章、聯絡資訊與合計不得遺失。
7. 轉檔完成後 PDF 上傳物件儲存，再以原本報價狀態機完成交付。

## Out of Scope

- 保證與特定 Microsoft Excel 版本逐像素完全一致。
- 使用雲端 Office 或使用者桌面 Excel。
- 修改既有五種報價計價規則。

## Data Model Changes

- PDF generation job 增加 renderer 名稱與版本。
- 失敗摘要保存受控錯誤碼，不保存完整本機暫存路徑或程序輸出。

## API Changes

既有報價 API 不改變；管理狀態可顯示 renderer 版本及可重試錯誤碼。

## Edge Cases

- LibreOffice 無法啟動、輸入損壞、字型缺失、轉檔逾時或未產生 PDF 時，XLSX 必須保留且工作標記可重試失敗。
- 同一工作重試不得產生新報價號碼。
- 程序被 SIGTERM 中斷時不得發布半成品 PDF。

## Dependencies

`headless-runtime`、`company-asset-contract`、`portable-storage`。
