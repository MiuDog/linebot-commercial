package dev.myudog.assetsmanagerlinebot.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面設定會完整映射為 Spring 啟動屬性。
 */
class DesktopSpringPropertiesTest {

	// 方法：驗證一般與解密後機密欄位都映射至宣告的 Spring property key。
	@Test
	void shouldMapEveryConfigurationFieldToItsSpringProperty() {
		AppConfiguration configuration = AppConfiguration.defaults(Path.of("C:/local"))
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "line-token")
			.withValue(AppConfigurationField.QUOTATION_POSTBACK_SECRET, "postback-secret");

		Map<String, Object> properties = new DesktopSpringProperties().from(configuration);

		assertThat(properties)
			.containsEntry("line.bot.channel-token", "line-token")
			.containsEntry("QUOTATION_POSTBACK_SECRET", "postback-secret")
			.containsEntry("server.port", "8088")
			.hasSize(AppConfigurationField.values().length);
	}
}

