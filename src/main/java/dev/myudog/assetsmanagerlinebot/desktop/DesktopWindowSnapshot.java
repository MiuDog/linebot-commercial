package dev.myudog.assetsmanagerlinebot.desktop;

/**
 * 保存主視窗一次更新所需的完整且不可變狀態。
 */
public record DesktopWindowSnapshot(
	DesktopStatus status,
	String statusText,
	String localUrl,
	String publicUrl,
	String callbackUrl
) {
}
