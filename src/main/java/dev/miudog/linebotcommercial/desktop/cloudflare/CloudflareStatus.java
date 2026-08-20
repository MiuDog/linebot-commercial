package dev.miudog.linebotcommercial.desktop.cloudflare;

/**
 * 表示本 App 所建立 cloudflared child process 的生命週期狀態。
 */
public enum CloudflareStatus {
	STOPPED,
	STARTING,
	RUNNING,
	FAILED
}
