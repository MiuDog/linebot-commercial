# SPEC：Operations Documentation

狀態：Approved，2026-09-02 核准。

## User Story

身為沒有原始碼的公司維護者，我希望依文件完成部署、公司資產更新、備份、還原、升級及故障處理。

## Acceptance Criteria

1. 新增 ADR 說明取消桌面版、單公司單部署、PostgreSQL、S3 相容儲存及 LibreOffice 的決策與代價。
2. 分別提供本地 Docker 與 Kubernetes 雲端部署指南，所有命令與實際檔名一致。
3. 提供公司資產 SOP：準備 manifest、上傳、驗證、核准、啟用、回復及稽核。
4. 提供 Secret 清單與取得位置，但範例不得包含真實值。
5. 提供 PostgreSQL、物件儲存及 asset manifest 的備份／還原與定期演練方式。
6. 提供 SQLite／本機檔案到新架構的遷移、核對與回復步驟。
7. 提供監控、常見錯誤、LINE webhook、儲存、PDF renderer 與 Tunnel 排錯指南。
8. 文件索引不得再把 Windows App、Setup.exe 或內建 Tunnel 列為現行部署方式。

## Out of Scope

- 特定公司的真實帳號、Bucket、Domain 或憑證值。
- 雲端供應商採購與費率比較。

## Data Model Changes

無。

## API Changes

無。

## Edge Cases

- 指令需同時標註 PowerShell 與跨平台替代方式，或明確限定執行環境。
- 所有破壞性還原操作必須先要求備份並驗證目標 deployment／companyId。

## Dependencies

所有能力模組。
