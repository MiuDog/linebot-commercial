package dev.miudog.linebotcommercial.desktop.log;

import dev.miudog.linebotcommercial.observability.SensitiveDataSanitizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存固定容量且已清理敏感資料的即時 Log，並提供篩選快照。
 */
public final class DesktopLogBuffer {

	//#region 欄位

	private static final Pattern LEVEL_PATTERN = Pattern.compile(
		"\\\"level\\\"\\s*:\\s*\\\"(TRACE|DEBUG|INFO|WARN|ERROR)\\\"",
		Pattern.CASE_INSENSITIVE
	);

	private final int capacity;
	private final ArrayDeque<DesktopLogEntry> entries;
	private final List<Consumer<List<DesktopLogEntry>>> listeners;
	private String status;

	//#endregion

	//#region 建構子

	// 方法：建立指定最大筆數的固定容量 Log buffer。
	public DesktopLogBuffer(int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("Log buffer 容量必須大於零");

		this.capacity = capacity;
		this.entries = new ArrayDeque<>(capacity);
		this.listeners = new CopyOnWriteArrayList<>();
		this.status = "等待 Log 檔案";
	}

	//#endregion

	//#region 方法

	// 方法：清理敏感資料、解析等級並加入最新 Log 項目。
	public synchronized void add(String line) {
		String safeLine = SensitiveDataSanitizer.sanitizeLogLine(line);

		if (safeLine.isBlank()) return;

		while (entries.size() >= capacity) {
			entries.removeFirst();
		}

		entries.addLast(new DesktopLogEntry(parseLevel(safeLine), safeLine));
		status = "Log 讀取中";
		publish();
	}

	// 方法：依等級與不分大小寫文字搜尋建立不可變更的顯示快照。
	public synchronized List<DesktopLogEntry> entries(
		String level,
		String query
	) {
		String normalizedLevel = Objects.requireNonNullElse(level, "ALL").toUpperCase(Locale.ROOT);
		String normalizedQuery = Objects.requireNonNullElse(query, "").toLowerCase(Locale.ROOT);
		List<DesktopLogEntry> matched = new ArrayList<>();

		for (DesktopLogEntry entry : entries) {
			if (!"ALL".equals(normalizedLevel) && !entry.level().equals(normalizedLevel)) continue;

			if (!normalizedQuery.isBlank() && !entry.text().toLowerCase(Locale.ROOT).contains(normalizedQuery)) continue;

			matched.add(entry);
		}

		return List.copyOf(matched);
	}

	// 方法：更新讀檔狀態供 UI 顯示等待或錯誤原因。
	public synchronized void updateStatus(String status) {
		this.status = Objects.requireNonNullElse(status, "Log 狀態未知");
		publish();
	}

	// 方法：取得目前 Log 讀取狀態。
	public synchronized String status() {
		return status;
	}

	// 方法：加入 Log 快照監聽器供 Swing 畫面即時更新。
	public void addListener(Consumer<List<DesktopLogEntry>> listener) {
		listeners.add(Objects.requireNonNull(listener, "Log 監聽器不可為 null"));
	}

	// 方法：從 JSON Log 文字解析標準等級，無法解析時標示 UNKNOWN。
	private String parseLevel(String line) {
		Matcher matcher = LEVEL_PATTERN.matcher(line);

		if (!matcher.find()) return "UNKNOWN";

		return matcher.group(1).toUpperCase(Locale.ROOT);
	}

	// 方法：發布最新完整 buffer 快照，讓 UI 依目前篩選條件重新呈現。
	private void publish() {
		List<DesktopLogEntry> snapshot = List.copyOf(entries);

		for (Consumer<List<DesktopLogEntry>> listener : listeners) {
			listener.accept(snapshot);
		}
	}

	//#endregion
}
