package dev.miudog.linebotcommercial.desktop.ngrok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 驗證 ngrok local API 的 HTTPS tunnel、空清單與錯誤 JSON 行為。
 */
class NgrokLocalApiClientTest {

	private HttpServer server;

	// 方法：每項測試後停止本機 HTTP stub 並釋放 Port。
	@AfterEach
	void stopServer() {
		if (server != null) server.stop(0);
	}

	// 方法：從 loopback local API 選出第一個 HTTPS 公開網址。
	@Test
	void shouldReadHttpsTunnelFromLocalApi() throws Exception {
		startServer("{\"tunnels\":[{\"public_url\":\"http://example.test\"},{\"public_url\":\"https://example.ngrok.app\"}]}", 200);

		assertThat(client().fetchHttpsUrl(Duration.ofSeconds(2)))
			.contains("https://example.ngrok.app");
	}

	// 方法：空 tunnel 清單回傳空值，讓上層在 timeout 內繼續等待。
	@Test
	void shouldReturnEmptyWhenNoTunnelExists() throws Exception {
		startServer("{\"tunnels\":[]}", 200);

		assertThat(client().fetchHttpsUrl(Duration.ofSeconds(2))).isEmpty();
	}

	// 方法：HTTP 錯誤與 malformed JSON 都轉成不含回應內容的穩定例外。
	@Test
	void shouldFailSafelyForHttpErrorAndMalformedJson() throws Exception {
		startServer("secret-response", 500);

		assertThatThrownBy(() -> client().fetchHttpsUrl(Duration.ofSeconds(2)))
			.isInstanceOf(NgrokLocalApiException.class)
			.hasMessageNotContaining("secret-response");
	}

	// 方法：啟動只綁定 loopback 的本機 HTTP stub。
	private void startServer(
		String body,
		int status
	) throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/tunnels", exchange -> {
			byte[] response = body.getBytes(StandardCharsets.UTF_8);

			exchange.sendResponseHeaders(status, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
	}

	// 方法：建立指向測試 loopback Port 的 ngrok local API client。
	private NgrokLocalApiClient client() {
		return new NgrokLocalApiClient(server.getAddress().getPort());
	}
}
