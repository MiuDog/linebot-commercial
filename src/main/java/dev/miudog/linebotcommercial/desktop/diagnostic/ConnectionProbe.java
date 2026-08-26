package dev.miudog.linebotcommercial.desktop.diagnostic;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 隔離 DNS、Socket、TLS 與 HTTP 外部連線邊界以供測試注入。
 */
public interface ConnectionProbe {

	// 方法：解析目標主機的所有 IP 位址。
	List<String> resolve(String host) throws Exception;

	// 方法：在指定時限內建立 TCP 連線。
	void connect(
		String host,
		int port,
		Duration timeout
	) throws Exception;

	// 方法：在指定時限內完成 TLS 握手與憑證驗證。
	void handshake(
		String host,
		int port,
		Duration timeout
	) throws Exception;

	// 方法：傳送不含機密記錄的 HTTP GET 診斷請求。
	ConnectionProbeResponse request(
		URI uri,
		Map<String, String> headers,
		Duration timeout
	) throws Exception;
}

