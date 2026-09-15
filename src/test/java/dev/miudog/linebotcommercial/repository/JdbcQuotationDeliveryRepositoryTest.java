package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryClaim;
import dev.miudog.linebotcommercial.service.quotation.QuotationDeliverySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcQuotationDeliveryRepositoryTest {

	@Test
	void persistsFailureRetryCountAndIdempotentSuccess() {
		JdbcTemplate jdbc = jdbc();
		createSchema(jdbc);
		insertReadyQuotation(jdbc);
		JdbcQuotationDeliveryRepository repository = new JdbcQuotationDeliveryRepository(jdbc);

		QuotationDeliverySnapshot snapshot = repository.findSnapshot(41);
		QuotationDeliveryClaim first = repository.claimFinal(41, "U-line-user");
		QuotationDeliveryClaim concurrent = repository.claimFinal(41, "U-line-user");
		repository.markFailed(first.attemptId(), "LineMessagingException:LINE_HTTP_ERROR");
		QuotationDeliveryClaim retry = repository.claimFinal(41, "U-line-user");
		repository.markSent(retry.attemptId(), "provider-message-2");
		QuotationDeliveryClaim repeated = repository.claimFinal(41, "U-line-user");

		assertThat(snapshot.totalAmount()).isEqualByComparingTo("105000");
		assertThat(first.attemptCount()).isEqualTo(1);
		assertThat(concurrent.state()).isEqualTo(QuotationDeliveryClaim.State.IN_PROGRESS);
		assertThat(retry.attemptCount()).isEqualTo(2);
		assertThat(repeated.state()).isEqualTo(QuotationDeliveryClaim.State.ALREADY_SENT);
		assertThat(repeated.providerMessageId()).isEqualTo("provider-message-2");
		List<Map<String, Object>> attempts = jdbc.queryForList("""
			SELECT status, error_message, provider_message_id
			FROM quotation_delivery_attempt
			ORDER BY id
			""");
		assertThat(attempts).hasSize(2);
		assertThat(attempts.get(0))
			.containsEntry("status", "FAILED")
			.containsEntry("error_message", "LineMessagingException:LINE_HTTP_ERROR");
		assertThat(attempts.get(1))
			.containsEntry("status", "SENT")
			.containsEntry("provider_message_id", "provider-message-2");
	}

	@Test
	void expiresAStaleSendingLeaseAndAllowsANewAttemptAfterCrash() {
		JdbcTemplate jdbc = jdbc();
		createSchema(jdbc);
		insertReadyQuotation(jdbc);
		jdbc.update("UPDATE quotation SET status = 'SENDING' WHERE id = 41");
		jdbc.update("""
			INSERT INTO quotation_delivery_attempt (
				quotation_id, destination_type, destination_id, delivery_kind, status, attempted_at
			)
			VALUES (41, 'LINE_USER', 'U-line-user', 'FINAL', 'SENDING', '2000-01-01 00:00:00')
			""");
		JdbcQuotationDeliveryRepository repository = new JdbcQuotationDeliveryRepository(jdbc);

		QuotationDeliveryClaim recovered = repository.claimFinal(41, "U-line-user");

		assertThat(recovered.state()).isEqualTo(QuotationDeliveryClaim.State.READY);
		assertThat(recovered.attemptCount()).isEqualTo(2);
		assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation_delivery_attempt WHERE id = 1",
			String.class
		)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject(
			"SELECT error_message FROM quotation_delivery_attempt WHERE id = 1",
			String.class
		)).isEqualTo("DELIVERY_LEASE_EXPIRED");
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
	}

	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("""
			CREATE TABLE quotation (
				id INTEGER PRIMARY KEY,
				company_name TEXT,
				work_name TEXT,
				quotation_no TEXT,
				currency TEXT,
				subtotal NUMERIC,
				tax_amount NUMERIC,
				total_amount NUMERIC,
				status TEXT,
				created_at TEXT DEFAULT CURRENT_TIMESTAMP
			)
			""");
		jdbc.execute("""
			CREATE TABLE quotation_file (
				id INTEGER PRIMARY KEY,
				quotation_id INTEGER,
				file_kind TEXT,
				status TEXT
			)
			""");
		jdbc.execute("""
			CREATE TABLE quotation_delivery_attempt (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				quotation_id INTEGER NOT NULL,
				destination_type TEXT NOT NULL,
				destination_id TEXT NOT NULL,
				delivery_kind TEXT NOT NULL,
				status TEXT NOT NULL,
				provider_message_id TEXT,
				error_message TEXT,
				attempted_at TEXT DEFAULT CURRENT_TIMESTAMP,
				completed_at TEXT
			)
			""");
		jdbc.execute("""
			CREATE UNIQUE INDEX uq_quotation_delivery_active
			ON quotation_delivery_attempt (
				quotation_id, destination_type, destination_id, delivery_kind
			)
			WHERE status IN ('PENDING', 'SENDING', 'SENT')
			""");
	}

	private void insertReadyQuotation(JdbcTemplate jdbc) {
		jdbc.update("""
			INSERT INTO quotation (
				id, company_name, work_name, quotation_no, currency,
				subtotal, tax_amount, total_amount, status
			)
			VALUES (41, '範例工程', '港區搭架', '2026081101', 'TWD', 100000, 5000, 105000, 'READY')
			""");
		jdbc.update("""
			INSERT INTO quotation_file (id, quotation_id, file_kind, status)
			VALUES (51, 41, 'PDF', 'READY')
			""");
	}
}
