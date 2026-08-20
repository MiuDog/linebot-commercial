package dev.miudog.linebotcommercial.desktop;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import javax.swing.JComponent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.WindowConstants;

/**
 * 顯示服務狀態、本機與公開網址及常用桌面操作的繁體中文主視窗。
 */
public final class DesktopWindow implements DesktopWindowHandle {

	//#region 欄位

	private final DesktopWindowModel model;
	private final DesktopActions actions;
	private final JFrame frame;
	private final JLabel statusValue;
	private final JLabel localUrlValue;
	private final JLabel publicUrlValue;
	private final JLabel callbackUrlValue;
	private Runnable closeHandler;

	//#endregion

	//#region 建構子

	// 方法：在 Swing EDT 建立主視窗並綁定完整快照更新。
	public DesktopWindow(
		DesktopWindowModel model,
		DesktopActions actions,
		JComponent logPanel
	) {
		requireEdt();
		this.model = model;
		this.actions = actions;
		this.frame = new JFrame("Linebot Commercial");
		this.statusValue = new JLabel();
		this.localUrlValue = new JLabel();
		this.publicUrlValue = new JLabel();
		this.callbackUrlValue = new JLabel();
		this.closeHandler = () -> {
		};

		buildWindow(logPanel);
		model.addListener(this::scheduleSnapshot);
		applySnapshot(model.snapshot());
	}

	//#endregion

	//#region 方法

	// 方法：設定使用者按下視窗關閉時的系統匣控制流程。
	public void setCloseHandler(Runnable closeHandler) {
		requireEdt();
		this.closeHandler = closeHandler;
	}

	// 方法：顯示主視窗、還原最小化狀態並移至前景。
	@Override
	public void showWindow() {
		requireEdt();
		frame.setVisible(true);
		frame.setState(JFrame.NORMAL);
		frame.toFront();
		frame.requestFocus();
	}

	// 方法：隱藏主視窗但不停止 Spring 後端。
	@Override
	public void hideWindow() {
		requireEdt();
		frame.setVisible(false);
	}

	// 方法：釋放主視窗原生資源。
	public void closeWindow() {
		requireEdt();
		frame.dispose();
	}

	// 方法：建立狀態區、網址區與四項主要操作按鈕。
	private void buildWindow(JComponent logPanel) {
		JPanel root = new JPanel(new BorderLayout(12, 12));
		JPanel values = new JPanel(new GridLayout(4, 2, 8, 8));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton openLocalButton = new JButton("開啟本機管理頁");
		JButton settingsButton = new JButton("設定");
		JButton restartButton = new JButton("重新啟動");
		JButton exitButton = new JButton("結束");
		JTabbedPane tabs = new JTabbedPane();

		values.add(new JLabel("服務狀態"));
		values.add(statusValue);
		values.add(new JLabel("本機網址"));
		values.add(localUrlValue);
		values.add(new JLabel("公開網址"));
		values.add(publicUrlValue);
		values.add(new JLabel("LINE Callback 網址"));
		values.add(callbackUrlValue);
		openLocalButton.addActionListener(event -> openLocalUrl());
		settingsButton.addActionListener(event -> actions.settings().run());
		restartButton.addActionListener(event -> actions.restart().run());
		exitButton.addActionListener(event -> actions.exit().run());
		buttons.add(openLocalButton);
		buttons.add(settingsButton);
		buttons.add(restartButton);
		buttons.add(exitButton);
		root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
		root.add(new JLabel("應用程式已啟動後會持續在背景執行。"), BorderLayout.NORTH);
		root.add(values, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		tabs.addTab("狀態", root);
		tabs.addTab("Log", logPanel);
		frame.setContentPane(tabs);
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {

			// 方法：將關閉按鈕交由系統匣控制器決定隱藏或保持顯示。
			@Override
			public void windowClosing(WindowEvent event) {
				closeHandler.run();
			}
		});
		frame.setSize(900, 620);
		frame.setLocationRelativeTo(null);
	}

	// 方法：從任意後端執行緒把完整快照排入 Swing EDT。
	private void scheduleSnapshot(DesktopWindowSnapshot snapshot) {
		if (SwingUtilities.isEventDispatchThread()) {
			applySnapshot(snapshot);
		}
		else {
			SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
		}
	}

	// 方法：一次更新所有顯示元件，避免畫面呈現混合狀態。
	private void applySnapshot(DesktopWindowSnapshot snapshot) {
		requireEdt();
		statusValue.setText(snapshot.statusText());
		localUrlValue.setText(snapshot.localUrl());
		publicUrlValue.setText(snapshot.publicUrl());
		callbackUrlValue.setText(snapshot.callbackUrl());
	}

	// 方法：使用系統預設瀏覽器開啟目前本機管理頁。
	private void openLocalUrl() {
		try {
			// 外部函式：交由 Windows 預設瀏覽器開啟本機管理頁。
			Desktop.getDesktop().browse(URI.create(model.snapshot().localUrl()));
		}
		catch (Exception exception) {
			// 外部函式：瀏覽器不可用時把網址複製到目前使用者剪貼簿。
			Toolkit.getDefaultToolkit()
				.getSystemClipboard()
				.setContents(new StringSelection(model.snapshot().localUrl()), null);
		}
	}

	// 方法：強制主視窗元件只在 Swing EDT 建立或操作。
	private void requireEdt() {
		if (!SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("桌面視窗必須在 Swing EDT 操作");
		}
	}

	//#endregion
}
