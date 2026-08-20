package dev.miudog.linebotcommercial.desktop.log;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 提供即時 Log 的等級篩選、搜尋、暫停捲動與開啟資料夾操作。
 */
public final class DesktopLogPanel {

	//#region 欄位

	private final DesktopLogBuffer buffer;
	private final Supplier<Path> logDirectory;
	private final JPanel content;
	private final JComboBox<String> levelFilter;
	private final JTextField searchField;
	private final JCheckBox pauseScrolling;
	private final JTextArea logText;
	private final JLabel statusLabel;

	//#endregion

	//#region 建構子

	// 方法：在 Swing EDT 建立即時 Log 檢視與操作控制項。
	public DesktopLogPanel(
		DesktopLogBuffer buffer,
		Supplier<Path> logDirectory
	) {
		requireEdt();
		this.buffer = Objects.requireNonNull(buffer, "Log buffer 不可為 null");
		this.logDirectory = Objects.requireNonNull(logDirectory, "Log 目錄來源不可為 null");
		this.content = new JPanel(new BorderLayout(8, 8));
		this.levelFilter = new JComboBox<>(new String[] {"ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"});
		this.searchField = new JTextField(18);
		this.pauseScrolling = new JCheckBox("暫停自動捲動");
		this.logText = new JTextArea();
		this.statusLabel = new JLabel();

		buildPanel();
		buffer.addListener(this::scheduleRefresh);
		refresh();
	}

	//#endregion

	//#region 方法

	// 方法：取得可加入主視窗分頁的 Log 面板。
	public JPanel content() {
		return content;
	}

	// 方法：建立篩選列、唯讀 Log 文字區與開啟資料夾按鈕。
	private void buildPanel() {
		JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton openFolderButton = new JButton("開啟 Log 資料夾");

		logText.setEditable(false);
		logText.setLineWrap(false);
		levelFilter.addActionListener(event -> refresh());
		pauseScrolling.addActionListener(event -> refresh());
		openFolderButton.addActionListener(event -> openLogDirectory());
		searchField.getDocument().addDocumentListener(new DocumentListener() {

			// 方法：搜尋文字新增後立即重新篩選 Log。
			@Override
			public void insertUpdate(DocumentEvent event) {
				refresh();
			}

			// 方法：搜尋文字移除後立即重新篩選 Log。
			@Override
			public void removeUpdate(DocumentEvent event) {
				refresh();
			}

			// 方法：搜尋欄位樣式變更時同步重新篩選 Log。
			@Override
			public void changedUpdate(DocumentEvent event) {
				refresh();
			}
		});
		filters.add(new JLabel("等級"));
		filters.add(levelFilter);
		filters.add(new JLabel("搜尋"));
		filters.add(searchField);
		filters.add(pauseScrolling);
		filters.add(openFolderButton);
		content.add(filters, BorderLayout.NORTH);
		content.add(new JScrollPane(logText), BorderLayout.CENTER);
		content.add(statusLabel, BorderLayout.SOUTH);
	}

	// 方法：從背景讀檔執行緒安排 Swing EDT 更新 Log 內容。
	private void scheduleRefresh(List<DesktopLogEntry> ignoredEntries) {
		if (SwingUtilities.isEventDispatchThread()) {
			refresh();
		}
		else {
			SwingUtilities.invokeLater(this::refresh);
		}
	}

	// 方法：依目前等級與搜尋條件更新文字區，並控制是否自動捲到底端。
	private void refresh() {
		requireEdt();
		String selectedLevel = Objects.toString(levelFilter.getSelectedItem(), "ALL");
		String text = buffer.entries(selectedLevel, searchField.getText())
			.stream()
			.map(DesktopLogEntry::text)
			.collect(Collectors.joining(System.lineSeparator()));

		logText.setText(text);
		statusLabel.setText(buffer.status());

		if (!pauseScrolling.isSelected()) logText.setCaretPosition(logText.getDocument().getLength());
	}

	// 方法：使用 Windows 檔案總管開啟目前 Log 資料夾。
	private void openLogDirectory() {
		try {
			// 外部函式：交由 Windows 預設檔案總管開啟 Log 目錄供進一步診斷。
			Desktop.getDesktop().open(logDirectory.get().toFile());
		}
		catch (Exception exception) {
			buffer.updateStatus("無法開啟 Log 資料夾，請確認資料夾已建立");
		}
	}

	// 方法：強制 Log 面板元件只在 Swing EDT 建立與更新。
	private void requireEdt() {
		if (!SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("Log 面板必須在 Swing EDT 操作");
		}
	}

	//#endregion
}
