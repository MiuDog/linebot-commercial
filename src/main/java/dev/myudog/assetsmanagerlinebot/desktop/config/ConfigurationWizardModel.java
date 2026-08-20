package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.util.List;
import java.util.Objects;

/**
 * 管理首次設定與編輯設定共用的欄位狀態及驗證規則。
 */
public final class ConfigurationWizardModel {

	//#region 欄位

	public static final String SECRET_MASK = "••••••••";

	private final AppConfiguration original;
	private final AppConfigurationValidator validator;
	private AppConfiguration configuration;

	//#endregion

	//#region 建構子

	// 方法：以指定設定快照建立可編輯模型。
	public ConfigurationWizardModel(AppConfiguration configuration) {
		this(configuration, new AppConfigurationValidator());
	}

	// 方法：以可替換驗證器建立方便測試的設定模型。
	ConfigurationWizardModel(
		AppConfiguration configuration,
		AppConfigurationValidator validator
	) {
		this.original = Objects.requireNonNull(configuration, "桌面設定不可為 null");
		this.configuration = configuration;
		this.validator = Objects.requireNonNull(validator, "設定驗證器不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：取得適合顯示於一般或密碼控制項的欄位內容。
	public String displayValue(AppConfigurationField field) {
		String value = configuration.value(field);

		if (field.secret() && !value.isBlank()) return SECRET_MASK;

		return value;
	}

	// 方法：套用使用者輸入，機密欄留空或保留遮罩時沿用原值。
	public void update(
		AppConfigurationField field,
		String value
	) {
		Objects.requireNonNull(field, "設定欄位不可為 null");
		String normalizedValue = Objects.requireNonNullElse(value, "").trim();

		if (field.secret() && (normalizedValue.isBlank() || SECRET_MASK.equals(normalizedValue))) {
			configuration = configuration.withValue(field, original.value(field));
			return;
		}

		configuration = configuration.withValue(field, normalizedValue);
	}

	// 方法：取得目前編輯中的完整設定快照。
	public AppConfiguration configuration() {
		return configuration;
	}

	// 方法：驗證目前設定並回傳可定位至控制項的錯誤。
	public List<AppConfigurationValidator.Violation> violations() {
		return validator.validate(configuration);
	}

	//#endregion
}
