package dev.myudog.assetsmanagerlinebot.desktop.config;

/**
 * 表示桌面設定檔無法安全讀取或寫入。
 */
public final class ConfigurationPersistenceException extends RuntimeException {

	// 方法：建立不包含設定內容的持久化錯誤。
	public ConfigurationPersistenceException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
