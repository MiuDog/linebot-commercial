package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

/**
 * 表示 ngrok child process 無法安全啟動或停止。
 */
public final class NgrokProcessException extends RuntimeException {

	// 方法：建立不包含 Authtoken 或完整命令列的程序例外。
	public NgrokProcessException(
		String message,
		Throwable cause
	) {
		super(message, cause);
	}
}
