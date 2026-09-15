# SPEC：Cloud Deployment

狀態：Approved，2026-09-02 核准。

## User Story

身為雲端維護者，我希望把同一個商用機 OCI 映像部署到標準 Kubernetes，並使用外部資料庫、物件儲存及 Secret，而不把公司資產或原始碼交付給客戶。

## Acceptance Criteria

1. 產出單一 Linux OCI 映像，使用固定基底映像版本、非 root 使用者及唯讀 root filesystem 相容設定。
2. Kubernetes manifests 提供 Deployment、Service、ConfigMap、Secret 範本、liveness、readiness、資源限制與 Pod disruption 設定。
3. 映像不得包含公司資產、正式設定、Secret、SQLite、輸出報價或 Log。
4. PostgreSQL、S3 endpoint、Bucket、companyId 與公開網址皆由外部設定注入；Secret 由掛載檔案／Secret Manager 提供。
5. App 至少支援一個 replica；需要背景 singleton 語意的工作以資料庫 lease 協調，不依賴程序內鎖。
6. 部署文件包含 DNS／TLS／LINE webhook、資產初始化、滾動升級、回復及監控步驟。
7. CI 可建置、測試、產生 SBOM、掃描映像並輸出 digest；部署使用 digest，不使用浮動 `latest`。

## Out of Scope

- 綁定特定 AWS、Azure、GCP 或 Cloudflare 帳號。
- 由 repository 自動建立付費雲端資源。
- 多區域主動式容錯。

## Data Model Changes

無雲端專用 schema；使用 `portable-storage` 的共同 migration。

## API Changes

無雲端專用業務 API。

## Edge Cases

- 滾動升級期間新舊版本短暫並存時，schema 必須 backward compatible，工作 lease 不得重複執行。
- readiness 失敗的 Pod 不得接收 webhook。
- 回復映像不得自動執行不可逆 downgrade；資料 migration 需明確相容策略。

## Dependencies

`headless-runtime`、`portable-storage`、`portable-pdf-rendering`。
