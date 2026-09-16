# LINE Bot Commercial

商用報價機器人目前只提供兩種部署：本地 Docker Compose 與標準 Kubernetes。Windows 桌面 App、安裝程式、內嵌 Tunnel 及 SQLite 正式執行模式均已退役。

## 架構

Commercial 負責 LINE 報價草稿、確認、計價與 XLSX／PDF 交付；Document 負責圖片歸檔、查詢與資產匯入／匯出。兩者是獨立服務，不會因同時啟動就自動同步圖片或公司資產。

- Java 25／Spring Boot 無介面服務，固定容器埠 `8088`。
- PostgreSQL 保存業務資料與狀態；Flyway 管理 schema。
- S3 相容物件儲存保存公司資產、圖片、XLSX 與 PDF。
- LibreOffice headless 在 Linux 容器中將 XLSX 轉成 PDF。
- 公司 Logo、印章、Excel 範本、品項與聯絡資料不進 Git、不進映像，以版本化資產包維護。
- Cloudflare Tunnel 是獨立容器或平台元件，App 不持有 Tunnel Token 生命週期。

## Windows 客戶操作

1. 安裝並開啟 Docker Desktop，等候畫面顯示 Engine running。
2. 雙擊 `linebot.cmd`，選擇「首次設定／修改設定」。
3. 依畫面填入公司代碼、Cloudflare 公開網址、LINE 憑證與選用 AI 設定；資料庫、物件儲存、報價簽章及管理用 Secret 會自動安全產生。
4. 設定顯示通過後，選擇「啟動服務」。控制台會等待 PostgreSQL、物件儲存與 App 全部健康才顯示完成。
5. 遇到收不到訊息、報價失敗、下載網址無法開啟或 Tunnel 異常時，先選擇「連線與環境診斷」。診斷會分開檢查 Docker、設定、本機服務、公開網域 DNS／HTTPS 與 LINE API，並在失敗階段列出原因和解法。

「停止服務」不會刪除資料卷；不要在 Docker Desktop 手動刪除 database-data 或 object-storage-data。需要交付工程人員時，選擇「查看 App 紀錄」，記下問題發生時間與第一個 `ERROR` 的 `requestId`，不要傳送 Token、客戶報價內容或 Secret 檔案。

## Docker Compose 部署

以下命令在本專案根目錄執行。需要 Docker Engine／Docker Desktop（Linux containers）與 Compose v2；映像內會建置 Java，主機不需先安裝 Java、Maven 或 Microsoft Excel。

### 1. 準備設定與 Secret

Windows 可先執行以下命令，保留既有設定、移轉既有憑證並自動產生缺少的內部密碼：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare-local-settings.ps1
```

此工具不會申請 LINE／AI／Tunnel 憑證。執行後雙擊 `linebot.cmd` →「首次設定／修改設定」補上外部資料；空白檔案會提示輸入。請勿把 Secret 貼到聊天或提交 Git。

首次設定才複製 `.env.example` 為 `.env`（PowerShell：`Copy-Item .env.example .env`；Linux／macOS：`cp .env.example .env`）。已有設定時直接編輯，避免覆蓋。

- 設定 `COMPANY_ID`、`PUBLIC_BASE_URL`（LINE 能存取的 HTTPS 網址）、獨立的 `OBJECT_STORAGE_BUCKET`。
- 預設 `APP_PORT=8088`，只影響主機埠；容器內仍是 `8088`。
- 自然語言報價需同時設定 `AI_API_URL`、`AI_MODEL` 與 `secrets/ai-api-key`。API URL 可使用含版本前綴的基底網址或完整 `/chat/completions` 端點。不啟用 AI 時保留空金鑰檔，但無法使用 AI 解析。
- 依 [Secret 清單](secrets/README.md) 建立全部必要檔案；`.env` 只放非機密設定。Linux 主機須讓容器 UID `10001` 可讀掛載的 Secret，並限制其他使用者存取。
- Windows 可使用 `linebot.cmd` 的首次設定功能自動產生內部 Secret。

### 2. 建置與確認健康

```text
docker compose config --quiet
docker compose up --build --wait
docker compose ps
curl --fail http://127.0.0.1:8088/readyz
```

PowerShell 若 `curl` 是別名，使用 `curl.exe`。`database`、`object-storage`、`app` 應為 healthy；`object-storage-init` 是一次性建 Bucket 工作，正常結束為 Exited (0)。首次啟動會下載映像與 Maven 相依套件，需要網路。`readyz` 成功只代表相依服務可用，仍須啟用公司資產並完成實際報價驗收。

### 3. 公開 HTTPS 與 LINE Webhook

使用 Compose 內的 Cloudflare Tunnel 時，建立 `secrets/cloudflared-token`，將該 Tunnel 的 Public Hostname 路由到 `http://app:8088`，再執行：

```text
docker compose --profile tunnel up --build --wait
docker compose logs --tail 100 tunnel
```

`TUNNEL_ENABLED=true` 控制 Windows 控制台；手動 Compose 仍須指定 `--profile tunnel`。外部 Connector 的目的地依其網路位置設定，主機上的 Connector 可使用 `http://127.0.0.1:8088`。不要將另一個容器的 `localhost` 當作 App。

將 LINE Messaging API Webhook URL 設為 `{PUBLIC_BASE_URL}/callback`，啟用 Webhook 並執行 Verify。管理路徑 `/admin`、`/api/admin/**` 不應對外公開。啟用公司資產後，實測 LINE 草稿 → 完整預覽 → 確認 → XLSX／PDF → 下載連結。

### 4. 紀錄、停止與更新

```text
docker compose logs --tail 100 app
docker compose --profile tunnel stop
docker compose --profile tunnel down
```

`stop` 停止容器；`down` 移除容器及網路，但保留命名資料卷。**不要使用 `down -v`，它會刪除 PostgreSQL 與物件儲存資料卷。** 更新前先備份 PostgreSQL 與 Bucket；原始碼部署重新執行 `up --build --wait`。使用發布映像時設定 `APP_IMAGE=repository@sha256:...`，執行 `docker compose pull app`、`docker compose up --no-build -d --wait app`，並依 [部署 Runbook](docs/deployment-runbook.md) 檢查 schema 相容性。

### Windows 維運入口

1. 建議先以 `linebot.cmd Validate` 執行與客戶控制台相同的完整前置驗證。
2. 只有自動化部署才手動複製 `.env.example` 與建立 [`secrets/README.md`](secrets/README.md) 所列 Secret。
3. 執行 `docker compose up --build --wait`。
4. 確認 `http://127.0.0.1:8088/readyz` 回應成功。
5. 需要 Cloudflare Tunnel 時改執行 `docker compose --profile tunnel up --build --wait`。

商用與文書專案可同時啟動；文書機預設使用 `127.0.0.1:8089`，兩者有各自的 Compose project、資料庫、Bucket 與 Tunnel。

## 公司資產首次上線

### 檔案維護契約

- 品項、別名、規格、單位與單價：匯出 **UTF-8 CSV**，以 Excel／試算表軟體編輯，再驗證及匯入。
- 報價版面、合併儲存格、圖片位置與列印設定：維護 **XLSX 範本及 TEMPLATE_DEFINITIONS 檔案**；CSV 不保存版面、圖片或公式。
- 正式發布將上述檔案放入新版公司資產包，經 stage → validation → approval → activation。資料庫是執行時資料來源，母檔由公司保管。
- **網頁逐筆編輯品項或報價格式已撤銷為產品需求。** 現有 `/admin/` 表單與逐筆寫入 API 尚未移除，屬待退役實作；不作為日常維護或新版驗收方式。查看報價、預覽及失敗重試仍屬維運功能。

詳細欄位、匯入限制與既有 API 邊界見 [CSV／範本操作](docs/07-quotation-database-operations.md)。

正式服務啟動後，以受保護管理 API 上傳 manifest 與檔案，依序執行 stage、validation、approval、activation。報價在確認時鎖定資產版本，之後啟用新版不會改變舊報價。

資產所有權、交付責任、維護與離場匯出請先閱讀 [`docs/company-asset-governance.md`](docs/company-asset-governance.md)。實際啟動、備份與更新步驟見 [`docs/deployment-runbook.md`](docs/deployment-runbook.md)。

## 報價穩定性與低 token

新增選用的 [多模型 workflow](docs/quotation-model-workflow.md)：圖片觀察、文字抽取與一次升級可各自使用不同模型／端點，具整輪預算與持久檢查點。`AI_WORKFLOW_ENABLED=false` 預設保留原單模型行為；設定為 `true` 才啟用。CSV、計價、預覽、確認與產檔不變。

已加入 strict JSON Schema、依格式縮小品項目錄、最多一次契約修復，以及截斷／拒絕分類。普通文字修正不會重送全部圖片，計價與確認仍由程式執行。`AI_MAX_COMPLETION_TOKENS` 預設 4000，應依截斷率及用量調整。

重複報價可在 LINE 私訊直接上傳 [報價輸入 CSV](docs/quotation-input-csv.md)，不使用 AI 抽取，仍走草稿、預覽及確認。此 CSV 與維護品項的主檔 CSV 不同。改善內容與後續量測見 [低 token 報價穩定性](docs/quotation-ai-reliability.md)；尚未宣稱真實模型成功率提升幅度。

## 開發與驗證

公司原始檔已移至 Git／Docker 忽略的本地保全包，詳見 [外移與復原紀錄](docs/company-asset-externalization.md)。Git 歷史與既有舊版產物尚未清除。

真實 LINE 對話與生成結果的截圖範例正在準備，進度與驗收步驟見 [實機範例](docs/examples/README.md)；尚未完成前不以模擬圖片代替。

測試輸入已備妥：[五種報價格式測試套件](docs/examples/quotation-test-pack/README.md)，包含文字腳本、38 份 CSV、6 張合成圖片與 60 項功能案例。可執行 `./mvnw.cmd -q -Dtest=QuotationExamplePackTest test` 驗證範例檔案與預期計價。

```text
mvnw.cmd clean verify
docker compose config --quiet
kubectl kustomize k8s/base
```

正式部署必須使用 CI 產出的映像 digest，不使用 `latest`。Kubernetes 範本位於 [`k8s/base`](k8s/base)。

## 文件

- [`docs/deployment-runbook.md`](docs/deployment-runbook.md)：本地與雲端部署、升級、回復、備份。
- [`docs/company-asset-governance.md`](docs/company-asset-governance.md)：公司資產取得與維護制度。
- [`docs/migration-runbook.md`](docs/migration-runbook.md)：舊 SQLite／本機檔案切換。
- [`SPEC-cloud-deployment.md`](SPEC-cloud-deployment.md) 與其他根目錄 SPEC：已核准架構契約。
