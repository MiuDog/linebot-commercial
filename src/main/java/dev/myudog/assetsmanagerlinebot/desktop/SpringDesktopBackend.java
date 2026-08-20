package dev.myudog.assetsmanagerlinebot.desktop;

import dev.myudog.assetsmanagerlinebot.AssetsManagerLinebotApplication;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 以可受控關閉的 Spring ApplicationContext 執行既有 LINE Bot 後端。
 */
public final class SpringDesktopBackend implements DesktopBackend {

	//#region 欄位

	private ConfigurableApplicationContext context;

	//#endregion

	//#region 方法

	// 方法：建立 Spring 應用程式並在啟動前套用桌面設定屬性。
	@Override
	public synchronized void start(
		Map<String, Object> properties,
		String[] arguments
	) {
		if (context != null) throw new IllegalStateException("Spring 後端已經啟動");

		SpringApplication application = new SpringApplication(AssetsManagerLinebotApplication.class);

		// 外部函式：在建立任何 Spring Bean 前注入桌面設定，避免先使用舊設定啟動。
		application.setDefaultProperties(properties);
		context = application.run(arguments);
	}

	// 方法：關閉目前 Spring Context 並釋放所有受管理資源。
	@Override
	public synchronized void stop() {
		if (context == null) return;

		ConfigurableApplicationContext runningContext = context;
		context = null;

		// 外部函式：依 Spring 生命週期順序停止 Bean、排程器、資料庫與 Web Server。
		runningContext.close();
	}

	//#endregion
}
