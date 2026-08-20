package dev.miudog.linebotcommercial.desktop.ngrok;

/**
 * 表示本 App 所建立 ngrok child process 的生命週期狀態。
 */
public enum NgrokStatus {
	STOPPED,
	STARTING,
	RUNNING,
	FAILED
}
