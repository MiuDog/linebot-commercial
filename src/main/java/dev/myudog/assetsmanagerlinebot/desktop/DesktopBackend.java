package dev.myudog.assetsmanagerlinebot.desktop;

import java.util.Map;

/**
 * 隔離桌面生命週期與實際 Spring ApplicationContext 操作。
 */
public interface DesktopBackend {

	// 方法：以桌面設定映射出的屬性啟動後端服務。
	void start(
		Map<String, Object> properties,
		String[] arguments
	);

	// 方法：停止後端並釋放資料庫、Port 與 Spring 資源。
	void stop();
}
