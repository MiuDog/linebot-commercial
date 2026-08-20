package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.domain.Asset;
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
 *
 * <p><b>上游呼叫鏈：</b>
 * <ul>
 *   <li>{@code ImageArchiveService.confirm → insert／upsertTag／linkTag}</li>
 *   <li>{@code AssetService.search／tagCounts／countBySource → 對應查詢}</li>
 *   <li>{@code MediaController → AssetService.findByShareToken → findByShareToken}</li>
 * </ul>
 * 本 Repository 不操作實體圖片，資料列中的 {@code file_path} 只是一個相對指標。
 */
@Repository
public class AssetRepository {

	private final JdbcClient jdbc;

	/**
	 * @param jdbc Spring 提供的 SQLite 連線用戶端
	 */
	//#region 初始化與資產索引

	// 方法：初始化 AssetRepository。
	public AssetRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 寫入一筆新的資產索引。
	 *
	 * @param asset 待寫入的資產，{@code id} 欄位會被忽略
	 * @return 資料庫產生的流水號
	 */
	// 方法：執行 insert 方法的處理流程。
	public Long insert(Asset asset) {
		KeyHolder keys = new GeneratedKeyHolder();

		// 外部呼叫：使用 Spring JDBC 寫入資產索引，並取得資料庫產生的主鍵。
		jdbc.sql("""
                        INSERT INTO asset (message_id, share_token, source_type, source_id,
                                           uploader_id, file_path, content_type, file_size, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
			.params(
			asset.messageId(),
			asset.shareToken(),
			asset.sourceType(),
			asset.sourceId(),
			asset.uploaderId(),
			asset.filePath(),
			asset.contentType(),
			asset.fileSize(),
			asset.createdAt().toString()
		)
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
	// 方法：執行 findByMessageId 方法的處理流程。
	public Optional<Asset> findByMessageId(String messageId) {
		// 外部呼叫：使用 Spring JDBC 依 LINE 訊息識別碼查詢資產與標籤。
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
	// 方法：執行 findByShareToken 方法的處理流程。
	public Optional<Asset> findByShareToken(String shareToken) {
		// 外部呼叫：使用 Spring JDBC 依公開權杖查詢媒體端點需要的資產。
		return jdbc.sql("SELECT * FROM asset WHERE share_token = ?")
			.param(shareToken)
			.query(AssetRepository::mapAsset)
			.optional();
	}

	// 方法：取得檔案同步需要的全部資產索引。
	public List<Asset> findAll() {
		// 外部呼叫：資產根目錄同步排除由正式報價資料夾管理的圖片。
		return jdbc.sql("""
			SELECT a.* FROM asset a
			WHERE NOT EXISTS (
				SELECT 1 FROM quotation_asset qa WHERE qa.asset_id = a.id
			)
			ORDER BY a.id
			""")
			.query(AssetRepository::mapAsset)
			.list();
	}

	// 方法：判斷圖片是否由正式報價資料夾管理。
	public boolean isQuotationAsset(long assetId) {
		Integer count = jdbc.sql("SELECT COUNT(*) FROM quotation_asset WHERE asset_id = ?")
			.param(assetId)
			.query(Integer.class)
			.single();
		return count != null && count > 0;
	}

	// 方法：更新 Explorer 改名或移動後的檔案資料。
	public void updateFile(
		long assetId,
		String filePath,
		String contentType,
		long fileSize
	) {
		// 外部呼叫：使用 Spring JDBC 更新資產的相對路徑、型態與實際大小。
		jdbc.sql("""
                        UPDATE asset
                        SET file_path = ?, content_type = ?, file_size = ?
                        WHERE id = ?
                        """)
			.params(filePath, contentType, fileSize, assetId)
			.update();
	}

	// 方法：將被本機編輯的圖片改為本地來源，並釋放原本的 LINE 訊息編號。
	public void markAsLocal(long assetId, String localMessageId) {
		// 外部 API：透過 Spring JDBC 更新來源與唯一訊息編號。
		jdbc.sql("""
				UPDATE asset
				SET message_id = ?, source_type = 'local'
				WHERE id = ?
				""")
			.params(localMessageId, assetId)
			.update();
	}

	// 方法：刪除實體檔案已不存在的圖片資料；關聯資料由外鍵串聯刪除。
	public void delete(long assetId) {
		// 外部 API：透過 Spring JDBC 刪除圖片主資料。
		jdbc.sql("DELETE FROM asset WHERE id = ?")
			.param(assetId)
			.update();
	}

	public record FileIdentity(
		long assetId,
		String fileKey,
		String contentHash,
		long fileSize,
		long lastModified
	) {}

	// 方法：取得全部資產檔案身分資料。
	public Map<Long, FileIdentity> findFileIdentities() {
		Map<Long, FileIdentity> identities = new LinkedHashMap<>();

		// 外部呼叫：使用 Spring JDBC 讀取檔案識別碼、雜湊與快速比對欄位。
		jdbc.sql("SELECT * FROM asset_file_identity ORDER BY asset_id")
			.query()
			.listOfRows()
			.forEach(row -> {
				long assetId = ((Number) row.get("asset_id")).longValue();
				identities.put(
					assetId,
					new FileIdentity(
						assetId,
						(String) row.get("file_key"),
						(String) row.get("content_hash"),
						((Number) row.get("file_size")).longValue(),
						((Number) row.get("last_modified")).longValue()
					)
				);
			});
		return identities;
	}

	// 方法：新增或更新資產檔案身分資料。
	public void upsertFileIdentity(FileIdentity identity) {
		// 外部呼叫：使用 Spring JDBC 保存可重建的檔案識別碼與內容雜湊。
		jdbc.sql("""
                        INSERT INTO asset_file_identity (
                            asset_id, file_key, content_hash, file_size, last_modified, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (asset_id) DO UPDATE SET
                            file_key = excluded.file_key,
                            content_hash = excluded.content_hash,
                            file_size = excluded.file_size,
                            last_modified = excluded.last_modified,
                            updated_at = excluded.updated_at
                        """)
			.params(
				identity.assetId(),
				identity.fileKey(),
				identity.contentHash(),
				identity.fileSize(),
				identity.lastModified(),
				Instant.now().toString()
			)
			.update();
	}

	/**
	 * 取得標籤 id，不存在就建立。標籤名稱以 UTF-8 存入，中文與資產編號同樣適用。
	 *
	 * @param name 標籤名稱
	 * @return 既有或新建的標籤 id
	 */
	//#endregion

	//#region 標籤關聯

	// 方法：執行 upsertTag 方法的處理流程。
	public long upsertTag(String name) {
		// 步驟 1：使用 Spring JDBC 建立不存在的標籤，並忽略重複名稱。
		jdbc.sql("INSERT OR IGNORE INTO tag (name) VALUES (?)").param(name).update();

		// 步驟 2：使用 Spring JDBC 讀回標籤主鍵，供資產關聯使用。
		return jdbc.sql("SELECT id FROM tag WHERE name = ?").param(name).query(Long.class).single();
	}

	/**
	 * 建立資產與標籤的關聯，重複掛同一個標籤不會出錯。
	 *
	 * @param assetId 資產流水號
	 * @param tagId   標籤 id
	 */
	// 方法：執行 linkTag 方法的處理流程。
	public void linkTag(long assetId, long tagId) {
		// 外部呼叫：使用 Spring JDBC 建立資產與標籤的關聯，並忽略重複關聯。
		jdbc.sql("INSERT OR IGNORE INTO asset_tag (asset_id, tag_id) VALUES (?, ?)").params(assetId, tagId).update();
	}

	/**
	 * 取出某筆資產的所有標籤，順序與當初掛上的順序一致，
	 * 因此第一個就是決定實體資料夾的資產編號。
	 *
	 * @param assetId 資產流水號
	 * @return 標籤名稱清單
	 */
	// 方法：執行 findTagNames 方法的處理流程。
	public List<String> findTagNames(long assetId) {
		// 外部呼叫：使用 Spring JDBC 依建立順序讀取資產的所有標籤。
		return jdbc.sql("""
                        SELECT t.name FROM tag t
                        JOIN asset_tag at ON at.tag_id = t.id
                        WHERE at.asset_id = ?
                        ORDER BY at.rowid
                        """).param(assetId).query(String.class).list();
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
	//#endregion

	//#region 查詢與統計

	// 方法：執行 searchByTags 方法的處理流程。
	public List<Asset> searchByTags(String sourceId, List<String> tags, int limit) {
		if (tags.isEmpty()) return List.of();

		// 步驟 1：使用 Java 集合 API 建立安全的 SQL 參數占位符與參數清單。
		String placeholders = String.join(", ", java.util.Collections.nCopies(tags.size(), "?"));
		List<Object> params = new ArrayList<>();
		params.add(sourceId);
		params.addAll(tags);
		params.add(tags.size());
		params.add(limit);

		// 步驟 2：使用 Spring JDBC 執行限定來源且必須符合全部標籤的查詢。
		List<Asset> found = jdbc.sql("""
                        SELECT a.* FROM asset a
                        JOIN asset_tag at ON at.asset_id = a.id
                        JOIN tag t ON t.id = at.tag_id
                        WHERE a.source_id = ?
                          AND t.name IN (%s)
                        GROUP BY a.id
                        HAVING COUNT(DISTINCT t.name) = ?
                        ORDER BY a.created_at DESC
                        LIMIT ?
                        """.formatted(placeholders)).params(params).query(AssetRepository::mapAsset).list();

		return found.stream().map(this::withTags).toList();
	}

	// 方法：依來源、完整部門標籤及部門下的八碼日期資料夾查詢圖片。
	public List<Asset> searchByDepartmentAndDate(
		String sourceId,
		String departmentTag,
		String compactDate,
		int limit
	) {
		String pathPattern = departmentTag + "/" + compactDate + "/%";
		List<Asset> found = jdbc.sql("""
			SELECT DISTINCT a.* FROM asset a
			JOIN asset_tag at ON at.asset_id = a.id
			JOIN tag t ON t.id = at.tag_id
			WHERE a.source_id = ?
			  AND LOWER(t.name) = ?
			  AND LOWER(a.file_path) LIKE ?
			ORDER BY a.created_at ASC, a.id ASC
			LIMIT ?
			""")
			.params(sourceId, departmentTag, pathPattern, limit)
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
	// 方法：執行 tagCounts 方法的處理流程。
	public Map<String, Integer> tagCounts(String sourceId) {
		Map<String, Integer> counts = new LinkedHashMap<>();

		// 外部呼叫：使用 Spring JDBC 統計指定來源的標籤與資產數量。
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
	// 方法：執行 countBySource 方法的處理流程。
	public int countBySource(String sourceId) {
		// 外部呼叫：使用 Spring JDBC 統計指定來源的資產總數。
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
	// 方法：執行 withTags 方法的處理流程。
	private Asset withTags(Asset asset) {
		return new Asset(
			asset.id(),
			asset.messageId(),
			asset.shareToken(),
			asset.sourceType(),
			asset.sourceId(),
			asset.uploaderId(),
			asset.filePath(),
			asset.contentType(),
			asset.fileSize(),
			asset.createdAt(),
			findTagNames(asset.id())
		);
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
	//#endregion

	//#region 資料映射

	// 方法：執行 mapAsset 方法的處理流程。
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
			List.of()
		);
	}

	//#endregion
}
