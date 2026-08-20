package dev.miudog.linebotcommercial.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class NetworkObservationLoggerTest {

	// 驗證外部依賴呼叫會產生可計算 Rate、Error、Duration 的安全事件。
	@Test
	void logsRedEventsWithoutUrlPayloadOrCredentials(CapturedOutput output) {
		NetworkObservationLogger logger = new NetworkObservationLogger();
		long startedAt = logger.started("LINE", "reply_message");

		logger.completed("LINE", "reply_message", startedAt, 200);
		logger.failed("AI", "chat_completion", startedAt, new IOExceptionForTest());

		assertThat(output)
			.contains("event=network_request_started")
			.contains("event=network_request_completed")
			.contains("event=network_request_failed")
			.contains("dependency=LINE")
			.contains("operation=reply_message")
			.contains("statusClass=2xx")
			.contains("durationMs=")
			.contains("errorType=IOExceptionForTest")
			.doesNotContain("Bearer")
			.doesNotContain("https://")
			.doesNotContain("payload");
	}

	private static final class IOExceptionForTest extends RuntimeException {}
}
