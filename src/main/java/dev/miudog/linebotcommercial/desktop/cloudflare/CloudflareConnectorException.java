package dev.miudog.linebotcommercial.desktop.cloudflare;

/**
 * 表示 Cloudflare Tunnel 無法在 Spring 啟動前提供服務。
 */
public final class CloudflareConnectorException extends RuntimeException {

	// 方法：建立不包含 Token、命令列或敏感資訊的連線例外。
	public CloudflareConnectorException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
