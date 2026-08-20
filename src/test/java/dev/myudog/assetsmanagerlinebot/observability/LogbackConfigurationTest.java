package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigurationTest {

	// 驗證正式日誌採 JSON、寫入專案 log 目錄並具備大小與日期輪替保留。
	@Test
	void configuresJsonRollingFileUnderCommonSystemRoot() throws IOException {
		// 從測試 classpath 讀取正式 Logback 設定，避免只驗證測試替身。
		try (var input = getClass().getResourceAsStream("/logback-spring.xml")) {
			assertThat(input).isNotNull();
			String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(config)
				.contains("${SYSTEM_ROOT_PATH:-${user.dir}/system-data}/log")
				.contains("RollingFileAppender")
				.contains("SizeAndTimeBasedRollingPolicy")
				.contains("ch.qos.logback.classic.encoder.JsonEncoder")
				.contains("maxHistory")
				.contains("totalSizeCap");
		}
	}
}
