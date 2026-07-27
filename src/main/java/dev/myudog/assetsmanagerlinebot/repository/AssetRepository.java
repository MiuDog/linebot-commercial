package dev.myudog.assetsmanagerlinebot.repository;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【職責】資產索引的唯一資料庫出入口。
 *
 * <p>所有 SQL 都集中在這裡，上層服務只看得到 {@link Asset} 與 Java 型別。
 * 使用 {@code JdbcClient} 而非 JPA，是因為資料模型固定、查詢型態少，
 * 直接寫 SQL 比維護 entity 對應更好讀也更好調校。
 *
 * <p>所有查詢都必須帶 {@code sourceId} 條件，確保不同群組的資產彼此看不到。
 */
@Repository
public class AssetRepository {

    private final JdbcClient jdbc;

    /**
     * @param jdbc Spring 提供的 SQLite 連線用戶端
     */
    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 寫入一筆新的資產索引。
     *
     * @param asset 待寫入的資產，{@code id} 欄位會被忽略
     * @return 資料庫產生的流水號
     */
    public Long insert(Asset asset) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO asset (message_id, share_token, source_type, source_id,
                                           uploader_id, file_path, content_type, file_size, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(asset.messageId(), asset.shareToken(), asset.sourceType(), asset.sourceId(),
                        asset.uploaderId(), asset.filePath(), asset.contentType(), asset.fileSize(),
                        asset.createdAt().toString())
                .update(keys);
        Number key = keys.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * 以 LINE 訊息 id 取出資產，並一併載入標籤。
     *
     * <p>引用回覆的歸檔流程與「重複 webhook 事件」的判斷都靠這個方法。
     *
     * @param messageId LINE 訊息 id
     * @return 含標籤的資產；查無資料時為空
     */
    public Optional<Asset> findByMessageId(String messageId) {
        return jdbc.sql("SELECT * FROM asset WHERE message_id = ?")
                .param(messageId)
                .query(AssetRepository::mapAsset)
                .optional()
                .map(this::withTags);
    }

    /**
     * 以對外取圖權杖取出資產。標籤不會載入，因為取圖端點用不到。
     *
     * @param shareToken 對外權杖
     * @return 不含標籤的資產；查無資料時為空
     */
    public Optional<Asset> findByShareToken(String shareToken) {
        return jdbc.sql("SELECT * FROM asset WHERE share_token = ?")
                .param(shareToken)
                .query(AssetRepository::mapAsset)
                .optional();
    }

    /**
     * 更新指向磁碟檔案的路徑，於圖片被搬進資產編號資料夾後呼叫。
     *
     * @param assetId  資產流水號
     * @param filePath 搬移後的相對路徑
     */
    public void updateFilePath(long assetId, String filePath) {
        jdbc.sql("UPDATE asset SET file_path = ? WHERE id = ?")
                .params(filePath, assetId)
                .update();
    }

    /**
     * 取得標籤 id，不存在就建立。標籤名稱以 UTF-8 存入，中文與資產編號同樣適用。
     *
     * @param name 標籤名稱
     * @return 既有或新建的標籤 id
     */
    public long upsertTag(String name) {
        jdbc.sql("INSERT OR IGNORE INTO tag (name) VALUES (?)").param(name).update();
        return jdbc.sql("SELECT id FROM tag WHERE name = ?").param(name).query(Long.class).single();
    }

    /**
     * 建立資產與標籤的關聯，重複掛同一個標籤不會出錯。
     *
     * @param assetId 資產流水號
     * @param tagId   標籤 id
     */
    public void linkTag(long assetId, long tagId) {
        jdbc.sql("INSERT OR IGNORE INTO asset_tag (asset_id, tag_id) VALUES (?, ?)")
                .params(assetId, tagId)
                .update();
    }

    /**
     * 取出某筆資產的所有標籤，順序與當初掛上的順序一致，
     * 因此第一個就是決定實體資料夾的資產編號。
     *
     * @param assetId 資產流水號
     * @return 標籤名稱清單
     */
    public List<String> findTagNames(long assetId) {
        return jdbc.sql("""
                        SELECT t.name FROM tag t
                        JOIN asset_tag at ON at.tag_id = t.id
                        WHERE at.asset_id = ?
                        ORDER BY at.rowid
                        """)
                .param(assetId)
                .query(String.class)
                .list();
    }

    /**
     * 以「同時具備全部標籤」的條件查詢資產（AND 語意），限定在同一個來源（群組）內。
     *
     * <p>AND 語意靠 {@code HAVING COUNT(DISTINCT t.name) = ?} 達成：
     * 命中的標籤種類數必須等於使用者給的關鍵字數量。結果依收錄時間新到舊排序。
     *
     * @param sourceId 查詢範圍
     * @param tags     關鍵字，空集合直接回傳空結果
     * @param limit    最多回傳幾筆
     * @return 含標籤的資產清單
     */
    public List<Asset> searchByTags(String sourceId, List<String> tags, int limit) {
        if (tags.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(tags.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(sourceId);
        params.addAll(tags);
        params.add(tags.size());
        params.add(limit);

        List<Asset> found = jdbc.sql("""
                        SELECT a.* FROM asset a
                        JOIN asset_tag at ON at.asset_id = a.id
                        JOIN tag t ON t.id = at.tag_id
                        WHERE a.source_id = ? AND t.name IN (%s)
                        GROUP BY a.id
                        HAVING COUNT(DISTINCT t.name) = ?
                        ORDER BY a.created_at DESC
                        LIMIT ?
                        """.formatted(placeholders))
                .params(params)
                .query(AssetRepository::mapAsset)
                .list();

        return found.stream().map(this::withTags).toList();
    }

    /**
     * 某個來源（群組）目前用過的標籤與各自的資產數，供 {@code #標籤} 指令列出。
     *
     * @param sourceId 統計範圍
     * @return 標籤名稱到數量的對應，依數量多到少排序
     */
    public Map<String, Integer> tagCounts(String sourceId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT t.name AS name, COUNT(*) AS cnt FROM tag t
                        JOIN asset_tag at ON at.tag_id = t.id
                        JOIN asset a ON a.id = at.asset_id
                        WHERE a.source_id = ?
                        GROUP BY t.name
                        ORDER BY cnt DESC, t.name
                        """)
                .param(sourceId)
                .query()
                .listOfRows()
                .forEach(row -> counts.put((String) row.get("name"), ((Number) row.get("cnt")).intValue()));
        return counts;
    }

    /**
     * 統計某個來源目前收錄的圖片總數。
     *
     * @param sourceId 統計範圍
     * @return 圖片張數
     */
    public int countBySource(String sourceId) {
        return jdbc.sql("SELECT COUNT(*) FROM asset WHERE source_id = ?")
                .param(sourceId)
                .query(Integer.class)
                .single();
    }

    /**
     * 補上標籤欄位。{@link #mapAsset} 一次只讀一列，無法順帶帶出關聯資料，
     * 因此由這裡補齊。
     *
     * @param asset 尚未載入標籤的資產
     * @return 含標籤的複本
     */
    private Asset withTags(Asset asset) {
        return new Asset(asset.id(), asset.messageId(), asset.shareToken(), asset.sourceType(),
                asset.sourceId(), asset.uploaderId(), asset.filePath(), asset.contentType(),
                asset.fileSize(), asset.createdAt(), findTagNames(asset.id()));
    }

    /**
     * 把一列查詢結果轉成 {@link Asset}，標籤留空由 {@link #withTags} 補。
     *
     * <p>{@code file_size} 需要 {@code wasNull()} 判斷，否則 SQL NULL 會被
     * {@code getLong} 悄悄讀成 0。
     *
     * @param rs     結果集，游標已指在目標列
     * @param rowNum 列序，未使用
     * @return 對應的資產
     * @throws java.sql.SQLException 讀取欄位失敗時拋出
     */
    private static Asset mapAsset(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Long fileSize = rs.getLong("file_size");
        if (rs.wasNull()) {
            fileSize = null;
        }
        return new Asset(
                rs.getLong("id"),
                rs.getString("message_id"),
                rs.getString("share_token"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("uploader_id"),
                rs.getString("file_path"),
                rs.getString("content_type"),
                fileSize,
                Instant.parse(rs.getString("created_at")),
                List.of());
    }
}
