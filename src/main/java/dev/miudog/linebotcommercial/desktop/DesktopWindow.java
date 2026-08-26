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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
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
	private final JLabel cloudflareIdentityValue;
	private JButton connectionTestButton;
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

		// Swing 函式庫：讓工作列、視窗切換器與視窗標題列顯示相同 commercial 品牌圖示。
		frame.setIconImages(DesktopIconLoader.loadAll());

		// AWT 函式庫：明確設定 Windows 工作列品牌圖示，避免回退成 Java 預設圖示。
		DesktopIconLoader.applyTaskbarIcon();
		this.statusValue = new JLabel();
		this.localUrlValue = new JLabel();
		this.publicUrlValue = new JLabel();
		this.callbackUrlValue = new JLabel();
		this.cloudflareIdentityValue = new JLabel();
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

	// 方法：建立狀態區、網址區與 App 內主要操作按鈕。
	private void buildWindow(JComponent logPanel) {
		JPanel root = new JPanel(new BorderLayout(12, 12));
		JPanel values = new JPanel(new GridLayout(5, 2, 8, 8));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton openLocalButton = new JButton("開啟本機管理頁");
		JButton settingsButton = new JButton("設定");
		JButton restartButton = new JButton("重新啟動");
		JButton exitButton = new JButton("結束");
		JTabbedPane tabs = new JTabbedPane();
		connectionTestButton = new JButton("測試連線");

		values.add(new JLabel("服務狀態"));
		values.add(statusValue);
		values.add(new JLabel("本機網址"));
		values.add(localUrlValue);
		values.add(new JLabel("公開網址"));
		values.add(publicUrlValue);
		values.add(new JLabel("LINE Callback 網址"));
		values.add(callbackUrlValue);
		values.add(new JLabel("Cloudflare Connector"));
		values.add(cloudflareIdentityValue);
		openLocalButton.addActionListener(event -> openLocalUrl());
		settingsButton.addActionListener(event -> actions.settings().run());
		restartButton.addActionListener(event -> actions.restart().run());
		connectionTestButton.addActionListener(event -> requestConnectionDiagnostic());
		exitButton.addActionListener(event -> actions.exit().run());
		buttons.add(openLocalButton);
		buttons.add(settingsButton);
		buttons.add(restartButton);
		buttons.add(connectionTestButton);
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

	// 方法：詢問受測網域，驗證後將 Commercial 分階段診斷交由背景操作執行。
	private void requestConnectionDiagnostic() {
		String suggestedTarget = suggestedDiagnosticTarget();

		// Swing 函式：以可編輯輸入框讓使用者直接指定公開或內網網域。
		String target = (String) JOptionPane.showInputDialog(
			frame,
			"請輸入要測試的網域或網址：",
			"測試連線",
			JOptionPane.QUESTION_MESSAGE,
			null,
			null,
			suggestedTarget
		);

		if (target == null) return;

		String validationMessage = validateDiagnosticTarget(target);

		if (validationMessage != null) {
			// Swing 函式：立即說明輸入問題，不啟動無效背景測試。
			JOptionPane.showMessageDialog(
				frame,
				validationMessage,
				"無法測試",
				JOptionPane.WARNING_MESSAGE
			);

			return;
		}

		connectionTestButton.setEnabled(false);
		connectionTestButton.setText("測試中…");
		actions.connectionTest().run(target.trim(), this::scheduleDiagnosticReport);
	}

	// 方法：從當前公開網址推導預設測試目標，未設定時提示 HTTPS 格式。
	private String suggestedDiagnosticTarget() {
		String publicUrl = model.snapshot().publicUrl();

		if (publicUrl.startsWith("http")) return publicUrl;

		return "https://";
	}

	// 方法：在開啟背景工作前驗證受測目標的協定與主機。
	private String validateDiagnosticTarget(String requestedTarget) {
		String target = requestedTarget.trim();

		if (target.isBlank()) return "請輸入要測試的網域。";

		if (!target.contains("://")) target = "https://" + target;

		try {
			// JDK URI 函式：在測試前解析使用者輸入，避免無效目標進入背景執行緒。
			URI uri = URI.create(target);
			String scheme = uri.getScheme();

			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return "只支援 HTTP 或 HTTPS 網址。";

			if (uri.getHost() == null || uri.getHost().isBlank()) return "請輸入有效網域。";
		}
		catch (IllegalArgumentException exception) {
			return "網址格式無效，請檢查後再試。";
		}

		return null;
	}

	// 方法：將背景診斷結果排入 Swing EDT 顯示。
	private void scheduleDiagnosticReport(
		dev.miudog.linebotcommercial.desktop.diagnostic.ConnectionDiagnosticReport report
	) {
		SwingUtilities.invokeLater(() -> showDiagnosticReport(report));
	}

	// 方法：還原按鈕並以可捲動、可選取的文字顯示 Commercial 分階段報告。
	private void showDiagnosticReport(
		dev.miudog.linebotcommercial.desktop.diagnostic.ConnectionDiagnosticReport report
	) {
		requireEdt();
		JTextArea content = new JTextArea(report.displayText(), 20, 72);

		content.setEditable(false);
		content.setCaretPosition(0);
		content.setLineWrap(true);
		content.setWrapStyleWord(true);
		connectionTestButton.setEnabled(true);
		connectionTestButton.setText("測試連線");

		// Swing 函式：以明確成功或警告狀態顯示可複製診斷報告。
		JOptionPane.showMessageDialog(
			frame,
			new JScrollPane(content),
			report.successful() ? "連線測試成功" : "連線測試發現問題",
			report.successful() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
		);
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
		cloudflareIdentityValue.setText(snapshot.cloudflareIdentity());
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
