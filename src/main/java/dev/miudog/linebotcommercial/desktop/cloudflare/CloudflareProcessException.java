package dev.miudog.linebotcommercial.desktop.cloudflare;

/**
 * 表示 cloudflared child process 無法安全啟動或停止。
 */
public final class CloudflareProcessException extends RuntimeException {

	// 方法：建立不包含 Token 或完整命令列的程序例外。
	public CloudflareProcessException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
