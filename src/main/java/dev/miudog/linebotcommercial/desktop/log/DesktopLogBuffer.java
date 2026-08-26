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
import java.time.Instant;

/**
 * 保存固定容量且已清理敏感資料的即時 Log，並提供篩選快照。
 */
public final class DesktopLogBuffer {

	//#region 欄位

	private static final Pattern LEVEL_PATTERN = Pattern.compile(
		"\\\"level\\\"\\s*:\\s*\\\"(TRACE|DEBUG|INFO|WARN|ERROR)\\\"",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern LOGGER_PATTERN = Pattern.compile("\\\"loggerName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
	private static final Pattern FORMATTED_MESSAGE_PATTERN = Pattern.compile(
		"\\\"formattedMessage\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""
	);
	private static final Pattern MESSAGE_PATTERN = Pattern.compile("\\\"message\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
	private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\\"timestamp\\\"\\s*:\\s*([0-9]+)");

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
		addEntries(List.of(line));
		publish();
	}

	// 方法：一次加入本輪讀取的所有 Log，只發布一份 UI 快照以降低重繪成本。
	public synchronized void addAll(List<String> lines) {
		Objects.requireNonNull(lines, "Log 行集合不可為 null");
		addEntries(lines);
		publish();
	}

	// 方法：清理並格式化一批 Log，不在每一行觸發 Swing 全畫面更新。
	private void addEntries(List<String> lines) {
		for (String line : lines) {
			String safeLine = SensitiveDataSanitizer.sanitizeLogLine(line);

			if (safeLine.isBlank()) continue;

			while (entries.size() >= capacity) {
				entries.removeFirst();
			}

			entries.addLast(new DesktopLogEntry(parseLevel(safeLine), readableText(safeLine)));
			status = "Log 讀取中";
		}
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

	// 方法：把 JSON Log 外殼轉成時間、等級、元件與事件摘要，無法解析時保留安全原文。
	private String readableText(String line) {
		Matcher levelMatcher = LEVEL_PATTERN.matcher(line);
		Matcher loggerMatcher = LOGGER_PATTERN.matcher(line);
		Matcher formattedMessageMatcher = FORMATTED_MESSAGE_PATTERN.matcher(line);
		Matcher messageMatcher = MESSAGE_PATTERN.matcher(line);
		Matcher timestampMatcher = TIMESTAMP_PATTERN.matcher(line);

		if (!levelMatcher.find()) return line;

		String loggerName = loggerMatcher.find() ? simpleName(loggerMatcher.group(1)) : "Application";
		String message = formattedMessageMatcher.find()
			? formattedMessageMatcher.group(1)
			: messageMatcher.find() ? messageMatcher.group(1) : line;

		if (message.equals(line)) return line;

		String timestamp = timestampPrefix(timestampMatcher);

		return timestamp + "[" + levelMatcher.group(1).toUpperCase(Locale.ROOT) + "] [" + loggerName + "] " + unescapeJson(message);
	}

	// 方法：把 epoch millisecond 轉成穩定 ISO 時間，損毀或超大值則安全省略時間。
	private String timestampPrefix(Matcher timestampMatcher) {
		if (!timestampMatcher.find()) return "";

		try {
			return "[" + Instant.ofEpochMilli(Long.parseLong(timestampMatcher.group(1))) + "] ";
		}
		catch (NumberFormatException exception) {
			return "";
		}
	}

	// 方法：只顯示 Logger 最後一段類別名稱，減少重複套件路徑造成的閱讀干擾。
	private String simpleName(String loggerName) {
		int separator = loggerName.lastIndexOf('.');

		return separator < 0 ? loggerName : loggerName.substring(separator + 1);
	}

	// 方法：還原桌面上最常見的 JSON 字串跳脫，使訊息可直接閱讀。
	private String unescapeJson(String value) {
		return value
			.replace("\\\\n", " ↵ ")
			.replace("\\\\r", "")
			.replace("\\\\t", " ")
			.replace("\\\\\"", "\"")
			.replace("\\\\\\\\", "\\");
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
