package dev.myudog.assetsmanagerlinebot.desktop;

/**
 * 表示桌面殼層管理的 Spring 後端生命週期狀態。
 */
public enum DesktopStatus {
	STOPPED,
	STARTING,
	RUNNING,
	STOPPING,
	FAILED
}
