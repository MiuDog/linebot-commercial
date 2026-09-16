package dev.miudog.linebotcommercial.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedEnvironmentConfigurationTest {

	// 方法：正式入口只能啟動 Spring Boot，不保留任何桌面或服務監督模式。
	@Test
	void usesHeadlessSpringBootAsTheOnlyRuntime() throws IOException {
		String application = read("src/main/java/dev/miudog/linebotcommercial/LinebotCommercialApplication.java");
		String properties = read("src/main/resources/application.properties");

		assertThat(application)
			.contains("SpringApplication.run(LinebotCommercialApplication.class, args)")
			.doesNotContain("DesktopApplication")
			.doesNotContain("ServiceApplication")
			.doesNotContain("ApplicationRuntimeMode");
		assertThat(Path.of("src/main/java/dev/miudog/linebotcommercial/desktop")).doesNotExist();
		assertThat(Path.of("packaging/windows")).doesNotExist();
		assertThat(Path.of(".github/workflows/release-windows.yml")).doesNotExist();
		assertThat(properties)
			.contains("management.endpoint.health.probes.enabled=true")
			.contains("management.endpoint.health.probes.add-additional-paths=true")
			.contains("server.shutdown=graceful")
			.contains("spring.lifecycle.timeout-per-shutdown-phase=${SHUTDOWN_TIMEOUT:30s}");
	}

	// 方法：部署只將 PostgreSQL 與物件儲存視為持久狀態，不再掛載桌面資料目錄或內嵌公司資產。
	@Test
	void keepsPersistentStateOutsideTheApplicationImage() throws IOException {
		String environment = read(".env.example");
		String properties = read("src/main/resources/application.properties");
		String compose = read("docker-compose.yml");
		String dockerfile = read("Dockerfile");

		assertThat(environment)
			.contains("COMPANY_ID=")
			.contains("OBJECT_STORAGE_BUCKET=")
			.contains("SECRETS_DIR=")
			.doesNotContain("AI_API_KEY=")
			.doesNotContain("DATABASE_PASSWORD=")
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
			.contains("SYSTEM_ROOT_PATH: /tmp/linebot")
			.contains("database-data:/var/lib/postgresql/data")
			.contains("object-storage-data:/data")
			.contains("LOCAL_ADMIN_CONTAINER_HOST_ACCESS: \"true\"")
			.contains("127.0.0.1:${APP_PORT:-8088}:8088")
			.doesNotContain("./system-data")
			.doesNotContain("ASSETS_ROOT=")
			.doesNotContain("QUOTATION_ROOT_PATH=");
		assertThat(dockerfile)
			.doesNotContain("COPY outputs")
			.doesNotContain("VOLUME ");
	}

	// 方法：報價 AI 只使用共同的三個設定；語音任務屬於文書機器人，此產品不得殘留其設定。
	@Test
	void sharesTheCommonAiSettingsAndKeepsNoVoiceConfiguration() throws IOException {
		String environment = read(".env.example");
		String properties = read("src/main/resources/application.properties");

		assertThat(environment)
			.contains("AI_API_URL=")
			.contains("AI_MODEL=")
			.doesNotContain("AI_API_KEY=")
			.doesNotContain("AI_TIMEOUT_SECONDS=")
			.doesNotContain("AI_PRICE_CURRENCY=")
			.doesNotContain("AI_INPUT_RATE_PER_MILLION=")
			.doesNotContain("AI_CACHED_INPUT_RATE_PER_MILLION=")
			.doesNotContain("AI_OUTPUT_RATE_PER_MILLION=")
			.doesNotContain("QUOTATION_GENERATION_QUEUE_CAPACITY=")
			.doesNotContain("METHOD_TRACING_ENABLED=")
			.doesNotContain("RESOURCE_LOG_INTERVAL_MS=")
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
			.isEqualTo("${app.system.root}/data");
		assertThat(properties.getProperty("app.quotation.output-path"))
			.isEqualTo("${app.system.root}/報價單");
	}

	// 方法：以 UTF-8 讀取受測設定檔。
	private String read(String path) throws IOException {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
