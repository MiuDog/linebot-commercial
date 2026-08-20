package dev.myudog.assetsmanagerlinebot.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 驗證設定精靈模型的必要欄位、密碼遮罩與編輯語意。
 */
class ConfigurationWizardModelTest {

	// 方法：首次設定缺少必要欄位時回傳可定位欄位的錯誤。
	@Test
	void shouldRejectMissingRequiredFieldsDuringFirstConfiguration() {
		ConfigurationWizardModel model = new ConfigurationWizardModel(defaults());

		assertThat(model.violations())
			.extracting(AppConfigurationValidator.Violation::field)
			.contains(
				AppConfigurationField.LINE_BOT_CHANNEL_TOKEN,
				AppConfigurationField.LINE_BOT_CHANNEL_SECRET
			);
	}

	// 方法：編輯時密碼欄留空或維持遮罩都不覆蓋既有機密。
	@Test
	void shouldPreserveExistingSecretWhenMaskedOrBlank() {
		AppConfiguration original = validConfiguration()
			.withValue(AppConfigurationField.AI_API_KEY, "existing-secret");
		ConfigurationWizardModel model = new ConfigurationWizardModel(original);

		assertThat(model.displayValue(AppConfigurationField.AI_API_KEY))
			.isEqualTo(ConfigurationWizardModel.SECRET_MASK);

		model.update(AppConfigurationField.AI_API_KEY, "");
		assertThat(model.configuration().value(AppConfigurationField.AI_API_KEY)).isEqualTo("existing-secret");

		model.update(AppConfigurationField.AI_API_KEY, ConfigurationWizardModel.SECRET_MASK);
		assertThat(model.configuration().value(AppConfigurationField.AI_API_KEY)).isEqualTo("existing-secret");
	}

	// 方法：輸入新密碼時以新值取代舊機密並通過驗證。
	@Test
	void shouldReplaceSecretWhenNewValueIsProvided() {
		ConfigurationWizardModel model = new ConfigurationWizardModel(validConfiguration());

		model.update(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "new-token");

		assertThat(model.configuration().value(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN))
			.isEqualTo("new-token");
		assertThat(model.violations()).isEmpty();
	}

	// 方法：建立測試用預設設定。
	private AppConfiguration defaults() {
		return AppConfiguration.defaults(Path.of("C:/local"));
	}

	// 方法：建立具有必要 LINE 機密的有效設定。
	private AppConfiguration validConfiguration() {
		return defaults()
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "token")
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_SECRET, "secret");
	}
}
