package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfiguration;
import java.util.Objects;

/**
 * 保存 ngrok 啟用狀態、公開網址與可直接啟動 Spring 的設定。
 */
public record NgrokConnection(
	boolean enabled,
	String publicUrl,
	AppConfiguration configuration
) {

	// 方法：建立不允許 null 的 ngrok 連線結果。
	public NgrokConnection {
		publicUrl = Objects.requireNonNullElse(publicUrl, "");
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
	}
}
