# 公司資產外移紀錄

## 範圍與復原

2026-09-10 依授權將公司母檔移至 `company-assets-local/initial/`。此目錄由 Git 與 Docker 忽略，不得強制加入版控或公開上傳。

- 原始五種 XLSX、預覽圖、提取報告與範本定義已保全，`preservation-manifest.json` 記錄初次 22 個檔案的大小與 SHA-256。
- 原始 XLSM 另保存在 `initial/templates/`。
- 原本包含公司主檔的 PostgreSQL baseline 與 SQLite 測試 schema 亦已保全；正式 baseline 不再內建公司範本與價目。
- 測試 XLSX 由 `scripts/generate-test-templates.py` 獨立生成，僅包含虛構聯絡資訊與單色測試像素；測試價目依品項順序產生，不再取自公司提取報告。

這是**保全包**，不是已完成核准的營運匯入包。正式匯入前仍須由公司確認 Logo、印章、聯絡資料與價目，依 [資產治理流程](company-asset-governance.md) 建立完整十用途 manifest，完成驗證與核准。不要把保全 SQL 直接套回正式資料庫。

復原時先核對保全清單雜湊，只把需要的母檔交付公司管理人；不要還原到 `src/main/resources` 或 Docker build context。被 Git 忽略不等於加密或備份，請另存公司控制且有權限管理的儲存位置。

## 相容性與保留項目

Git 歷史未改寫；既有歷史提交仍可能取得舊資產。舊安裝程式、既有 Docker 映像／快取、遠端 Release 以及已安裝的桌面 App 不會因重新建置而自動清除，不得視為已完成歷史資料清除。

新版 baseline 供新部署使用。若曾執行舊版 Flyway V1，原資料庫會有 checksum 差異：先備份並由維運人員規劃遷移；不可直接執行 `repair` 掩蓋差異，也不可刪除正式 volume 解決。

## 驗證

```text
mvnw.cmd clean verify
docker build -t linebot-commercial:asset-free .
```

`CompanyAssetPackagingContractTest` 防止正式 resources 再內建公司 XLSX／XLSM、範本 JSON 或價目 seed，並檢查私有資產包的排除設定。封裝檢查與 LINE 真實端到端驗證是不同層級，不能互相代替。
