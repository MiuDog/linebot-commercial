package dev.miudog.linebotcommercial.desktop;

/**
 * 隔離系統匣控制器與實際 Swing JFrame。
 */
public interface DesktopWindowHandle {

	// 方法：顯示主視窗並移至前景。
	void showWindow();

	// 方法：隱藏主視窗但保持後端執行。
	void hideWindow();
}
