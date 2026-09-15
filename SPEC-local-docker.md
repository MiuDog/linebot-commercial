# SPEC：Local Docker Deployment

狀態：Approved，2026-09-02 核准。

## User Story

身為公司內部維護者，我希望用 Docker Compose 在單一主機啟動商用機器人及其必要服務，並能與文書機器人同時運行。

## Acceptance Criteria

1. 商用機主機入口預設為 `127.0.0.1:8088`，不得與文書機預設 `8089` 衝突。
2. Compose 至少包含 App、PostgreSQL、S3 相容物件儲存及一次性初始化／migration。
3. 不使用固定 `container_name`；兩個專案可依 Compose project name 隔離並同時啟動。
4. 資料庫及物件儲存不預設暴露至區域網路，所有持久資料使用 named volume 或明確 bind mount。
5. Secret 以 Compose secret file 掛載；`.env` 只保存非機密設定與 secret file 路徑。
6. 提供選用 `tunnel` profile 執行獨立 cloudflared 容器；App 不管理其程序或 Token。
7. `docker compose up --build --wait` 後健康檢查通過，重建 App 容器不遺失資料。
8. 提供備份、還原、升級與舊資料遷移指令文件。

## Out of Scope

- 桌面視窗、安裝程式及自動修改 LINE Developers Console。
- 在 Compose 內提供正式 TLS 憑證終止；公開入口由 Tunnel 或公司反向代理負責。

## Data Model Changes

沿用 `portable-storage` 所定義的 PostgreSQL schema 與物件格式。

## API Changes

無本地專用業務 API；只新增健康與受保護管理入口。

## Edge Cases

- Secret file 缺漏、權限錯誤或空白時 App 必須 fail fast。
- App 重建、主機重啟及 Tunnel 重連不得產生重複 webhook 處理。
- PostgreSQL 或物件儲存尚未 ready 時 App readiness 維持失敗並有限重試。

## Dependencies

`headless-runtime`、`portable-storage`、`portable-pdf-rendering`。
