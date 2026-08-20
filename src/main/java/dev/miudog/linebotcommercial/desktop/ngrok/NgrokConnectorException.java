package dev.miudog.linebotcommercial.desktop.ngrok;

/**
 * 表示 ngrok 無法在 Spring 啟動前提供新的 HTTPS 公開網址。
 */
public final class NgrokConnectorException extends RuntimeException {

	// 方法：建立不包含 Authtoken、命令列或 local API body 的連線例外。
	public NgrokConnectorException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
