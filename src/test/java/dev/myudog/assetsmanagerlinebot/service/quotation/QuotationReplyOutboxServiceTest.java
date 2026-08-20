package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuotationReplyOutboxServiceTest {

	// 測試：工作流程可先把回覆持久化，而不在資料庫交易內呼叫 LINE。
	@Test
	void stagesReplyWithoutSendingIt() {
		JdbcTemplate jdbc = jdbc();
		createSchema(jdbc);
		LineStorageService line = mock(LineStorageService.class);
		QuotationReplyOutboxService service = new QuotationReplyOutboxService(jdbc, line, new ObjectMapper());
		List<Map<String, Object>> messages = List.of(Map.of("type", "text", "text", "請補資料"));

		service.stage("EV-STAGE", "U-line-user", messages);

		assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation_reply_outbox WHERE event_id = 'EV-STAGE'",
			String.class
		)).isEqualTo("PENDING");
		verifyNoInteractions(line);
	}

	@Test
	void keepsMutationReplyPendingAndResendsItSeparatelyOnDuplicateEvent() {
		JdbcTemplate jdbc = jdbc();
		createSchema(jdbc);
		LineStorageService line = mock(LineStorageService.class);
		List<Map<String, Object>> messages = List.of(Map.of("type", "text", "text", "請補資料"));
		doThrow(new LineStorageService.LineMessagingException("LINE_REPLY_HTTP_ERROR", "reply failed"))
			.when(line)
			.reply("expired-reply-token", messages);
		when(line.push(eq("U-line-user"), eq(messages), any(UUID.class)))
			.thenThrow(new LineStorageService.LineMessagingException("LINE_HTTP_ERROR", "push failed"))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message"));
		QuotationReplyOutboxService service = new QuotationReplyOutboxService(
			jdbc,
			line,
			new ObjectMapper()
		);

		assertThatThrownBy(() -> service.deliver(
			"EV-1",
			"U-line-user",
			"expired-reply-token",
			messages
		)).isInstanceOf(LineStorageService.LineMessagingException.class);
		service.retryPending("EV-1");

		Map<String, Object> row = jdbc.queryForMap("""
			SELECT status, attempt_count, last_error_code
			FROM quotation_reply_outbox
			WHERE event_id = 'EV-1'
			""");
		assertThat(row)
			.containsEntry("status", "SENT")
			.containsEntry("attempt_count", 3)
			.containsEntry("last_error_code", null);
		ArgumentCaptor<UUID> retryKeys = ArgumentCaptor.forClass(UUID.class);
		verify(line, org.mockito.Mockito.times(2)).push(
			eq("U-line-user"),
			eq(messages),
			retryKeys.capture()
		);
		assertThat(retryKeys.getAllValues()).containsOnly(retryKeys.getValue());
	}

	@Test
	void drainsPendingRepliesAfterAServiceRestartWithoutWaitingForWebhookRedelivery() {
		JdbcTemplate jdbc = jdbc();
		createSchema(jdbc);
		LineStorageService line = mock(LineStorageService.class);
		List<Map<String, Object>> messages = List.of(Map.of("type", "text", "text", "請補資料"));
		doThrow(new LineStorageService.LineMessagingException("LINE_REPLY_HTTP_ERROR", "reply failed"))
			.when(line)
			.reply("expired-reply-token", messages);
		when(line.push(eq("U-line-user"), eq(messages), any(UUID.class)))
			.thenThrow(new LineStorageService.LineMessagingException("LINE_HTTP_ERROR", "push failed"))
			.thenReturn(new LineStorageService.LinePushReceipt("provider-message"));
		QuotationReplyOutboxService beforeRestart = new QuotationReplyOutboxService(jdbc, line, new ObjectMapper());

		assertThatThrownBy(() -> beforeRestart.deliver(
			"EV-RESTART",
			"U-line-user",
			"expired-reply-token",
			messages
		)).isInstanceOf(LineStorageService.LineMessagingException.class);

		QuotationReplyOutboxService afterRestart = new QuotationReplyOutboxService(jdbc, line, new ObjectMapper());
		afterRestart.drainPending();

		assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation_reply_outbox WHERE event_id = 'EV-RESTART'",
			String.class
		)).isEqualTo("SENT");
	}

	// 方法：建立 outbox 聚焦測試使用的單一 SQLite 連線。
	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
	}

	// 方法：建立與正式 migration 相同的最小 outbox 結構。
	private void createSchema(JdbcTemplate jdbc) {
		jdbc.execute("""
			CREATE TABLE quotation_reply_outbox (
				event_id TEXT PRIMARY KEY,
				destination_id TEXT NOT NULL,
				messages_json TEXT NOT NULL,
				status TEXT NOT NULL DEFAULT 'PENDING',
				attempt_count INTEGER NOT NULL DEFAULT 0,
				last_error_code TEXT,
				created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
				completed_at TEXT
			)
			""");
	}
}
