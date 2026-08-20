package dev.myudog.assetsmanagerlinebot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuotationEventReceiptRepository {

	private final JdbcTemplate jdbc;

	// 方法：初始化報價事件收件儲存庫。
	public QuotationEventReceiptRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：原子占用事件編號，避免 LINE 重送造成重複副作用。
	public boolean claim(String eventId, Long draftId, String eventType) {
		if (eventId == null || eventId.isBlank()) return false;

		// 資料庫：新事件建立租約；失敗或租約過期事件可安全重領，完成事件永不重播。
		return jdbc.update("""
			INSERT INTO quotation_event_receipt (
				event_id, draft_id, event_type, result_status, lease_until, attempt_count
			)
			VALUES (?, ?, ?, 'RECEIVED', datetime(CURRENT_TIMESTAMP, '+5 minutes'), 1)
			ON CONFLICT (event_id) DO UPDATE SET
				draft_id = COALESCE(excluded.draft_id, quotation_event_receipt.draft_id),
				event_type = excluded.event_type,
				result_status = 'RECEIVED',
				received_at = CURRENT_TIMESTAMP,
				processed_at = NULL,
				lease_until = datetime(CURRENT_TIMESTAMP, '+5 minutes'),
				attempt_count = quotation_event_receipt.attempt_count + 1
			WHERE quotation_event_receipt.result_status = 'FAILED'
				OR (quotation_event_receipt.result_status = 'RECEIVED'
					AND (quotation_event_receipt.lease_until IS NULL
						OR quotation_event_receipt.lease_until <= CURRENT_TIMESTAMP))
			""", eventId, draftId, eventType) == 1;
	}

	// 方法：查詢事件是否已被接收。
	public boolean exists(String eventId) {
		if (eventId == null || eventId.isBlank()) return false;

		// 資料庫：只讀取是否存在，不載入事件內容。
		Integer count = jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_event_receipt WHERE event_id = ?",
			Integer.class,
			eventId
		);
		return count != null && count > 0;
	}

	// 方法：只阻擋已完成或仍持有有效租約的事件，FAILED 與逾期租約可重試。
	public boolean blocksReplay(String eventId) {
		if (eventId == null || eventId.isBlank()) return false;

		// 資料庫：判斷事件是否已終結，或仍由另一個處理者持有有效租約。
		Integer count = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_event_receipt
			WHERE event_id = ?
				AND (
					result_status IN ('PROCESSED', 'IGNORED')
					OR (result_status = 'RECEIVED' AND lease_until > CURRENT_TIMESTAMP)
				)
			""", Integer.class, eventId);
		return count != null && count > 0;
	}

	// 方法：將事件標記為處理完成或忽略。
	public void complete(String eventId, Long draftId, boolean ignored) {
		// 資料庫：完成狀態與處理時間一併更新。
		jdbc.update("""
			UPDATE quotation_event_receipt
			SET draft_id = COALESCE(?, draft_id),
				result_status = ?,
				lease_until = NULL,
				processed_at = CURRENT_TIMESTAMP
			WHERE event_id = ?
			""", draftId, ignored ? "IGNORED" : "PROCESSED", eventId);
	}

	// 方法：將失敗事件標記為失敗，保留稽核紀錄並阻擋重複副作用。
	public void fail(String eventId, Long draftId) {
		// 資料庫：失敗不刪除收件紀錄，避免 LINE 重送重複執行部分副作用。
		jdbc.update("""
			UPDATE quotation_event_receipt
			SET draft_id = COALESCE(?, draft_id),
				result_status = 'FAILED',
				lease_until = NULL,
				processed_at = CURRENT_TIMESTAMP
			WHERE event_id = ?
			""", draftId, eventId);
	}
}
