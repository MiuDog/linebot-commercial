package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 到期的模型步驟快取；內容與草稿資料採用相同資料庫存取邊界。 */
@Repository
public class QuotationModelCheckpointStore {

	private final JdbcTemplate jdbc;

	// 方法：注入既有資料庫，不建立另一套連線與持久化服務。
	public QuotationModelCheckpointStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：僅讀取未到期的成功步驟，雜湊鍵已包含使用者與輸入版本。
	public Checkpoint find(String key) {
		List<Checkpoint> rows = jdbc.query("SELECT payload, output_budget, total_tokens, execution_key FROM quotation_model_checkpoint WHERE cache_key = ? AND expires_at > ?",
			(row, index) -> new Checkpoint(row.getString(1), row.getInt(2), row.getLong(3), row.getString(4)), key, System.currentTimeMillis());
		return rows.isEmpty() ? null : rows.getFirst();
	}

	// 方法：使用 PostgreSQL／SQLite 都支援的 upsert 保存可重用步驟並清理過期內容。
	public void save(String key, String payload, int outputBudget, long totalTokens, String executionKey) {
		long now = System.currentTimeMillis();
		jdbc.update("""
			INSERT INTO quotation_model_checkpoint(cache_key, payload, output_budget, total_tokens, execution_key, expires_at) VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT(cache_key) DO UPDATE SET payload = excluded.payload, output_budget = excluded.output_budget,
			 total_tokens = excluded.total_tokens, execution_key = excluded.execution_key, expires_at = excluded.expires_at
			""", key, payload, outputBudget, totalTokens, executionKey, now + java.time.Duration.ofDays(7).toMillis());
	}

	// 方法：定期清除逾期內容，沒有新請求時也不會永久保留 OCR 與客戶文字。
	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
	public void deleteExpired() {
		jdbc.update("DELETE FROM quotation_model_checkpoint WHERE expires_at <= ?", System.currentTimeMillis());
	}

	public record Checkpoint(String content, int outputBudget, long totalTokens, String executionKey) {}
}
