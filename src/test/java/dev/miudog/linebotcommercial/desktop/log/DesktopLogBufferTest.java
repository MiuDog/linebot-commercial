package dev.miudog.linebotcommercial.desktop.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 驗證桌面 Log 固定容量、等級搜尋與敏感資料再次遮罩。
 */
class DesktopLogBufferTest {

	// 方法：超過容量時只保留最新記錄且不無限成長。
	@Test
	void shouldKeepOnlyTheNewestEntriesWithinCapacity() {
		DesktopLogBuffer buffer = new DesktopLogBuffer(2);

		buffer.add("{\"level\":\"INFO\",\"message\":\"first\"}");
		buffer.add("{\"level\":\"WARN\",\"message\":\"second\"}");
		buffer.add("{\"level\":\"ERROR\",\"message\":\"third\"}");

		assertThat(buffer.entries("ALL", ""))
			.extracting(DesktopLogEntry::text)
			.containsExactly(
				"[WARN] [Application] second",
				"[ERROR] [Application] third"
			);
	}

	// 方法：JSON Log 只顯示短元件名稱與訊息，不把內部欄位外殼交給客戶閱讀。
	@Test
	void shouldFormatJsonAsReadableDesktopText() {
		DesktopLogBuffer buffer = new DesktopLogBuffer(10);

		buffer.add("{\"timestamp\":0,\"level\":\"INFO\",\"loggerName\":\"dev.example.LineWebhookController\",\"message\":\"event=line_request_completed durationMs={}\",\"formattedMessage\":\"event=line_request_completed durationMs=12\",\"sequenceNumber\":9}");

		assertThat(buffer.entries("ALL", "")).singleElement().satisfies(entry -> assertThat(entry.text())
			.isEqualTo("[1970-01-01T00:00:00Z] [INFO] [LineWebhookController] event=line_request_completed durationMs=12")
			.doesNotContain("sequenceNumber", "loggerName"));
	}

	// 方法：損毀的超大 timestamp 不可中斷後續 Log 顯示。
	@Test
	void shouldIgnoreAnInvalidTimestamp() {
		DesktopLogBuffer buffer = new DesktopLogBuffer(10);

		buffer.add("{\"timestamp\":999999999999999999999,\"level\":\"INFO\",\"message\":\"ready\"}");

		assertThat(buffer.entries("ALL", "")).singleElement().satisfies(entry -> assertThat(entry.text())
			.isEqualTo("[INFO] [Application] ready"));
	}

	// 方法：依等級與文字搜尋篩選，並遮罩常見 JSON 密鑰與 Bearer Token。
	@Test
	void shouldFilterAndRedactSensitiveValues() {
		DesktopLogBuffer buffer = new DesktopLogBuffer(10);

		buffer.add("{\"level\":\"INFO\",\"message\":\"ready\"}");
		buffer.add("{\"level\":\"ERROR\",\"apiKey\":\"secret-value\",\"message\":\"failed Bearer abc123\"}");

		assertThat(buffer.entries("ERROR", "failed")).singleElement().satisfies(entry -> {
			assertThat(entry.text()).contains("[REDACTED]");
			assertThat(entry.text()).doesNotContain("secret-value", "abc123");
		});
	}
}
