package dev.myudog.assetsmanagerlinebot.desktop.log;

import java.util.Objects;

/**
 * 保存經過敏感資料清理後的一筆桌面 Log。
 */
public record DesktopLogEntry(String level, String text) {

	// 方法：建立具有標準化等級與安全文字的 Log 項目。
	public DesktopLogEntry {
		Objects.requireNonNull(level, "Log 等級不可為 null");
		Objects.requireNonNull(text, "Log 文字不可為 null");
	}
}
