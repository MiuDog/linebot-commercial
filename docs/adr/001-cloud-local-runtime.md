# ADR-001：Headless、單公司 PostgreSQL／S3 與 LibreOffice

狀態：Accepted（2026-09-02）

## Context

舊版將 Spring Boot、SQLite、本機檔案、Microsoft Excel COM、Tunnel 程序與 Windows 桌面控制器綁在同一個 App。此模式無法安全水平擴展，也會把公司範本與程式發布綁定。

## Decision

- 只保留 headless Spring Boot server；Windows App、installer、IPC、DPAPI 與內嵌 Tunnel 退役。
- 同一份 OCI 映像支援本地 Compose 與標準 Kubernetes。
- 每套部署只服務一個 `companyId`；PostgreSQL 保存結構化資料，S3 相容儲存保存公司資產與產出檔。
- 公司資產以不可變 asset set 經 stage、validation、approval、activation、rollback 管理，報價鎖定版本。
- Linux 以 LibreOffice headless 轉 PDF；主機檔案只作可重建暫存。
- Secret 由 config tree／Secret Manager 注入；Cloudflare Tunnel 是獨立工作負載。

## Consequences

本地環境需同時維護 PostgreSQL 與 MinIO，且舊 SQLite 要經明確遷移。公司資產不再隨程式發布，需有內容 owner 與核准 SOP。交付容器映像仍可能被反編譯；真正不交付程式的要求必須採供應方託管 SaaS，而不是依賴映像隱藏。

