package dev.miudog.linebotcommercial.desktop;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用 AWT SystemTray 實作 Windows 系統匣圖示與操作選單。
 */
public final class AwtTrayAccess implements DesktopTrayAccess {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(AwtTrayAccess.class);

	private TrayIcon trayIcon;

	//#endregion

	//#region 方法

	// 方法：建立顯示、設定、重新啟動與結束選單並安裝系統匣圖示。
	@Override
	public boolean install(DesktopActions actions) {
		if (!SystemTray.isSupported()) return false;

		PopupMenu menu = new PopupMenu();
		MenuItem showItem = menuItem("顯示", actions.show());
		MenuItem settingsItem = menuItem("設定", actions.settings());
		MenuItem restartItem = menuItem("重新啟動", actions.restart());
		MenuItem exitItem = menuItem("結束", actions.exit());

		menu.add(showItem);
		menu.add(settingsItem);
		menu.add(restartItem);
		menu.addSeparator();
		menu.add(exitItem);
		trayIcon = new TrayIcon(createImage(), "Linebot Commercial", menu);
		trayIcon.setImageAutoSize(true);
		trayIcon.addActionListener(event -> SwingUtilities.invokeLater(actions.show()));

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

	// 方法：從 Windows 系統匣移除目前圖示。
	@Override
	public void remove() {
		if (trayIcon == null || !SystemTray.isSupported()) return;

		// 外部函式：明確移除系統匣圖示，避免程序結束後留下殘影。
		SystemTray.getSystemTray().remove(trayIcon);
		trayIcon = null;
	}

	// 方法：建立把 AWT 點擊事件轉回 Swing EDT 的系統匣選單項目。
	private MenuItem menuItem(
		String label,
		Runnable action
	) {
		MenuItem item = new MenuItem(label);

		item.addActionListener(event -> SwingUtilities.invokeLater(action));

		return item;
	}

	// 方法：建立開發階段使用的中性程式圖示，正式素材可直接替換。
	private BufferedImage createImage() {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();

		try {
			graphics.setColor(new Color(30, 136, 229));
			graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
			graphics.setColor(Color.WHITE);
			graphics.fillOval(9, 9, 14, 14);
		}
		finally {
			graphics.dispose();
		}

		return image;
	}

	//#endregion
}
