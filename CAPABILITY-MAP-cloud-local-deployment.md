# Capability Map：雲端與本地 Docker 雙部署

狀態：已核准能力切分，詳細需求由同目錄的 `SPEC-*.md` 定義。

## 能力模組

| Module id | 責任 | Depends on |
|---|---|---|
| `headless-runtime` | 將應用程式收斂為單一無介面的 Spring Boot 服務，不再管理桌面視窗、安裝程式或 Tunnel 子程序 | — |
| `company-asset-contract` | 定義單一公司的資產包、版本、完整性驗證、啟用、回復與權限邊界 | — |
| `portable-storage` | 以 PostgreSQL 保存結構化資料、以 S3 相容物件儲存保存公司與營運檔案，並提供舊資料遷移 | `company-asset-contract` |
| `portable-pdf-rendering` | 以 Linux 可執行的 LibreOffice headless 取代 Windows Excel COM PDF 匯出 | `headless-runtime`, `company-asset-contract` |
| `local-docker` | 提供可在公司電腦執行的 App、PostgreSQL、物件儲存與選用 Cloudflare Tunnel 組合 | `headless-runtime`, `portable-storage`, `portable-pdf-rendering` |
| `cloud-deployment` | 提供 OCI 映像與 Kubernetes 部署契約，使用外部 Secret、PostgreSQL 與物件儲存 | `headless-runtime`, `portable-storage`, `portable-pdf-rendering` |
| `desktop-retirement` | 移除 Swing、DPAPI、JNA、IPC、內建 ngrok／cloudflared、jpackage、NSIS 與 Windows Release 流程 | `headless-runtime`, `local-docker`, `cloud-deployment` |
| `operations-docs` | 建立架構決策、部署、資產維護、備份、遷移、升級、回復及事故處理文件 | 所有模組 |

## 建置順序

```text
headless-runtime ─┬─> portable-pdf-rendering ─┬─> local-docker ─┐
                  │                           └─> cloud-deployment ─┼─> desktop-retirement ─> operations-docs
company-asset-contract ─> portable-storage ───┴───────────────────┘
```

## 共用邊界

- 每一套部署只服務一家公司；不同公司不得共用資料庫、Bucket prefix 或 Secret。
- 同一個 OCI 映像同時供雲端與本地 Docker 使用，差異只存在於外部設定與基礎設施。
- 映像、JAR 與 Git repository 不得包含公司 Logo、印章、報價範本、主檔或正式營運資料。
- 公開 HTTP 入口只提供 LINE webhook、必要媒體下載及健康檢查；管理操作使用獨立路由與權限。
- 桌面版規格保留作為歷史紀錄，但狀態改為 `Superseded`，不得再作為現行驗收依據。

