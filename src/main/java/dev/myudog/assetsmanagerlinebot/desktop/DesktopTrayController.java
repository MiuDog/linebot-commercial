package dev.myudog.assetsmanagerlinebot.desktop;

import java.util.Objects;
import javax.swing.SwingUtilities;

/**
 * 控制關閉視窗、系統匣恢復與不可用時的安全後備行為。
 */
public final class DesktopTrayController {

	//#region 欄位

	private final DesktopWindowHandle window;
	private final DesktopTrayAccess tray;
	private final DesktopActions actions;
	private boolean installed;

	//#endregion

	//#region 建構子

	// 方法：建立指定視窗、系統匣介面與操作集合的控制器。
	public DesktopTrayController(
		DesktopWindowHandle window,
		DesktopTrayAccess tray,
		DesktopActions actions
	) {
		this.window = Objects.requireNonNull(window, "桌面視窗不可為 null");
		this.tray = Objects.requireNonNull(tray, "系統匣介面不可為 null");
		this.actions = Objects.requireNonNull(actions, "桌面操作不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：在 EDT 嘗試安裝系統匣，並回傳能否安全隱藏視窗。
	public boolean install() {
		requireEdt();
		installed = tray.install(actions);

		return installed;
	}

	// 方法：視窗要求關閉時，有系統匣則隱藏，否則保持可見。
	public void windowClosing() {
		requireEdt();

		if (installed) {
			window.hideWindow();
		}
		else {
			window.showWindow();
		}
	}

	// 方法：從第二次開啟或系統匣命令恢復主視窗。
	public void showWindow() {
		requireEdt();
		window.showWindow();
	}

	// 方法：把後端狀態同步至已安裝的系統匣提示文字。
	public void updateStatus(DesktopWindowSnapshot snapshot) {
		requireEdt();

		if (installed) tray.updateStatus(snapshot);
	}

	// 方法：移除已安裝的系統匣資源。
	public void close() {
		requireEdt();

		if (installed) tray.remove();

		installed = false;
	}

	// 方法：強制所有 Swing 與 AWT 系統匣操作只在 EDT 執行。
	private void requireEdt() {
		if (!SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("桌面元件必須在 Swing EDT 操作");
		}
	}

	//#endregion
}
