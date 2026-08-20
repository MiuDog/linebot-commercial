package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 保存單一時間點的不可變桌面設定。
 */
public final class AppConfiguration {

	//#region 欄位

	public static final int CURRENT_SCHEMA_VERSION = 1;

	private static final String PRODUCT_DIRECTORY = "AssetsManagerLinebot";

	private final int schemaVersion;
	private final Map<AppConfigurationField, String> values;

	//#endregion

	//#region 建構子

	// 方法：建立不可變設定快照。
	public AppConfiguration(
		int schemaVersion,
		Map<AppConfigurationField, String> values
	) {
		this.schemaVersion = schemaVersion;
		this.values = immutableValues(values);
	}

	//#endregion

	//#region 方法

	// 方法：以目前使用者的 Local AppData 建立安全預設設定。
	public static AppConfiguration defaults(Path localAppData) {
		Objects.requireNonNull(localAppData, "Local AppData 不可為 null");
		EnumMap<AppConfigurationField, String> values = new EnumMap<>(AppConfigurationField.class);

		// 步驟一：先套用每個欄位宣告的非機密預設值。
		for (AppConfigurationField field : AppConfigurationField.values()) {
			values.put(field, field.defaultValue());
		}

		// 步驟二：資料根目錄必須依目前 Windows 使用者推導。
		values.put(
			AppConfigurationField.SYSTEM_ROOT_PATH,
			localAppData.resolve(PRODUCT_DIRECTORY).resolve("data").toString()
		);

		return new AppConfiguration(CURRENT_SCHEMA_VERSION, values);
	}

	// 方法：取得設定檔專屬根目錄。
	public static Path configurationRoot(Path localAppData) {
		Objects.requireNonNull(localAppData, "Local AppData 不可為 null");

		return localAppData.resolve(PRODUCT_DIRECTORY).resolve("config");
	}

	// 方法：取得設定 schema 版本。
	public int schemaVersion() {
		return schemaVersion;
	}

	// 方法：取得單一欄位值，未設定時回傳空字串。
	public String value(AppConfigurationField field) {
		Objects.requireNonNull(field, "設定欄位不可為 null");

		return values.getOrDefault(field, "");
	}

	// 方法：以新欄位值建立另一份不可變設定快照。
	public AppConfiguration withValue(
		AppConfigurationField field,
		String value
	) {
		Objects.requireNonNull(field, "設定欄位不可為 null");
		EnumMap<AppConfigurationField, String> changedValues = new EnumMap<>(values);

		changedValues.put(field, Objects.requireNonNullElse(value, "").trim());

		return new AppConfiguration(schemaVersion, changedValues);
	}

	// 方法：取得所有欄位的唯讀設定快照。
	public Map<AppConfigurationField, String> values() {
		return values;
	}

	// 方法：建立包含所有欄位且不可修改的映射。
	private Map<AppConfigurationField, String> immutableValues(
		Map<AppConfigurationField, String> source
	) {
		Objects.requireNonNull(source, "設定內容不可為 null");
		EnumMap<AppConfigurationField, String> copiedValues = new EnumMap<>(AppConfigurationField.class);

		for (AppConfigurationField field : AppConfigurationField.values()) {
			copiedValues.put(field, Objects.requireNonNullElse(source.get(field), ""));
		}

		return Collections.unmodifiableMap(copiedValues);
	}

	//#endregion
}

