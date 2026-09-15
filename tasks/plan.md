# 實作計畫：商用機雲端與本地 Docker 雙部署

狀態：Active
核准規格：`CAPABILITY-MAP-cloud-local-deployment.md` 與對應八份 `SPEC-*.md`
舊計畫：已封存至 `tasks/archive/plan-legacy-windows-2026-09-02.md`

## 1. 目標

把商用機器人改為單一 headless Spring Boot／OCI 服務；本地以 Docker Compose 執行，雲端以 Kubernetes 部署。結構化資料改用 PostgreSQL，檔案改用 S3 相容物件儲存，公司資產以不可變版本管理，PDF 改由 LibreOffice headless 產生，並完整移除桌面 App 交付鏈。

## 2. 依賴圖

```text
C1 基線
├─ C2 Headless 啟動
│  ├─ C3 Desktop／Tunnel 移除
│  └─ C4 外部設定與 Secret
├─ C5 PostgreSQL migration
│  ├─ C6 Repository SQL 遷移
│  └─ C7 舊 SQLite 遷移工具
├─ C8 S3 儲存介面
│  ├─ C9 公司資產版本
│  ├─ C10 報價範本／主檔外部化
│  └─ C11 營運檔案遷移
├─ C12 LibreOffice PDF
├─ C13 本地 Docker
├─ C14 Kubernetes 雲端部署
├─ C15 CI／供應鏈
└─ C16 文件與完成性驗證
```

## 3. 分階段工作

### Phase C-A：安全移除桌面邊界

- C1：記錄既有測試、打包與 Docker 設定基線。
- C2：主程式改為唯一 `SpringApplication.run`，加入 graceful shutdown 與 probes。
- C3：移除 desktop packages／tests、JNA、DPAPI、內建 ngrok／cloudflared、Windows packaging／scripts／workflow。
- C4：以 type-safe properties、環境變數與 config tree 建立非桌面設定／Secret 契約。

Checkpoint：非 Windows 環境完成 Maven test／package；repository 無可執行 desktop mode。

### Phase C-B：可攜式資料層

- C5：導入 Flyway 與 PostgreSQL schema，建立資產版本及 migration ledger。
- C6：把 SQLite-specific repository SQL、日期、布林、upsert、流水號與 lease 語意改成 PostgreSQL。
- C7：建立 SQLite＋本機檔案唯讀遷移命令，支援 dry-run、重跑與 SHA-256 報告。
- C8：建立 object storage port 與 S3 實作，統一 key、stream、metadata、staging、promote、delete compensation。
- C9：建立 company asset manifest、stage、validate、approve、activate、rollback 與 audit。
- C10：移除 classpath 公司的 XLSX／固定資料，改由 active asset set 取得並鎖定版本。
- C11：報價圖片、XLSX、PDF 與下載流程改由 object storage，不依賴容器持久目錄。

Checkpoint：PostgreSQL 整合測試、S3 測試、資產原子切換、舊資料 dry-run／migration 全部通過。

### Phase C-C：可攜式 PDF 與部署

- C12：以 LibreOffice headless exporter 取代 PowerShell Excel COM，加入隔離暫存與五範本 smoke test。
- C13：強化 Dockerfile，建立 App＋PostgreSQL＋MinIO＋初始化＋選用 cloudflared 的本地 Compose。
- C14：建立 Kubernetes Deployment、Service、ConfigMap／Secret 範本、probes、resource limits、PDB 與 rollback 說明。
- C15：更新 CI，驗證 Maven、migration、Compose config、Kubernetes schema、SBOM、映像掃描與 digest。

Checkpoint：商用機 `127.0.0.1:8088` 可啟動；App 重建後資料仍在；OCI 映像無公司資產與 Secret。

### Phase C-D：營運文件與驗收

- C16：完成 ADR、部署、資產 SOP、備份／還原、遷移、升級、回復、監控與排錯文件。
- 執行規格逐條完成性稽核，所有不足以證明的項目維持未完成。

## 4. 風險與緩解

| 風險 | 緩解 |
|---|---|
| SQLite 到 PostgreSQL 語意差異造成流水號或冪等失效 | 先建立 dialect tests，再以真實 PostgreSQL integration test 驗證並行 |
| DB 與 S3 無分散式交易 | staging object＋DB 狀態＋補償＋可重跑 reconciliation |
| LibreOffice 與 Excel 排版差異 | 五範本 PDF smoke、固定字型、版本鎖定、保留 XLSX 與可重試狀態 |
| 公司資產切版影響進行中報價 | 報價建立時鎖定 asset set version，active pointer 只影響新工作 |
| 舊資料遷移破壞來源 | 工具唯讀來源、不自動刪除、dry-run 與 hash／count gate |

## 5. 驗證命令

- `./mvnw.cmd test`
- `./mvnw.cmd -DskipTests package`
- `docker compose config`
- `docker compose up --build --wait`
- PostgreSQL／S3 integration tests
- SQLite migration dry-run 與重跑測試
- OCI contents／Secret／公司資產掃描
- Kubernetes manifests server-side 或 schema 驗證
