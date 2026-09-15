# 任務清單：商用機雲端與本地 Docker 雙部署

目前阻塞：最終映像掃描仍找到 `src/main/resources/quotation/template-definitions.json`，且 PostgreSQL `V1__baseline.sql` 仍含公司專屬範本定位與品項／價格 seed；Git 也仍追蹤 `outputs/excel-templates/**`。完成 C10、Checkpoint C-C 與全部 SPEC 驗收前，必須先取得公司資產刪除／外移授權。不得以忽略掃描或只改副檔名方式關閉此項。

## Phase C-A：Headless runtime

- [x] C1：保存基線測試、package、目前 schema 與資料路徑證據。
- [x] C2：主程式只保留 Spring Boot server 啟動。
- [x] C2：加入 liveness、readiness 與 graceful shutdown 設定／測試。
- [x] C3：刪除 production／test desktop packages 與 desktop resource。
- [x] C3：移除 JNA、DPAPI、IPC、ngrok、cloudflared runtime 相依。
- [x] C3：刪除 Windows packaging、installer scripts 與 release-windows workflow。
- [ ] C4：建立 type-safe runtime、database、object storage、company、admin 設定。
- [ ] C4：支援 `/run/secrets` config tree，必要 Secret 缺漏時安全失敗。
- [ ] Checkpoint C-A：Maven test／package 通過且無可執行 desktop mode。

## Phase C-B：資料與公司資產

- [ ] C5：加入 PostgreSQL driver、Flyway 與第一版 PostgreSQL migration。
- [ ] C5：建立 company asset set／object／activation／audit／migration ledger schema。
- [ ] C6：遷移所有 repository 的 SQLite-specific SQL。
- [ ] C6：並行流水號、job lease、webhook 冪等與 transaction 測試通過。
- [ ] C7：建立 SQLite＋本機檔案 migration dry-run。
- [ ] C7：完成不刪來源的 copy、hash／count gate、重跑與報告。
- [ ] C8：建立 ObjectStorage port、S3 實作與安全 object key policy。
- [ ] C8：完成 staging、promote、stream、metadata、delete compensation 與整合測試。
- [ ] C9：完成 asset manifest parse／schema／hash／用途驗證。
- [ ] C9：完成 stage、approve、activate、rollback 與稽核 API。
- [ ] C9：管理入口具備管理憑證及私有網路限制。
- [ ] C10：五份 XLSX、定義、Logo／印章／固定聯絡資料改由 active asset set 取得。
- [ ] C10：進行中報價鎖定 asset set version。
- [ ] C11：報價圖片、XLSX、PDF、下載及 archive 改用 object storage。
- [ ] Checkpoint C-B：PostgreSQL、S3、資產切版與 migration 驗證通過。

## Phase C-C：PDF 與部署

- [ ] C12：實作 LibreOfficePdfExporter 並移除 PowerShellExcelPdfExporter。
- [ ] C12：隔離暫存、逾時、程序終止、受控錯誤與重試測試通過。
- [ ] C12：五種範本 XLSX／PDF smoke 驗證通過。
- [ ] C13：Dockerfile 使用非 root、固定基底、LibreOffice 與唯讀 root 相容目錄。
- [ ] C13：Compose 提供 App、PostgreSQL、MinIO、初始化與選用 cloudflared。
- [ ] C13：商用機固定主機 `127.0.0.1:8088`，不使用 `container_name`。
- [ ] C13：Secret file、volume、health、restart 與資料持久化測試通過。
- [ ] C14：建立 Kubernetes Deployment／Service／ConfigMap／Secret 範本／PDB。
- [ ] C14：probes、resources、securityContext、rolling update 與 digest 規則完整。
- [ ] C15：CI 驗證 Maven、migration、Compose、Kubernetes、SBOM、scan、digest。
- [ ] Checkpoint C-C：本地重建不失資料；OCI 不含 Secret／公司資產。

## Phase C-D：文件與完成性

- [ ] C16：ADR 說明桌面退役、單公司部署、PostgreSQL、S3、LibreOffice。
- [ ] C16：本地 Docker 與 Kubernetes 部署文件完成。
- [ ] C16：公司資產準備／驗證／核准／啟用／回復 SOP 完成。
- [ ] C16：備份、還原、遷移、升級、rollback、監控與排錯文件完成。
- [ ] C16：文件索引及所有舊桌面說明已更新或標記歷史。
- [ ] 完整 `mvn test`、package、Docker、migration、資產與部署驗證通過。
- [ ] 對八份核准 SPEC 逐條建立直接證據，無未完成要求。

## 2026-09-10 公司資產外移追加驗收

- [x] 初次 22 份公司檔案保全並核對 SHA-256，另保全原始 XLSM。
- [x] 原始 XLSX／預覽／報告／範本定義／XLSM 從受追蹤工作路徑移除；Git 歷史未改寫。
- [x] 正式 V1 移除公司範本與價目 seed；测试改用獨立生成的 XLSX 與虛構價目。
- [x] 商用專案 `mvnw.cmd clean verify`：358 tests、0 failures、0 errors、0 skipped。
- [x] 新映像 `linebot-commercial:asset-free` 建置完成；本機及映像內 JAR 均不含公司範本資源或價目 seed。
- [x] 新映像 ID：`sha256:ceb1c77fd72c234c204fba03b2ef965c7774888cc0bad734be026926ea07fc40`。
- [x] 文書專案同步私有資產包 Git／Docker 排除規則與 README 驗收狀態。
- [ ] 公司確認完整十用途營運匯入包；目前只有原始檔保全包，未核准啟用。
- [ ] 新版 Docker 的真實 LINE 對話、產出開啟與截圖：尚需可操作的已登入聊天室與測試 Channel 設定。
- [ ] 檢查截圖無私人資料後上傳 GitHub，並在 README 嵌入。GitHub 已確認登入有效，目前未上傳任何截圖。
- [ ] 舊映像／建置快取／安裝程式／遠端 Release 尚未清除；不能視為新映像排除驗證的一部分。
