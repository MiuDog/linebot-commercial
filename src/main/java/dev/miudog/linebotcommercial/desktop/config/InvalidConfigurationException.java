package dev.miudog.linebotcommercial.desktop.config;

import java.util.List;

/**
 * 表示設定快照未通過欄位驗證，因此不得寫入磁碟。
 */
public final class InvalidConfigurationException extends RuntimeException {

	//#region 欄位

	private final List<AppConfigurationValidator.Violation> violations;

	//#endregion

	//#region 建構子

	// 方法：保存可供介面逐欄顯示的驗證結果。
	public InvalidConfigurationException(List<AppConfigurationValidator.Violation> violations) {
		super("桌面設定未通過驗證");
		this.violations = List.copyOf(violations);
	}

	//#endregion

	//#region 方法

	// 方法：取得不可變更的欄位驗證結果。
	public List<AppConfigurationValidator.Violation> violations() {
		return violations;
	}

	//#endregion
}
