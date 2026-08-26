package dev.miudog.linebotcommercial.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面設定的欄位分類、預設路徑與不可變操作。
 */
class AppConfigurationTest {

	// 方法：驗證預設設定與資料都放在目前使用者的 Local AppData。
	@Test
	void shouldUseLocalAppDataForDefaultDirectories() {
		Path localAppData = Path.of("C:/Users/test/AppData/Local");
		AppConfiguration configuration = AppConfiguration.defaults(localAppData);

		assertThat(AppConfiguration.configurationRoot(localAppData))
			.isEqualTo(localAppData.resolve("LinebotCommercial/config"));
		assertThat(configuration.value(AppConfigurationField.SYSTEM_ROOT_PATH))
			.isEqualTo(localAppData.resolve("LinebotCommercial/data").toString());
	}

	// 方法：驗證商用報價系統的機密欄位具有單一且完整的分類。
	@Test
	void shouldClassifyEverySpecifiedSecretField() {
		Set<String> secretEnvironmentKeys = AppConfigurationField.secretFields().stream()
			.map(AppConfigurationField::environmentKey)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());

		assertThat(secretEnvironmentKeys).containsExactlyInAnyOrder(
			"LINE_BOT_CHANNEL_TOKEN",
			"LINE_BOT_CHANNEL_SECRET",
			"AI_API_KEY",
			"QUOTATION_POSTBACK_SECRET",
			"QUOTATION_IMAGE_LINK_SECRET",
			"NGROK_AUTHTOKEN",
			"CLOUDFLARE_TUNNEL_TOKEN"
		);
	}

	// 方法：商用報價設定不得顯示文書機的語音或資產同步槽位。
	@Test
	void shouldContainOnlyCommercialProductGroups() {
		assertThat(AppConfigurationField.values())
			.extracting(AppConfigurationField::environmentKey)
			.noneMatch(key -> key.startsWith("VOICE_") || key.startsWith("ASSETS_SYNC_"));
		assertThat(AppConfigurationField.Group.values())
			.extracting(Enum::name)
			.doesNotContain("VOICE");
	}

	// 方法：客戶設定只顯示日常營運必要欄位，隱藏 token 計量與內部效能參數。
	@Test
	void shouldExposeOnlyCustomerRelevantFields() {
		assertThat(AppConfigurationField.customerVisibleFields())
			.contains(
				AppConfigurationField.LINE_BOT_CHANNEL_TOKEN,
				AppConfigurationField.AI_API_KEY,
				AppConfigurationField.AI_MODEL,
				AppConfigurationField.QUOTATION_TAX_RATE,
				AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN
			)
			.doesNotContain(
				AppConfigurationField.AI_PRICE_CURRENCY,
				AppConfigurationField.AI_INPUT_RATE_PER_MILLION,
				AppConfigurationField.AI_CACHED_INPUT_RATE_PER_MILLION,
				AppConfigurationField.AI_OUTPUT_RATE_PER_MILLION,
				AppConfigurationField.QUOTATION_GENERATION_QUEUE_CAPACITY,
				AppConfigurationField.METHOD_TRACING_ENABLED,
				AppConfigurationField.RESOURCE_LOG_INTERVAL_MS
			);
	}

	// 方法：驗證修改設定會建立新物件，不會改變既有設定快照。
	@Test
	void shouldCreateANewSnapshotWhenChangingAValue() {
		AppConfiguration original = AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")));
		AppConfiguration changed = original.withValue(
			AppConfigurationField.LINE_BOT_CHANNEL_TOKEN,
			"test-token"
		);

		assertThat(original.value(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN)).isEmpty();
		assertThat(changed.value(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN)).isEqualTo("test-token");
	}
}

