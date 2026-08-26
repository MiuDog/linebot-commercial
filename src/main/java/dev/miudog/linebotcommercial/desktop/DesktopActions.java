package dev.miudog.linebotcommercial.desktop;

import java.util.Objects;

/**
 * 定義主視窗與系統匣共同使用的桌面操作。
 */
public record DesktopActions(
	Runnable show,
	Runnable settings,
	Runnable restart,
	ConnectionDiagnosticAction connectionTest,
	Runnable exit
) {

	// 方法：拒絕缺少任何操作的桌面命令集合。
	public DesktopActions {
		Objects.requireNonNull(show, "顯示操作不可為 null");
		Objects.requireNonNull(settings, "設定操作不可為 null");
		Objects.requireNonNull(restart, "重新啟動操作不可為 null");
		Objects.requireNonNull(connectionTest, "連線診斷操作不可為 null");
		Objects.requireNonNull(exit, "結束操作不可為 null");
	}
}
