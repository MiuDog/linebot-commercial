package dev.myudog.assetsmanagerlinebot.desktop;

/**
 * 隔離系統匣控制流程與 AWT 作業系統資源。
 */
public interface DesktopTrayAccess {

	// 方法：嘗試安裝系統匣圖示與選單。
	boolean install(DesktopActions actions);

	// 方法：同步系統匣提示文字與目前服務狀態。
	void updateStatus(DesktopWindowSnapshot snapshot);

	// 方法：移除系統匣圖示並釋放作業系統資源。
	void remove();
}
