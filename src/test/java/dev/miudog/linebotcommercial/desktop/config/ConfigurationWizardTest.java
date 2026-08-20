package dev.miudog.linebotcommercial.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import org.junit.jupiter.api.Test;

/**
 * 驗證 Swing 設定精靈可在無畫面測試環境建立完整欄位。
 */
class ConfigurationWizardTest {

	// 方法：依設定群組建立分頁，並使用密碼控制項顯示機密欄位。
	@Test
	void shouldBuildGroupedTabsAndPasswordControls() {
		ConfigurationWizard wizard = new ConfigurationWizard(
			new ConfigurationWizardModel(validConfiguration()),
			configuration -> {
			}
		);

		assertThat(wizard.content()).isNotNull();
		assertThat(wizard.tabs().getTabCount()).isEqualTo(AppConfigurationField.Group.values().length);
		assertThat(wizard.fieldComponent(AppConfigurationField.AI_API_KEY)).isInstanceOf(JPasswordField.class);
		assertThat(wizard.tabs()).isInstanceOf(JTabbedPane.class);
	}

	// 方法：保存有效編輯時持久化設定並要求重新啟動服務。
	@Test
	void shouldSaveValidEditAndRequestRestart() {
		AppConfiguration[] saved = new AppConfiguration[1];
		ConfigurationWizard wizard = new ConfigurationWizard(
			new ConfigurationWizardModel(validConfiguration()),
			configuration -> saved[0] = configuration
		);

		wizard.fieldComponent(AppConfigurationField.AI_MODEL).setText("gpt-edited");
		ConfigurationWizardResult result = wizard.save(false);

		assertThat(result.saved()).isTrue();
		assertThat(result.restartRequired()).isTrue();
		assertThat(saved[0].value(AppConfigurationField.AI_MODEL)).isEqualTo("gpt-edited");
	}

	// 方法：取消首次設定時不保存且不要求重新啟動。
	@Test
	void shouldCancelFirstConfigurationWithoutStartingService() {
		ConfigurationWizard wizard = new ConfigurationWizard(
			new ConfigurationWizardModel(validConfiguration()),
			configuration -> {
			}
		);

		ConfigurationWizardResult result = wizard.cancel();

		assertThat(result.saved()).isFalse();
		assertThat(result.restartRequired()).isFalse();
	}

	// 方法：沒有顯示中的設定精靈時，lifecycle 關閉操作保持可重入且不拋例外。
	@Test
	void shouldAllowClosingWhenNoWizardIsActive() {
		ConfigurationWizard.closeActive();
		ConfigurationWizard.closeActive();
	}

	// 方法：建立具有必要欄位的測試設定。
	private AppConfiguration validConfiguration() {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")))
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "token")
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_SECRET, "secret");
	}
}
