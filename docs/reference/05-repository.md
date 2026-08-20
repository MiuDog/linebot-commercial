# Repository

[← 回索引](index.md)

資料庫存取。**只有這一層寫 SQL**，其他層看到的是 `Asset` 與 Java 型別。

---

## `AssetRepository`

`dev.myudog.assetsmanagerlinebot.repository.AssetRepository`

**職責**：資產索引的唯一資料庫出入口。

| 方法 | 說明 |
|---|---|
| `Long insert(Asset asset)` | 寫入新索引，回傳流水號。 |
| `Optional<Asset> findByMessageId(String messageId)` | 以 LINE 訊息 id 取出，**含標籤**。 |
| `Optional<Asset> findByShareToken(String shareToken)` | 以對外權杖取出，不含標籤（取圖端點用不到）。 |
| `long upsertTag(String name)` | 取得或建立標籤 id。 |
| `void linkTag(long assetId, long tagId)` | 建立關聯，重複掛不會出錯。 |
| `List<String> findTagNames(long assetId)` | 取出標籤，**順序即掛上順序**。 |
| `List<Asset> searchByTags(String sourceId, List<String> tags, int limit)` | AND 語意查詢。 |
| `Map<String,Integer> tagCounts(String sourceId)` | 標籤數量統計。 |
| `int countBySource(String sourceId)` | 收錄總數。 |
| `Asset withTags(Asset asset)` | private，補上標籤欄位。 |
| `static Asset mapAsset(ResultSet rs, int rowNum)` | private，單列轉 `Asset`。 |

### 為什麼用 JdbcClient 而非 JPA

資料模型固定、查詢型態少（就是「依標籤找資產」這一種）。直接寫 SQL 比維護 entity 對應更好讀，AND 語意那段查詢用 JPQL 表達反而更繞。

### AND 語意怎麼做到

```sql
WHERE a.source_id = ? AND t.name IN (?, ?, ...)
GROUP BY a.id
HAVING COUNT(DISTINCT t.name) = ?
```

命中的**標籤種類數**必須等於使用者給的關鍵字數量。用 `DISTINCT` 是必要的——沒有它，同一個標籤被重複掛時計數會虛胖，變成只符合一個標籤的資產也被撈出來。

### 標籤順序有意義

`findTagNames` 的 `ORDER BY at.rowid` 保證回傳順序等於掛上的順序，因此**第一個標籤就是主要的資產編號**。`Asset.primaryTag()` 與這個順序是一組的，改動其中一邊必須同時檢查另一邊。

### `wasNull()` 的必要性

`mapAsset` 讀 `file_size` 時必須用 `rs.wasNull()` 判斷，否則 SQL NULL 會被 `getLong` 悄悄讀成 `0`——一個永遠不會拋錯、但資料默默錯掉的經典陷阱。

### 資料表結構

定義於 `src/main/resources/schema.sql`，以 `CREATE TABLE IF NOT EXISTS` 撰寫，配合 `spring.sql.init.mode=always` 每次啟動執行。

```
asset       (id, message_id⚿, share_token⚿, source_type, source_id,
             uploader_id, file_path, content_type, file_size, created_at)
tag         (id, name⚿)
asset_tag   (asset_id→asset, tag_id→tag)   PK(asset_id, tag_id), ON DELETE CASCADE
```

索引：`idx_asset_source(source_id)`、`idx_asset_tag_tag(tag_id)`

> **`file_path` 寫入後不會再變**：檔案落地之後不搬動，因此沒有更新路徑的方法。若未來要支援搬遷，記得同時處理「搬到一半失敗」的狀態。

> **變更資料表時**：目前沒有 migration 工具。欄位異動需自行寫 `ALTER TABLE` 並確保對既有 `assets.db` 可重複執行。若異動變頻繁，應導入 Flyway。

---

## `QuotationAdminRepository`

`dev.myudog.assetsmanagerlinebot.repository.QuotationAdminRepository`

報價方案、共用品項、方案固定資料與稽核紀錄的 SQLite 出入口。所有異動均使用參數化 SQL；
方案範本就緒狀態以 `EXISTS` 查詢，避免未來加入多版本範本後重複方案列。批次查詢與安全更新
範例另見 [報價資料庫安全讀寫](../07-quotation-database-operations.md)。
