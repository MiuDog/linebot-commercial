package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

import static org.assertj.core.api.Assertions.assertThat;

import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfiguration;
import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfigurationField;
import dev.myudog.assetsmanagerlinebot.desktop.config.DesktopSpringProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 驗證 ngrok 連線結果可直接映射為 Spring 啟動前的公開網址。
 */
class DesktopNgrokIntegrationTest {

	// 方法：connector 寫入的公開網址會映射至既有 app.public-base-url。
	@Test
	void shouldMapConnectedPublicUrlToSpringProperties() {
		AppConfiguration configuration = AppConfiguration.defaults(Path.of("C:/local"))
			.withValue(AppConfigurationField.PUBLIC_BASE_URL, "https://example.ngrok.app");

		assertThat(new DesktopSpringProperties().from(configuration))
			.containsEntry("app.public-base-url", "https://example.ngrok.app");
	}
}
