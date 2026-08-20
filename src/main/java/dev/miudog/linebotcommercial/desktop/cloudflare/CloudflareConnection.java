package dev.miudog.linebotcommercial.desktop.cloudflare;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import java.util.Objects;

/**
 * 保存 Cloudflare 啟用狀態、公開網址與可直接啟動 Spring 的設定。
 */
public record CloudflareConnection(
	boolean enabled,
	String publicUrl,
	AppConfiguration configuration
) {

	// 方法：建立不允許 null 的 Cloudflare 連線結果。
	public CloudflareConnection {
		publicUrl = Objects.requireNonNullElse(publicUrl, "");
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
	}
}
