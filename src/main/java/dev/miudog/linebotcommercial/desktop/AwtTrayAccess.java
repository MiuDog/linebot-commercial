package dev.miudog.linebotcommercial.desktop;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 AWT SystemTray 實作 Windows 系統匣圖示與操作選單。
 */
public final class AwtTrayAccess implements DesktopTrayAccess {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(AwtTrayAccess.class);

	private static final Font MENU_FONT = resolveMenuFont();

	private TrayIcon trayIcon;
	private JPopupMenu popupMenu;
	private JWindow popupWindow;

	//#endregion

	//#region 方法

	// 方法：建立顯示、設定、重新啟動與結束選單並安裝系統匣圖示。
	@Override
	public boolean install(DesktopActions actions) {
		if (!SystemTray.isSupported()) return false;

		// Swing 函式庫：建立可明確套用中文字型的選單，避免 Windows 原生 AWT 選單顯示方塊字。
		popupMenu = createPopupMenu(actions);

		// AWT 函式庫：建立使用 commercial 品牌素材的系統匣圖示。
		trayIcon = new TrayIcon(DesktopIconLoader.load(), "Linebot Commercial");
		trayIcon.setImageAutoSize(true);
		trayIcon.addActionListener(event -> SwingUtilities.invokeLater(actions.show()));
		trayIcon.addMouseListener(new MouseAdapter() {

			// 方法：在使用者以右鍵操作系統匣圖示時顯示 Swing 中文選單。
			@Override
			public void mouseReleased(MouseEvent event) {
				if (!event.isPopupTrigger() && event.getButton() != MouseEvent.BUTTON3) return;

				// Swing 函式庫：在事件派送執行緒依系統匣的螢幕座標顯示選單。
				SwingUtilities.invokeLater(() -> showPopupMenu(event.getX(), event.getY()));
			}
		});

		try {
			// 外部函式：把圖示加入目前 Windows 使用者的系統匣。
			SystemTray.getSystemTray().add(trayIcon);

			return true;
		}
		catch (AWTException | SecurityException exception) {
			trayIcon = null;

			// 日誌：記錄系統匣安裝失敗類型，讓視窗保持可見作為安全後備。
			log.warn("event=desktop_tray_install_failed errorType={}", exception.getClass().getSimpleName());

			return false;
		}
	}

	// 方法：更新系統匣提示文字以反映目前後端狀態。
	@Override
	public void updateStatus(DesktopWindowSnapshot snapshot) {
		if (trayIcon == null) return;

		trayIcon.setToolTip("Linebot Commercial - " + snapshot.statusText());
	}

	// 方法：從 Windows 系統匣移除目前圖示與選單視窗。
	@Override
	public void remove() {
		if (trayIcon == null || !SystemTray.isSupported()) return;

		// Swing 函式庫：先關閉可能仍顯示的選單與透明定位視窗。
		SwingUtilities.invokeLater(this::hidePopupMenu);

		// 外部函式：明確移除系統匣圖示，避免程序結束後留下殘影。
		SystemTray.getSystemTray().remove(trayIcon);
		trayIcon = null;
	}

	// 方法：建立可正確顯示繁體中文的 Swing 系統匣選單。
	private JPopupMenu createPopupMenu(DesktopActions actions) {
		JPopupMenu menu = new JPopupMenu();
		menu.setFont(MENU_FONT);
		menu.setLightWeightPopupEnabled(false);

		menu.add(menuItem("顯示", actions.show()));
		menu.add(menuItem("設定", actions.settings()));
		menu.add(menuItem("重新啟動", actions.restart()));
		menu.addSeparator();
		menu.add(menuItem("結束", actions.exit()));
		menu.addPopupMenuListener(new PopupMenuListener() {

			// 方法：選單顯示前不需額外處理。
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
			}

			// 方法：選單關閉後釋放僅供螢幕定位使用的透明視窗。
			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
				disposePopupWindow();
			}

			// 方法：選單取消後釋放僅供螢幕定位使用的透明視窗。
			@Override
			public void popupMenuCanceled(PopupMenuEvent event) {
				disposePopupWindow();
			}
		});

		return menu;
	}

	// 方法：建立執行桌面操作並套用中文字型的 Swing 選單項目。
	private JMenuItem menuItem(
		String label,
		Runnable action
	) {
		JMenuItem item = new JMenuItem(label);
		item.setFont(MENU_FONT);
		item.addActionListener(event -> action.run());

		return item;
	}

	// 方法：依系統匣點擊位置顯示選單，並避免超出目前 Windows 工作區域。
	private void showPopupMenu(
		int screenX,
		int screenY
	) {
		if (popupMenu == null) return;

		hidePopupMenu();
		popupWindow = new JWindow();
		popupWindow.setType(Window.Type.POPUP);
		popupWindow.setAlwaysOnTop(true);
		popupWindow.setSize(1, 1);

		// AWT 函式庫：取得可用工作區域與選單尺寸，將選單固定在點擊位置的左上方。
		Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		Dimension size = popupMenu.getPreferredSize();
		int x = Math.max(bounds.x, Math.min(screenX - size.width, bounds.x + bounds.width - size.width));
		int y = Math.max(bounds.y, Math.min(screenY - size.height, bounds.y + bounds.height - size.height));

		popupWindow.setLocation(x, y);
		popupWindow.setVisible(true);

		// Swing 函式庫：以透明定位視窗為基準顯示完整中文選單。
		popupMenu.show(popupWindow.getContentPane(), 0, 0);
	}

	// 方法：關閉目前選單並釋放透明定位視窗。
	private void hidePopupMenu() {
		if (popupMenu != null) popupMenu.setVisible(false);

		disposePopupWindow();
	}

	// 方法：釋放 Swing 選單使用的透明定位視窗。
	private void disposePopupWindow() {
		if (popupWindow == null) return;

		popupWindow.dispose();
		popupWindow = null;
	}

	// 方法：解析適用於 Windows 系統匣選單的中文字型，避免預設字型遺失 CJK 字形而顯示方塊。
	private static Font resolveMenuFont() {
		Font font = new Font("Microsoft JhengHei UI", Font.PLAIN, 12);
		if (font.canDisplay('顯')) return font;

		return new Font(Font.DIALOG, Font.PLAIN, 12);
	}

	//#endregion
}
