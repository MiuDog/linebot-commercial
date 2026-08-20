package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

/**
 * 表示 ngrok loopback local API 無法提供有效 tunnel 狀態。
 */
public final class NgrokLocalApiException extends RuntimeException {

	// 方法：建立不包含遠端回應內容的 local API 例外。
	public NgrokLocalApiException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
