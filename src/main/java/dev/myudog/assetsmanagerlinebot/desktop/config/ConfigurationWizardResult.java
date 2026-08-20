package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.util.List;
import java.util.Objects;

/**
 * 表示設定精靈保存、驗證失敗或取消後的結果。
 */
public record ConfigurationWizardResult(
	boolean saved,
	boolean restartRequired,
	AppConfiguration configuration,
	List<AppConfigurationValidator.Violation> violations
) {

	// 方法：建立內容完整且不可變更的設定精靈結果。
	public ConfigurationWizardResult {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		violations = List.copyOf(violations);
	}

	// 方法：建立成功保存的設定精靈結果。
	public static ConfigurationWizardResult saved(
		AppConfiguration configuration,
		boolean restartRequired
	) {
		return new ConfigurationWizardResult(true, restartRequired, configuration, List.of());
	}

	// 方法：建立使用者取消且不啟動或重啟服務的結果。
	public static ConfigurationWizardResult cancelled(AppConfiguration configuration) {
		return new ConfigurationWizardResult(false, false, configuration, List.of());
	}

	// 方法：建立設定驗證失敗且保持精靈開啟的結果。
	public static ConfigurationWizardResult invalid(
		AppConfiguration configuration,
		List<AppConfigurationValidator.Violation> violations
	) {
		return new ConfigurationWizardResult(false, false, configuration, violations);
	}
}
