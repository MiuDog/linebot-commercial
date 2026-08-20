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
				"{\"level\":\"WARN\",\"message\":\"second\"}",
				"{\"level\":\"ERROR\",\"message\":\"third\"}"
			);
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
