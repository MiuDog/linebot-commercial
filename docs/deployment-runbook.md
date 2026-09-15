# Commercial 部署與維運 Runbook

## 本地 Docker

### 首次啟動

1. 安裝 Docker Engine／Docker Desktop 與 Compose v2。
2. 複製 `.env.example` 為 `.env`，設定公司 ID、公開 HTTPS 網址與 Bucket 名稱；`.env` 不放密碼。
3. 依 `secrets/README.md` 建立 Secret 檔案，限制只有部署帳號可讀。
4. 執行 `docker compose config --quiet`，確認設定可解析。
5. 執行 `docker compose up --build --wait`。
6. 確認 `docker compose ps` 的 database、object-storage、app 均健康，且 `http://127.0.0.1:8088/readyz` 成功。
7. 上傳並啟用第一個公司資產版本，再進行五種報價及 PDF 驗收。
8. 將 LINE Webhook 設為 `{PUBLIC_BASE_URL}/callback` 並按 Verify。

Compose 不暴露 PostgreSQL 或 MinIO 埠；App 只綁主機 loopback。需要 Tunnel 時使用 `docker compose --profile tunnel up --build --wait`，Commercial 與 Document 必須使用不同 Tunnel Token／hostname。

### 備份與還原

- PostgreSQL：每日用 `pg_dump --format=custom` 產生加密備份，備份檔移至不同故障域；每季以全新資料庫執行 `pg_restore` 演練。
- MinIO／S3：啟用 bucket versioning，另做跨儲存或離線複寫；備份必須包含所有 `companies/{companyId}/` 物件版本與 metadata。
- 一致性：記錄資料庫備份時間、Bucket inventory／version marker、映像 digest 與公司資產啟用版本。
- 還原：先還原 PostgreSQL 與物件，再使用相同或相容映像啟動；`readyz` 成功後抽查資產 hash、舊報價 XLSX／PDF 與限時下載。

### 升級與回復

1. 讀取 release notes 與 migration 相容性，完成資料與物件備份。
2. 先在 staging 使用正式資產副本驗證 Flyway、五種 XLSX、LibreOffice PDF 與 LINE sandbox。
3. 將 `APP_IMAGE` 改成不可變版本／digest，執行 `docker compose up -d --wait app`。
4. 觀察 readiness、5xx、LINE webhook、工作 lease、PDF 失敗率與 S3 錯誤。
5. 如需回復，只能切回與目前 schema 相容的舊映像；不要執行 Flyway downgrade。遇到不可相容 migration 時依 release 專用 forward-fix 執行。

## Kubernetes／雲端

1. 由公司或受託管理帳號建立 PostgreSQL、S3 Bucket、KMS、DNS／TLS 與監控；禁止把雲端憑證提交到 Git。
2. 依 `k8s/secret.example.yaml` 在 Secret Manager／部署管線建立 `linebot-commercial-secrets`。使用 workload identity 時，從 Deployment 移除 S3 access key items。
3. 修改 ConfigMap 的 companyId、資料庫、Bucket、region、public URL；將 Deployment 映像替換成 CI 產出的 `repository@sha256:...`。
4. 先執行 `kubectl kustomize k8s/base`，再於 staging namespace 套用。
5. Ingress 只公開 `/callback`、媒體與下載所需路徑；`/api/admin/**` 僅能由 VPN／零信任閘道存取，且仍須 `X-Admin-Token`。不要公開 `/admin` 靜態頁。
6. 等待 rollout 與 readiness，完成公司資產初始化、LINE Verify 及端到端測試後再切 DNS。

Deployment 使用非 root、唯讀 root filesystem、`/tmp` emptyDir、資源限制、滾動更新與 PDB。預設一個 replica；擴容前必須保留 PostgreSQL 唯一鍵與資料庫 lease，不得改回程序內單例鎖。

## 監控與事故

- Liveness：`/livez`；readiness：`/readyz`。
- 必看：HTTP 5xx、LINE 401／429／timeout、DB 連線池、Flyway、S3 timeout、quotation job lease、LibreOffice timeout／PDF_FAILED、磁碟／tmpfs、JVM heap。
- 收到 SIGTERM 時給至少 40 秒 graceful shutdown。
- Secret 洩漏時先輪替 LINE／管理／S3／DB 憑證，再重啟 workload；不要把 Secret 值貼進工單或 Log。
- S3 或 DB 故障時讓 readiness 失敗並停止切流，不用空資料庫強行啟動。

## 客戶診斷判讀

雙擊 `linebot.cmd` 並選擇「連線與環境診斷」。請由第一個「失敗」項目開始處理；後續失敗可能只是連鎖結果。

| 階段 | 代表意義 | 常見原因與處理 |
| --- | --- | --- |
| Docker 引擎 | 本機容器環境可用 | 開啟 Docker Desktop，等候 Engine running；仍失敗時重新啟動 Docker Desktop。 |
| 設定完整性 | `.env` 與 Secret 可安全啟動 | 回到「首次設定／修改設定」；不要把 Token 貼到工單或 Log。 |
| Docker Compose 設定 | 部署檔可正確展開 | 確認專案檔案未移動、`.env` 每行只有一個 `KEY=VALUE`。 |
| 本機服務 | App、PostgreSQL 與物件儲存已就緒 | 先選擇「啟動服務」，再從 App 紀錄第一個 `ERROR` 與 `requestId` 查起。 |
| Tunnel 容器 | 本產品管理的 Connector 正在執行 | 只有設定為由本產品啟動 Tunnel 時才檢查；Document 與 Commercial 各用獨立 Token、hostname 與 Compose project。 |
| Cloudflare 邊緣連線 | Connector 可穿過 VPN／防火牆 | 請網管允許 TCP 7844 與 `*.argotunnel.com`；公司 VPN 環境保留 `CLOUDFLARED_PROTOCOL=http2`。 |
| 公開網域 DNS | hostname 已指向 Cloudflare | 檢查 Cloudflare DNS 與 Tunnel Public Hostname 拼字；DNS 未通過時 HTTPS 會自動略過。 |
| 公開網域 HTTPS | LINE 可由外網接觸 App | 404 表示路由到錯誤服務、502 表示 Cloudflare 到 App 不通、403 通常是 Access 規則阻擋 Webhook。 |
| LINE API | Token 有效且主機可連 LINE | 401 重新發行 Token；403 檢查 Messaging API Channel 權限；429 等候後再測；網路錯誤請檢查 VPN、Proxy 與 TLS 解密。 |

內建 Tunnel 的 Public Hostname 服務 URL 為 `http://app:8088`。若公司統一管理外部 Connector，請依其所在位置路由到本機 `127.0.0.1:8088`，並保持 `TUNNEL_ENABLED=false`；此時控制台只略過內建 Connector，仍會驗證公開 DNS 與 HTTPS。

## 發布證據

每次發布保存 commit、測試結果、SBOM、掃描結果、映像 digest、Flyway 版本、資產版本、rollout 記錄、驗收者及回復決策。
