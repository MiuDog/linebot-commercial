package dev.miudog.linebotcommercial.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedEnvironmentConfigurationTest {

	@Test
	void derivesEveryFilesystemLocationFromOneSystemRoot() throws IOException {
		String environment = read(".env.example");
		String properties = read("src/main/resources/application.properties");
		String compose = read("docker-compose.yml");
		String dockerfile = read("Dockerfile");

		assertThat(environment)
			.contains("SYSTEM_ROOT_PATH=")
			.doesNotContain("ASSETS_ROOT=")
			.doesNotContain("QUOTATION_ROOT_PATH=")
			.doesNotContain("QUOTATION_OUTPUT_PATH=")
			.doesNotContain("QUOTATION_TEMPLATE_PATH=")
			.doesNotContain("LOG_PATH=");
		assertThat(properties)
			.contains("app.system.root=${SYSTEM_ROOT_PATH:${user.dir}/system-data}")
			.contains("app.storage.root=${app.system.root}/")
			.contains("app.quotation.root-path=${app.system.root}")
			.contains("app.quotation.template-path=classpath:quotation/templates")
			.contains("app.observability.log-path=${app.system.root}/log");
		assertThat(compose)
			.contains("${SYSTEM_ROOT_PATH:-./system-data}:/data/system-root")
			.contains("SYSTEM_ROOT_PATH=/data/system-root")
			.contains("LOCAL_ADMIN_CONTAINER_HOST_ACCESS=true")
			.contains("127.0.0.1:8088:8088")
			.doesNotContain("ASSETS_ROOT=")
			.doesNotContain("QUOTATION_ROOT_PATH=");
		assertThat(dockerfile)
			.contains("COPY outputs/excel-templates ./outputs/excel-templates")
			.contains("VOLUME /data/system-root")
			.doesNotContain("VOLUME /data/assets");
	}

	// 方法：報價 AI 只使用共同的三個設定；語音任務屬於文書機器人，此產品不得殘留其設定。
	@Test
	void sharesTheCommonAiSettingsAndKeepsNoVoiceConfiguration() throws IOException {
		String environment = read(".env.example");
		String properties = read("src/main/resources/application.properties");

		assertThat(environment)
			.contains("AI_API_URL=")
			.contains("AI_API_KEY=")
			.contains("AI_MODEL=")
			.contains("AI_TIMEOUT_SECONDS=60")
			.doesNotContain("OPENAI_API_KEY=")
			.doesNotContain("OPENAI_API_BASE_URL=");
		assertThat(properties)
			.contains("app.ai.api-url=${AI_API_URL:}")
			.contains("app.ai.api-key=${AI_API_KEY:}")
			.contains("app.ai.model=${AI_MODEL:}")
			.contains("app.ai.timeout-seconds=${AI_TIMEOUT_SECONDS:60}")
			.doesNotContain("app.voice.")
			.doesNotContain("OPENAI_API_KEY")
			.doesNotContain("OPENAI_API_BASE_URL");
	}

	@Test
	void loadsChineseDerivedDirectoryNamesWithoutMojibake() throws IOException {
		Properties properties = new Properties();

		// 依照 Java properties 的實際規則載入，防止 UTF-8 中文被誤讀成亂碼。
		try (var input = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
			properties.load(input);
		}

		assertThat(properties.getProperty("app.storage.root"))
			.isEqualTo("${app.system.root}/圖片資產");
		assertThat(properties.getProperty("app.quotation.output-path"))
			.isEqualTo("${app.system.root}/報價單");
	}

	// 方法：以 UTF-8 讀取受測設定檔。
	private String read(String path) throws IOException {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
