package dev.miudog.linebotcommercial.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 【職責】尚未正式歸檔的圖片組與確認操作的唯一 SQLite 出入口。
 *
 * <p><b>寫入鏈：</b>
 * {@code ImageArchiveService.stage → insert → pending_image}。
 *
 * <p><b>確認鏈：</b>
 * {@code ImageArchiveService.requestArchive → saveConfirmation
 * → pending_archive_confirmation}。
 *
 * <p><b>完成鏈：</b>
 * {@code ImageArchiveService.confirm → findConfirmation／findSet
 * → deleteSet／deleteConfirmation}。
 *
 * <p>所有圖片組查詢同時使用 {@code sourceId + imageSetId}，
 * 防止相同 imageSet 識別碼在不同 LINE 來源之間互相干擾。
 */
@Repository
public class PendingImageRepository {

	private final JdbcClient jdbc;

	//#region 初始化與待處理圖片

	// 方法：初始化 PendingImageRepository。
	public PendingImageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public record PendingImage(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String sourceType,
		String sourceId,
		String uploaderId,
		String stagingPath,
		String contentType,
		long fileSize,
		Instant receivedAt
	) {}

	public record PendingConfirmation(
		String sourceId,
		String requesterId,
		String imageSetId,
		String archiveDate,
		Instant requestedAt
	) {}

	public record FetchAttempt(
		String sourceId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String messageId,
		String status,
		Instant updatedAt
	) {}

	// 方法：執行 findByMessageId 方法的處理流程。
	public Optional<PendingImage> findByMessageId(String messageId) {
		// 外部呼叫：使用 Spring JDBC 依訊息識別碼查詢待歸檔圖片。
		return jdbc.sql("SELECT * FROM pending_image WHERE message_id = ?")
			.param(messageId)
			.query(PendingImageRepository::mapImage)
			.optional();
	}

	// 方法：執行 findSet 方法的處理流程。
	public List<PendingImage> findSet(String sourceId, String imageSetId) {
		// 外部呼叫：使用 Spring JDBC 依圖片組順序讀取同一來源的待歸檔圖片。
		return jdbc.sql("""
                        SELECT * FROM pending_image
                        WHERE source_id = ? AND image_set_id = ?
                        ORDER BY image_index
                        """)
			.params(sourceId, imageSetId)
			.query(PendingImageRepository::mapImage)
			.list();
	}

	// 方法：執行 insert 方法的處理流程。
	public void insert(PendingImage image) {
		// 外部呼叫：使用 Spring JDBC 保存待歸檔圖片的來源、順序與暫存路徑。
		jdbc.sql("""
                        INSERT INTO pending_image (
                            message_id, image_set_id, image_index, image_total,
                            source_type, source_id, uploader_id, staging_path,
                            content_type, file_size, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
			.params(
			image.messageId(),
			image.imageSetId(),
			image.imageIndex(),
			image.imageTotal(),
			image.sourceType(),
			image.sourceId(),
			image.uploaderId(),
			image.stagingPath(),
			image.contentType(),
			image.fileSize(),
			image.receivedAt().toString()
		)
			.update();
	}

	// 方法：記錄同一圖片組中每個位置最後確認的抓取結果。
	public void upsertFetchAttempt(FetchAttempt attempt) {
		// 外部呼叫：以來源、圖片組與位置去重，避免 webhook 重送重複計數。
		jdbc.sql("""
				INSERT INTO pending_image_fetch (
				    source_id, image_set_id, image_index, image_total,
				    message_id, status, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (source_id, image_set_id, image_index) DO UPDATE SET
				    image_total = excluded.image_total,
				    message_id = excluded.message_id,
				    status = CASE
				        WHEN pending_image_fetch.status = 'FETCHED' THEN 'FETCHED'
				        ELSE excluded.status
				    END,
				    updated_at = excluded.updated_at
				""")
			.params(
				attempt.sourceId(),
				attempt.imageSetId(),
				attempt.imageIndex(),
				attempt.imageTotal(),
				attempt.messageId(),
				attempt.status(),
				attempt.updatedAt().toString()
			)
			.update();
	}

	// 方法：依被引用的 LINE 訊息取得其圖片組抓取紀錄。
	public Optional<FetchAttempt> findFetchAttemptByMessageId(String messageId) {
		// 外部呼叫：使用 Spring JDBC 查詢最近一次相符抓取紀錄。
		return jdbc.sql("""
				SELECT * FROM pending_image_fetch
				WHERE message_id = ?
				ORDER BY updated_at DESC
				LIMIT 1
				""")
			.param(messageId)
			.query(PendingImageRepository::mapFetchAttempt)
			.optional();
	}

	// 方法：取得整組圖片各位置的抓取結果。
	public List<FetchAttempt> findFetchSet(String sourceId, String imageSetId) {
		// 外部呼叫：依圖片位置排序抓取狀態，供歸檔完整性判定。
		return jdbc.sql("""
				SELECT * FROM pending_image_fetch
				WHERE source_id = ? AND image_set_id = ?
				ORDER BY image_index
				""")
			.params(sourceId, imageSetId)
			.query(PendingImageRepository::mapFetchAttempt)
			.list();
	}

	//#endregion

	//#region 歸檔確認

	// 方法：執行 saveConfirmation 方法的處理流程。
	public void saveConfirmation(PendingConfirmation confirmation) {
		// 外部呼叫：使用 Spring JDBC 新增或更新使用者目前的歸檔確認資料。
		jdbc.sql("""
                        INSERT INTO pending_archive_confirmation (
                            source_id, requester_id, image_set_id, archive_date, requested_at
                        ) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (source_id, requester_id) DO UPDATE SET
                            image_set_id = excluded.image_set_id,
                            archive_date = excluded.archive_date,
                            requested_at = excluded.requested_at
                        """)
			.params(
			confirmation.sourceId(),
			confirmation.requesterId(),
			confirmation.imageSetId(),
			confirmation.archiveDate(),
			confirmation.requestedAt().toString()
		)
			.update();
	}

	// 方法：執行 findConfirmation 方法的處理流程。
	public Optional<PendingConfirmation> findConfirmation(String sourceId, String requesterId) {
		// 外部呼叫：使用 Spring JDBC 查詢指定使用者尚未完成的歸檔確認。
		return jdbc.sql("""
                        SELECT * FROM pending_archive_confirmation
                        WHERE source_id = ? AND requester_id = ?
                        """)
			.params(sourceId, requesterId)
			.query(PendingImageRepository::mapConfirmation)
			.optional();
	}

	// 方法：執行 deleteConfirmation 方法的處理流程。
	public void deleteConfirmation(String sourceId, String requesterId) {
		// 外部呼叫：使用 Spring JDBC 刪除已完成或取消的歸檔確認資料。
		jdbc.sql("""
                        DELETE FROM pending_archive_confirmation
                        WHERE source_id = ? AND requester_id = ?
                        """).params(sourceId, requesterId).update();
	}

	// 方法：執行 deleteSet 方法的處理流程。
	public void deleteSet(String sourceId, String imageSetId) {
		// 外部呼叫：使用 Spring JDBC 清除已完成歸檔的整組待處理索引。
		jdbc.sql("DELETE FROM pending_image WHERE source_id = ? AND image_set_id = ?")
			.params(sourceId, imageSetId)
			.update();
	}

	// 方法：正式歸檔成功後清除該圖片組的抓取結果。
	public void deleteFetchSet(String sourceId, String imageSetId) {
		// 外部呼叫：使用 Spring JDBC 清除已完成圖片組的統計狀態。
		jdbc.sql("""
				DELETE FROM pending_image_fetch
				WHERE source_id = ? AND image_set_id = ?
				""")
			.params(sourceId, imageSetId)
			.update();
	}

	//#endregion

	//#region 資料映射

	// 方法：執行 mapImage 方法的處理流程。
	private static PendingImage mapImage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new PendingImage(
			rs.getString("message_id"),
			rs.getString("image_set_id"),
			rs.getInt("image_index"),
			rs.getInt("image_total"),
			rs.getString("source_type"),
			rs.getString("source_id"),
			rs.getString("uploader_id"),
			rs.getString("staging_path"),
			rs.getString("content_type"),
			rs.getLong("file_size"),
			Instant.parse(rs.getString("received_at"))
		);
	}

	// 方法：執行 mapConfirmation 方法的處理流程。
	private static PendingConfirmation mapConfirmation(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new PendingConfirmation(
			rs.getString("source_id"),
			rs.getString("requester_id"),
			rs.getString("image_set_id"),
			rs.getString("archive_date"),
			Instant.parse(rs.getString("requested_at"))
		);
	}

	// 方法：將抓取狀態資料列映射為 FetchAttempt。
	private static FetchAttempt mapFetchAttempt(
		java.sql.ResultSet rs,
		int rowNum
	) throws java.sql.SQLException {
		return new FetchAttempt(
			rs.getString("source_id"),
			rs.getString("image_set_id"),
			rs.getInt("image_index"),
			rs.getInt("image_total"),
			rs.getString("message_id"),
			rs.getString("status"),
			Instant.parse(rs.getString("updated_at"))
		);
	}

	//#endregion
}
