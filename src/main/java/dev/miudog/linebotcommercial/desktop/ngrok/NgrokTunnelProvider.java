package dev.miudog.linebotcommercial.desktop.ngrok;

import java.time.Duration;
import java.util.Optional;

/**
 * 定義從 loopback local API 取得 HTTPS tunnel 的查詢邊界。
 */
public interface NgrokTunnelProvider {

	// 方法：在單次 timeout 內查詢目前 HTTPS 公開網址。
	Optional<String> fetchHttpsUrl(Duration timeout);
}
