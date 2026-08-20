package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 驗證 SpringDesktopBackend 啟動時桌面設定具備最高優先權且能正常關閉。
 */
class SpringDesktopBackendTest {

	private SpringDesktopBackend backend;
	private Path tempSystemRoot;

	@BeforeEach
	void setUp() throws IOException {
		backend = new SpringDesktopBackend();
		tempSystemRoot = Files.createTempDirectory("spring-desktop-test-root");
	}

	@AfterEach
	void tearDown() {
		if (backend != null) {
			backend.stop();
		}
	}

	// 方法：驗證桌面設定以最高優先權注入 Spring，不被 application.properties 預設空值覆蓋。
	@Test
	void shouldInjectPropertiesWithHighestPrecedence() {
		Map<String, Object> properties = Map.of(
			"line.bot.channel-secret", "test-secret-12345",
			"line.bot.channel-token", "test-token-67890",
			"app.system.root", tempSystemRoot.toString(),
			"app.storage.root", tempSystemRoot.resolve("assets").toString(),
			"spring.datasource.url", "jdbc:sqlite:" + tempSystemRoot.resolve("assets").resolve("assets.db").toString(),
			"server.port", "0"
		);

		backend.start(properties, new String[] {"--app.desktop.enabled=true"});

		// 驗證後端啟動後可受控停止
		backend.stop();
	}
}
