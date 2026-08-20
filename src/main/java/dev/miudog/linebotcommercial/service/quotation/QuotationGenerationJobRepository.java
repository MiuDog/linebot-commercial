package dev.miudog.linebotcommercial.service.quotation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存正式報價背景產生工作的租約、重試時間與完成狀態。
 */
@Repository
public class QuotationGenerationJobRepository {

	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");
	private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,80}");

	private final JdbcTemplate jdbc;

	// 方法：建立持久化背景工作儲存庫。
	public QuotationGenerationJobRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：在正式確認交易內為每張報價建立唯一工作，重複確認不新增第二筆。
	@Transactional
	public boolean enqueueIfAbsent(long quotationId, String correlationId) {
		Instant now = Instant.now();
		// 資料庫 API：目的地只從已確認報價所屬的一對一草稿快照取得。
		int inserted = jdbc.update("""
			INSERT INTO quotation_generation_job (
				quotation_id, status, destination_id, correlation_id,
				next_attempt_at, created_at, updated_at
			)
			SELECT q.id, 'PENDING', d.requester_id, ?, ?, ?, ?
			FROM quotation q
			JOIN quotation_draft d ON d.id = q.draft_id
			WHERE q.id = ?
			  AND d.source_type = 'user'
			  AND d.requester_id IS NOT NULL
			  AND TRIM(d.requester_id) <> ''
			ON CONFLICT (quotation_id) DO NOTHING
			""",
			safeCorrelationId(correlationId, quotationId),
			timestamp(now),
			timestamp(now),
			timestamp(now),
			quotationId
		);
		if (inserted == 1) return true;

		if (findByQuotationId(quotationId).isPresent()) return false;

		throw new QuotationGenerationException(
			"GENERATION_JOB_ENQUEUE_FAILED",
			"正式報價缺少可持久化的一對一 LINE 交付對象",
			null
		);
	}

	// 方法：以到期時間原子租用一筆工作，程式中斷後其他工作者可接手過期租約。
	@Transactional
	public Optional<Job> leaseNext(String workerId, Instant now, Duration leaseDuration) {
		String safeWorkerId = requiredSafeId(workerId, "背景工作者識別");
		if (now == null) throw new IllegalArgumentException("租約時間不可留空");

		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("租約長度必須大於零");
		}
		String nowText = timestamp(now);
		String leaseUntil = timestamp(now.plus(leaseDuration));
		// 資料庫 API：單一 UPDATE RETURNING 同時選取及宣告到期工作，避免兩個工作者重複執行。
		List<Job> rows = jdbc.query("""
			UPDATE quotation_generation_job
			SET status = 'RUNNING', lease_owner = ?, lease_until = ?,
				attempt_count = attempt_count + 1, last_error_code = NULL,
				updated_at = ?
			WHERE id = (
				SELECT id
				FROM quotation_generation_job
				WHERE (status IN ('PENDING', 'FAILED') AND next_attempt_at <= ?)
				   OR (status = 'RUNNING' AND lease_until <= ?)
				ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END,
					next_attempt_at, id
				LIMIT 1
			)
			RETURNING id, quotation_id, status, destination_id, correlation_id,
				lease_owner, lease_until, attempt_count, next_attempt_at,
				last_error_code, created_at, updated_at, completed_at
			""", this::job, safeWorkerId, leaseUntil, nowText, nowText, nowText);
		return rows.stream().findFirst();
	}

	// 方法：只有目前租約持有人能把工作標記完成。
	@Transactional
	public boolean markDone(long jobId, String workerId) {
		String now = timestamp(Instant.now());
		// 資料庫 API：以工作主鍵、狀態及租約持有人做 compare-and-set。
		return jdbc.update("""
			UPDATE quotation_generation_job
			SET status = 'DONE', lease_owner = NULL, lease_until = NULL,
				last_error_code = NULL, completed_at = ?, updated_at = ?
			WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
			""", now, now, jobId, requiredSafeId(workerId, "背景工作者識別")) == 1;
	}

	// 方法：只有目前租約持有人能保存安全錯誤代碼及下一次執行時間。
	@Transactional
	public boolean markFailed(
		long jobId,
		String workerId,
		String errorCode,
		Instant nextAttemptAt
	) {
		if (nextAttemptAt == null) throw new IllegalArgumentException("下次重試時間不可留空");

		String now = timestamp(Instant.now());
		String safeErrorCode = errorCode != null && SAFE_ERROR_CODE.matcher(errorCode).matches()
			? errorCode
			: "GENERATION_FAILED";
		// 資料庫 API：釋放失敗工作的租約，並保留可由排程器重試的時間。
		return jdbc.update("""
			UPDATE quotation_generation_job
			SET status = 'FAILED', lease_owner = NULL, lease_until = NULL,
				next_attempt_at = ?, last_error_code = ?, completed_at = NULL,
				updated_at = ?
			WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
			""",
			timestamp(nextAttemptAt),
			safeErrorCode,
			now,
			jobId,
			requiredSafeId(workerId, "背景工作者識別")
		) == 1;
	}

	// 方法：管理者只可把同一張 XLSX 失敗報價重設為待處理，不配置新流水號。
	@Transactional
	public boolean retryFailedXlsx(long quotationId, String correlationId) {
		Integer running = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_generation_job
			WHERE quotation_id = ? AND status = 'RUNNING'
			""", Integer.class, quotationId);
		if (running != null && running > 0) return false;

		// 資料庫 API：正式報價與 XLSX 必須同時處於失敗狀態才可原號重試。
		int quotationChanged = jdbc.update("""
			UPDATE quotation
			SET status = 'CONFIRMED'
			WHERE id = ? AND status = 'FAILED'
			  AND EXISTS (
				SELECT 1 FROM quotation_file f
				WHERE f.quotation_id = quotation.id
				  AND f.file_kind = 'XLSX' AND f.status = 'FAILED'
			  )
			""", quotationId);
		if (quotationChanged != 1) return false;

		jdbc.update("""
			UPDATE quotation_file
			SET status = 'PENDING', relative_path = NULL, content_hash = NULL,
				file_size = NULL, error_message = NULL, updated_at = CURRENT_TIMESTAMP
			WHERE quotation_id = ? AND file_kind = 'XLSX' AND status = 'FAILED'
			""", quotationId);

		Instant now = Instant.now();
		// 資料庫 API：沿用唯一工作及目的地，並清除舊租約與嘗試計數。
		int changed = jdbc.update("""
			UPDATE quotation_generation_job
			SET status = 'PENDING', correlation_id = ?, lease_owner = NULL,
				lease_until = NULL, attempt_count = 0, next_attempt_at = ?,
				last_error_code = NULL, completed_at = NULL, updated_at = ?
			WHERE quotation_id = ? AND status <> 'RUNNING'
			""",
			safeCorrelationId(correlationId, quotationId),
			timestamp(now),
			timestamp(now),
			quotationId
		);
		if (changed == 1) return true;

		if (enqueueIfAbsent(quotationId, correlationId)) return true;

		throw new QuotationGenerationException(
			"GENERATION_JOB_RETRY_FAILED",
			"無法建立 Excel 原號重試工作",
			null
		);
	}

	// 方法：依正式報價識別讀取唯一背景工作。
	public Optional<Job> findByQuotationId(long quotationId) {
		// 資料庫 API：讀取管理及確認冪等判斷需要的非敏感工作狀態。
		List<Job> rows = jdbc.query("""
			SELECT id, quotation_id, status, destination_id, correlation_id,
				lease_owner, lease_until, attempt_count, next_attempt_at,
				last_error_code, created_at, updated_at, completed_at
			FROM quotation_generation_job
			WHERE quotation_id = ?
			""", this::job, quotationId);
		return rows.stream().findFirst();
	}

	// 方法：將資料庫列轉成不可變背景工作模型。
	private Job job(ResultSet result, int row) throws SQLException {
		return new Job(
			result.getLong("id"),
			result.getLong("quotation_id"),
			Status.valueOf(result.getString("status")),
			result.getString("destination_id"),
			result.getString("correlation_id"),
			result.getString("lease_owner"),
			instantOrNull(result.getString("lease_until")),
			result.getInt("attempt_count"),
			Instant.parse(result.getString("next_attempt_at")),
			result.getString("last_error_code"),
			Instant.parse(result.getString("created_at")),
			Instant.parse(result.getString("updated_at")),
			instantOrNull(result.getString("completed_at"))
		);
	}

	// 方法：只接受可安全放入租約與日誌欄位的識別碼。
	private String requiredSafeId(String value, String label) {
		if (value == null || !SAFE_ID.matcher(value).matches()) {
			throw new IllegalArgumentException(label + "格式不合法");
		}
		return value;
	}

	// 方法：將外部 request id 正規化為不含個資的穩定關聯識別碼。
	private String safeCorrelationId(String value, long quotationId) {
		return value != null && SAFE_ID.matcher(value).matches()
			? value
			: "quotation-" + quotationId;
	}

	// 方法：使用固定 UTC ISO-8601 格式，讓 SQLite 文字比較保持時間順序。
	private String timestamp(Instant value) {
		return DateTimeFormatter.ISO_INSTANT.format(value);
	}

	// 方法：把可空租約或完成時間轉為 Instant。
	private Instant instantOrNull(String value) {
		return value == null ? null : Instant.parse(value);
	}

	public enum Status {
		PENDING,
		RUNNING,
		FAILED,
		DONE
	}

	public record Job(
		long id,
		long quotationId,
		Status status,
		String destinationId,
		String correlationId,
		String leaseOwner,
		Instant leaseUntil,
		int attemptCount,
		Instant nextAttemptAt,
		String lastErrorCode,
		Instant createdAt,
		Instant updatedAt,
		Instant completedAt
	) {}
}
