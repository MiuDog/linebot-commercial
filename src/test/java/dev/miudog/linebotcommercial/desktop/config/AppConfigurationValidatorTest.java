package dev.miudog.linebotcommercial.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面設定的必要欄位與格式規則。
 */
class AppConfigurationValidatorTest {

	private final AppConfigurationValidator validator = new AppConfigurationValidator();

	// 方法：驗證缺少 LINE 憑證時會回報對應欄位。
	@Test
	void shouldRejectMissingRequiredCredentials() {
		AppConfiguration configuration = AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")));

		assertThat(validator.validate(configuration))
			.extracting(AppConfigurationValidator.Violation::field)
			.contains(
				AppConfigurationField.LINE_BOT_CHANNEL_TOKEN,
				AppConfigurationField.LINE_BOT_CHANNEL_SECRET
			);
	}

	// 方法：驗證 URL、Port、正整數、布林值與絕對路徑的不合法內容。
	@Test
	void shouldRejectInvalidFieldFormats() {
		AppConfiguration configuration = validConfiguration()
			.withValue(AppConfigurationField.PUBLIC_BASE_URL, "http://public.example.com")
			.withValue(AppConfigurationField.AI_API_URL, "not-a-url")
			.withValue(AppConfigurationField.SERVER_PORT, "70000")
			.withValue(AppConfigurationField.AI_TIMEOUT_SECONDS, "0")
			.withValue(AppConfigurationField.VOICE_COMMANDS_ENABLED, "sometimes")
			.withValue(AppConfigurationField.SYSTEM_ROOT_PATH, "relative/data");

		assertThat(validator.validate(configuration))
			.extracting(AppConfigurationValidator.Violation::field)
			.containsExactlyInAnyOrder(
				AppConfigurationField.PUBLIC_BASE_URL,
				AppConfigurationField.AI_API_URL,
				AppConfigurationField.SERVER_PORT,
				AppConfigurationField.AI_TIMEOUT_SECONDS,
				AppConfigurationField.VOICE_COMMANDS_ENABLED,
				AppConfigurationField.SYSTEM_ROOT_PATH
			);
	}

	// 方法：驗證完整且合法的最小設定可以通過檢查。
	@Test
	void shouldAcceptAValidMinimumConfiguration() {
		assertThat(validator.validate(validConfiguration())).isEmpty();
	}

	// 方法：建立可通過驗證的最小設定快照。
	// 資料根目錄必須是執行中作業系統認定的絕對路徑；寫死 Windows 磁碟機代號會讓
	// 同一份設定在 Linux CI 被視為相對路徑而驗證失敗，因此改用當地的暫存目錄。
	private AppConfiguration validConfiguration() {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")))
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "test-token")
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_SECRET, "test-secret");
	}
}

