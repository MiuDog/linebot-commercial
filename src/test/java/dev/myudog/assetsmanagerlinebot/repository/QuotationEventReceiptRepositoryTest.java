package dev.myudog.assetsmanagerlinebot.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationEventReceiptRepositoryTest {

	@Test
	void reclaimsFailedOrExpiredReceiptsButNeverReplaysAProcessedEvent() throws Exception {
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
			JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			QuotationEventReceiptRepository repository = new QuotationEventReceiptRepository(jdbc);

			assertThat(repository.claim("EV-FAILED", null, "MESSAGE")).isTrue();
			assertThat(repository.claim("EV-FAILED", null, "MESSAGE")).isFalse();
			repository.fail("EV-FAILED", null);
			assertThat(repository.blocksReplay("EV-FAILED")).isFalse();
			assertThat(repository.claim("EV-FAILED", null, "MESSAGE")).isTrue();

			assertThat(repository.claim("EV-STALE", null, "MESSAGE")).isTrue();
			jdbc.update("UPDATE quotation_event_receipt SET lease_until = '2000-01-01 00:00:00' WHERE event_id = 'EV-STALE'");
			assertThat(repository.claim("EV-STALE", null, "MESSAGE")).isTrue();

			repository.complete("EV-STALE", null, false);
			assertThat(repository.blocksReplay("EV-STALE")).isTrue();
			assertThat(repository.claim("EV-STALE", null, "MESSAGE")).isFalse();
			assertThat(jdbc.queryForObject(
				"SELECT attempt_count FROM quotation_event_receipt WHERE event_id = 'EV-STALE'",
				Integer.class
			)).isEqualTo(2);
		}
	}
}
