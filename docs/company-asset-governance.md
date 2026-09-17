# 公司資產取得與維護制度（Commercial）

## 正式報價的啟用條件

Docker 健康不代表公司資產已就緒。正式確認前必須有啟用的公司資產版本與該格式的範本登錄；缺少時分別回覆 `QUOTATION_ASSETS_NOT_READY` 或 `QUOTATION_TEMPLATE_NOT_READY`，保留草稿且不配置流水號。

資產 activation／rollback 會在同一交易套用品項 CSV、登錄五種範本並切換啟用版本。範本登錄按資產版本保留歷史識別碼；rollback 重新啟用原登錄，不改寫已確認報價引用的舊範本。舊版程式已啟用但尚未登錄範本的資產，請以相同正式檔案建立並啟用新版本，或回復至已核准的 RETIRED 版本以同步登錄；不要以測試素材取代正式公司的範本、Logo 或印章。

## 先決策：程式與資產分離，不等於映像不可逆向

公司資產不放進 Git 或 OCI 映像，確實能讓程式更新與資產維護分離；但若把 Java 容器映像交付到客戶控制的主機或 Kubernetes，客戶可取得 bytecode，技術上仍可能反編譯。若「程式碼不可交付」是不可妥協條件，應採供應商管理的 SaaS／Kubernetes 帳號，只向公司提供 API、管理介面、稽核與完整資料匯出。若採公司自管雲端或本地 Docker，應把 IP 保護視為授權契約、存取控制與必要混淆的組合，不能宣稱單靠容器就不會透露程式邏輯。

## 所有權與責任

| 項目 | 建議所有人 | 維護責任 |
|---|---|---|
| Logo、印章、Excel 範本、品項、聯絡資料 | 公司 | 公司指定的資產管理人核准內容與版本 |
| 報價、客戶資料、圖片、產出 XLSX／PDF、稽核 | 公司 | 公司決定保存期；維運方執行備份與刪除政策 |
| LINE Channel、網域、TLS 與 Tunnel 身分 | 公司 | 公司保管主帳號；維運方只取得最小權限 |
| 應用程式原始碼與通用演算法 | 軟體供應方（依契約） | 供應方維護、修補及交付映像 digest／SBOM |
| PostgreSQL／Bucket | 優先由公司持有 | 維運方取得單一公司、單一 Bucket 前綴的服務帳號 |

契約至少應列出資料所有權、處理目的、保存期、備份／還原責任、資安事件通報、離場匯出格式、刪除證明、RTO／RPO，以及供應方不得以公司資產訓練模型或轉作他用。

## 資產如何取得

1. 公司指派「內容提供者」與不同人的「核准者」。
2. 內容提供者從公司可證明權利的來源取得原始檔，不從目前程式映像反向抽取作為唯一母檔。
3. 建立一個不可變資產版本，例如 `2026.09.01`，包含：
   - `LOGO`、`SEAL`
   - `TEMPLATE_BLANK`、`TEMPLATE_CNS`、`TEMPLATE_GENERAL`、`TEMPLATE_MARINE`、`TEMPLATE_SALES`
   - `TEMPLATE_DEFINITIONS`、`ITEM_MASTER`、`CONTACT_DATA`
4. 對每個檔案記錄檔名、MIME、byte size 與 SHA-256，產生 manifest。`companyId` 必須與部署設定完全相同。
5. 在非正式環境驗證五種報價、字型、分頁、印章、聯絡資訊及 LibreOffice PDF 結果後才送核准。

Manifest 格式：

```json
{
  "schemaVersion": "1",
  "companyId": "company-id",
  "assetSetVersion": "2026.09.01",
  "objects": [
    {
      "purpose": "LOGO",
      "fileName": "logo.png",
      "contentType": "image/png",
      "size": 12345,
      "sha256": "64-lowercase-hex"
    }
  ]
}
```

## 上線生命週期

管理 API 同時受網路邊界與 `X-Admin-Token` 保護，另要求 `X-Admin-Actor` 留下操作者代號。不要從公開 Internet 直接開放 `/api/admin/**`。

1. `POST /api/admin/company-assets/stages`：multipart 上傳 `manifest` 與全部 `files`；此時即解析 `ITEM_MASTER` CSV，並確認範本定義為 schema `1.1` 且包含五種唯一格式，但不修改正式主檔。
2. `POST /api/admin/company-assets/{version}/validation`：核對檔案型態、大小、magic header 與 SHA-256，並提升為正式不可變物件。
3. `POST /api/admin/company-assets/{version}/approval`：由核准者執行。
4. `POST /api/admin/company-assets/{version}/activation`：在同一資料庫交易中套用該版本 `ITEM_MASTER`，再原子切換目前版本；任一步驟失敗都不生效。
5. `GET /api/admin/company-assets`：稽核各版本狀態。
6. 發現問題時 `POST /api/admin/company-assets/{version}/rollback`，以同一交易恢復舊版 `ITEM_MASTER` 與 active 指標，不得覆寫已啟用版本內容。

每張正式報價在確認時保存 `asset_set_id`。因此新版啟用後，舊報價重試或重新產生仍使用原版本，避免歷史文件被悄悄改版。

## 日常維護

- 修改任何一個檔案都建立新版本，不在原 key 原地覆寫。
- 每次發布保留需求單、提供者、核准者、測試證據、manifest hash、啟用時間與回復結果。
- 每季做一次還原演練；每次啟用前先確認 PostgreSQL 備份及 Bucket versioning／生命週期政策。
- Production App 只取得 `companies/{companyId}/` 所需的讀寫權限；備份帳號、資產核准帳號與執行帳號分離。
- 管理下載不回傳 Bucket、長效 URL 或雲端憑證。
- 公司母檔另存公司文件管理系統；S3 正式物件是營運副本，不是唯一法務原件。

## 雲端服務建議

最平衡的作法是「公司持有資料，供應方持有程式」：公司帳號持有 PostgreSQL、Bucket、KMS key、網域與 LINE Channel；供應方在隔離的執行帳號部署映像，透過工作負載身分取得該公司前綴的最小權限。若供應方完全不應接觸明文資產，需由公司端加密並在公司控制的執行環境解密；這與不交付映像的 SaaS 模式有衝突，必須在契約與威脅模型中選擇信任邊界。

離場時，公司應取得 PostgreSQL 邏輯備份、完整資產與報價物件、manifest／SHA-256 清單及稽核紀錄；驗證可還原後才撤銷服務帳號並依契約取得供應方端刪除證明。
