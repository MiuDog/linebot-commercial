package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryClaim;
import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryRepository;
import dev.miudog.linebotcommercial.service.quotation.QuotationDeliverySnapshot;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 以報價資料庫保存正式 LINE 交付的宣告、嘗試次數、成功結果及安全錯誤摘要。 */
@Repository
public class JdbcQuotationDeliveryRepository implements QuotationDeliveryRepository {

	private final JdbcTemplate jdbc;

	// 方法：注入報價資料庫存取邊界。
	public JdbcQuotationDeliveryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：只從 READY PDF 所屬的不可變正式報價讀取 Flex 摘要金額。
	@Override
	public QuotationDeliverySnapshot findSnapshot(long quotationId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT q.id, q.company_name, q.work_name, q.quotation_no, q.currency,
				q.subtotal, q.tax_amount, q.total_amount, q.created_at
			FROM quotation q
			JOIN quotation_file qf ON qf.quotation_id = q.id
			WHERE q.id = ?
			  AND q.status IN ('READY', 'SENDING', 'SENT')
			  AND qf.file_kind = 'PDF'
			  AND qf.status = 'READY'
			""", quotationId);
		if (rows.size() != 1) throw new IllegalStateException("找不到可交付的正式報價 PDF");

		Map<String, Object> row = rows.getFirst();
		return new QuotationDeliverySnapshot(
			((Number) row.get("id")).longValue(),
			requiredText(row, "company_name"),
			requiredText(row, "work_name"),
			requiredText(row, "quotation_no"),
			requiredText(row, "currency"),
			decimal(row, "subtotal"),
			decimal(row, "tax_amount"),
			decimal(row, "total_amount"),
			parseDatabaseInstant(row.get("created_at"))
		);
	}

	// 方法：將資料庫文字時間轉成 UTC Instant，供顯示與跨資料庫租約判斷共用。
	private Instant parseDatabaseInstant(Object value) {
		if (value == null) return null;

		String timestamp = value.toString().replace(' ', 'T');
		if (!timestamp.endsWith("Z") && !timestamp.matches(".*[+-]\\d{2}(?::?\\d{2})?$")) {
			timestamp += "Z";
		}
		return OffsetDateTime.parse(timestamp).toInstant();
	}

	// 方法：以唯一 active delivery 索引取得跨執行緒與跨程序的最終交付權。
	@Override
	@Transactional
	public QuotationDeliveryClaim claimFinal(long quotationId, String destinationId) {
		recoverExpiredLease(quotationId, destinationId);
		QuotationDeliveryClaim existing = existingClaim(quotationId, destinationId);
		if (existing != null) return existing;

		try {
			KeyHolder key = new GeneratedKeyHolder();
			jdbc.update(connection -> {
				var statement = connection.prepareStatement("""
					INSERT INTO quotation_delivery_attempt (
						quotation_id, destination_type, destination_id, delivery_kind, status
					)
					VALUES (?, 'LINE_USER', ?, 'FINAL', 'SENDING')
					""", new String[]{"id"});
				statement.setLong(1, quotationId);
				statement.setString(2, destinationId);
				return statement;

			}, key);
			Number attemptId = key.getKey();
			if (attemptId == null) throw new IllegalStateException("無法建立 LINE 交付嘗試");

			jdbc.update(
				"UPDATE quotation SET status = 'SENDING' WHERE id = ? AND status = 'READY'",
				quotationId
			);
			return QuotationDeliveryClaim.ready(
				attemptId.longValue(),
				attemptCount(quotationId, destinationId)
			);
		}
		catch (DataIntegrityViolationException exception) {
			QuotationDeliveryClaim concurrent = existingClaim(quotationId, destinationId);
			if (concurrent == null) throw exception;

			return concurrent;
		}
	}

	// 方法：在 Java 解析文字時間，避免 SQLite 與 PostgreSQL 對 TEXT／TIMESTAMP 比較方式不同。
	private void recoverExpiredLease(long quotationId, String destinationId) {
		List<Map<String, Object>> activeAttempts = jdbc.queryForList("""
			SELECT id, attempted_at
			FROM quotation_delivery_attempt
			WHERE quotation_id = ?
			  AND destination_type = 'LINE_USER'
			  AND destination_id = ?
			  AND delivery_kind = 'FINAL'
			  AND status = 'SENDING'
			""", quotationId, destinationId);
		Instant cutoff = Instant.now().minus(java.time.Duration.ofMinutes(5));
		int recovered = 0;
		for (Map<String, Object> attempt : activeAttempts) {
			Instant attemptedAt = parseDatabaseInstant(attempt.get("attempted_at"));
			if (attemptedAt != null && attemptedAt.isAfter(cutoff)) continue;

			recovered += jdbc.update("""
				UPDATE quotation_delivery_attempt
				SET status = 'FAILED', error_message = 'DELIVERY_LEASE_EXPIRED',
					completed_at = CURRENT_TIMESTAMP
				WHERE id = ? AND status = 'SENDING'
				""", ((Number) attempt.get("id")).longValue());
		}
		if (recovered == 0) return;

		jdbc.update(
			"UPDATE quotation SET status = 'READY' WHERE id = ? AND status = 'SENDING'",
			quotationId
		);
	}

	// 方法：將取得發送權的嘗試標記成功，並同步正式報價整體狀態。
	@Override
	@Transactional
	public void markSent(long attemptId, String providerMessageId) {
		int updated = jdbc.update("""
			UPDATE quotation_delivery_attempt
			SET status = 'SENT', provider_message_id = ?, error_message = NULL,
				completed_at = CURRENT_TIMESTAMP
			WHERE id = ? AND status = 'SENDING'
			""", providerMessageId, attemptId);
		if (updated != 1) throw new IllegalStateException("LINE 交付嘗試已失效");

		jdbc.update("""
			UPDATE quotation
			SET status = 'SENT'
			WHERE id = (
				SELECT quotation_id FROM quotation_delivery_attempt WHERE id = ?
			)
			""", attemptId);
	}

	// 方法：將取得發送權的嘗試標記失敗，並讓正式報價恢復可重試狀態。
	@Override
	@Transactional
	public void markFailed(long attemptId, String errorSummary) {
		int updated = jdbc.update("""
			UPDATE quotation_delivery_attempt
			SET status = 'FAILED', provider_message_id = NULL, error_message = ?,
				completed_at = CURRENT_TIMESTAMP
			WHERE id = ? AND status = 'SENDING'
			""", errorSummary, attemptId);
		if (updated != 1) throw new IllegalStateException("LINE 交付嘗試已失效");

		jdbc.update("""
			UPDATE quotation
			SET status = 'READY'
			WHERE id = (
				SELECT quotation_id FROM quotation_delivery_attempt WHERE id = ?
			)
			  AND status = 'SENDING'
			""", attemptId);
	}

	// 方法：優先回傳已成功的冪等結果，其次回傳目前進行中的發送權。
	private QuotationDeliveryClaim existingClaim(long quotationId, String destinationId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT id, status, provider_message_id
			FROM quotation_delivery_attempt
			WHERE quotation_id = ?
			  AND destination_type = 'LINE_USER'
			  AND destination_id = ?
			  AND delivery_kind = 'FINAL'
			  AND status IN ('SENDING', 'SENT')
			ORDER BY CASE status WHEN 'SENT' THEN 0 ELSE 1 END, id DESC
			LIMIT 1
			""", quotationId, destinationId);
		if (rows.isEmpty()) return null;

		Map<String, Object> row = rows.getFirst();
		long attemptId = ((Number) row.get("id")).longValue();
		int count = attemptCount(quotationId, destinationId);
		if ("SENT".equals(row.get("status"))) {
			return QuotationDeliveryClaim.alreadySent(
				attemptId,
				count,
				(String) row.get("provider_message_id")
			);
		}

		return QuotationDeliveryClaim.inProgress(attemptId, count);
	}

	// 方法：計算同一正式報價與 LINE 使用者的累積最終交付次數。
	private int attemptCount(long quotationId, String destinationId) {
		Integer count = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_delivery_attempt
			WHERE quotation_id = ?
			  AND destination_type = 'LINE_USER'
			  AND destination_id = ?
			  AND delivery_kind = 'FINAL'
			""", Integer.class, quotationId, destinationId);
		return count == null ? 0 : count;
	}

	// 方法：讀取正式快照的必要文字欄位並拒絕空值。
	private String requiredText(Map<String, Object> row, String field) {
		Object value = row.get(field);
		if (value == null || value.toString().isBlank()) {
			throw new IllegalStateException("正式報價必要欄位不完整");
		}

		return value.toString();
	}

	// 方法：將 SQLite NUMERIC 欄位穩定轉成精確十進位金額。
	private BigDecimal decimal(Map<String, Object> row, String field) {
		Object value = row.get(field);
		if (value == null) throw new IllegalStateException("正式報價金額快照不完整");

		return new BigDecimal(value.toString());
	}
}
