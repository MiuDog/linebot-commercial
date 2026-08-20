package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 將桌面設定快照轉換為 Spring Boot 啟動屬性。
 */
public final class DesktopSpringProperties {

	//#region 方法

	// 方法：依欄位中繼資料建立完整且不可變更的 Spring 屬性集合。
	public Map<String, Object> from(AppConfiguration configuration) {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		Map<String, Object> properties = new LinkedHashMap<>();

		// 單一演算法：保持欄位宣告順序並映射所有 Spring property key。
		for (AppConfigurationField field : AppConfigurationField.values()) {
			properties.put(field.propertyKey(), configuration.value(field));
		}

		return Map.copyOf(properties);
	}

	//#endregion
}
