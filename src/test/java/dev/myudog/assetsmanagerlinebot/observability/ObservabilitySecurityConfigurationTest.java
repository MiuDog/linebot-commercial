package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilitySecurityConfigurationTest {

	// 驗證框架啟動日誌不洩漏本機使用者與完整路徑，且參數陣列不重複保存。
	@Test
	void disablesSensitiveStartupDetailsAndRawArgumentArrays() throws IOException {
		// 讀取正式環境設定，驗證安全預設值確實隨成品打包。
		String applicationProperties = Files.readString(
			Path.of("src/main/resources/application.properties"),
			StandardCharsets.UTF_8
		);
		String logback = Files.readString(
			Path.of("src/main/resources/logback-spring.xml"),
			StandardCharsets.UTF_8
		);

		assertThat(applicationProperties).contains("spring.main.log-startup-info=false");
		assertThat(logback)
			.contains("<withArguments>false</withArguments>")
			.contains("<withThrowable>false</withThrowable>");
	}

	// 驗證執行期日誌目錄永遠排除於版本控制。
	@Test
	void ignoresRuntimeLogDirectory() throws IOException {
		// 讀取專案忽略規則，防止客戶流程或成本稽核日誌被提交。
		String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);

		assertThat(gitignore).contains("/log/");
	}
}
