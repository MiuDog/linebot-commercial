package dev.miudog.linebotcommercial;

import java.util.Locale;

/**
 * 區分桌面管理介面、背景服務與既有 server 的程序職責。
 */
public enum ApplicationRuntimeMode {

	DESKTOP,
	SERVICE,
	SERVER;

	//#region 方法

	// 方法：由 JVM 屬性與命令列解析目前程序唯一的執行職責。
	public static ApplicationRuntimeMode resolve(String[] arguments) {
		return resolve(
			arguments,
			System.getProperty("app.desktop.enabled"),
			System.getProperty("app.runtime.mode")
		);
	}

	// 方法：提供測試以明確來源驗證 launcher 屬性優先順序。
	static ApplicationRuntimeMode resolve(
		String[] arguments,
		String desktopProperty,
		String runtimeProperty
	) {
		if (Boolean.parseBoolean(desktopProperty)) return DESKTOP;

		String requestedMode = runtimeProperty;

		// 步驟一：命令列只補足未由 launcher 固定的執行模式。
		for (String argument : arguments) {
			if ("--app.desktop.enabled=true".equalsIgnoreCase(argument)) return DESKTOP;

			if (argument.regionMatches(true, 0, "--app.runtime.mode=", 0, 19)) {
				requestedMode = argument.substring(19);
			}
		}

		if (requestedMode == null || requestedMode.isBlank()) return SERVER;

		// 步驟二：拒絕未知模式，避免安裝後悄悄啟動錯誤程序角色。
		try {
			return valueOf(requestedMode.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("不支援的應用程式執行模式：" + requestedMode, exception);
		}
	}

	//#endregion
}
