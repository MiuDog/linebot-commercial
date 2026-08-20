package dev.myudog.assetsmanagerlinebot.desktop.config;

/**
 * 表示機密資料無法由目前平台安全保護或還原。
 */
public final class SecretProtectionException extends RuntimeException {

	// 方法：建立不包含機密內容的保護失敗例外。
	public SecretProtectionException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}

