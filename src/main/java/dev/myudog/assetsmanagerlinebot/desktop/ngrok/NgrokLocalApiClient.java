package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 從只允許 loopback 的 ngrok local API 取得目前 HTTPS tunnel URL。
 */
public final class NgrokLocalApiClient implements NgrokTunnelProvider {

	//#region 欄位

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final URI tunnelsEndpoint;

	//#endregion

	//#region 建構子

	// 方法：建立連線至指定 loopback Port 的正式 local API client。
	public NgrokLocalApiClient(int port) {
		this(
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
			new ObjectMapper(),
			loopbackEndpoint(port)
		);
	}

	// 方法：建立可替換 HTTP 與 JSON 邊界的 local API client。
	NgrokLocalApiClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		URI tunnelsEndpoint
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.tunnelsEndpoint = tunnelsEndpoint;
	}

	//#endregion

	//#region 方法

	// 方法：查詢 local API 並回傳第一個有效 HTTPS 公開網址。
	public Optional<String> fetchHttpsUrl(Duration timeout) {
		HttpRequest request = HttpRequest.newBuilder(tunnelsEndpoint)
			.timeout(timeout)
			.GET()
			.build();

		try {
			// 外部函式：只向建構時鎖定的 127.0.0.1 local API 發送同步狀態查詢。
			HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString()
			);

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new NgrokLocalApiException("ngrok local API 回應失敗", null);
			}

			return parseHttpsUrl(response.body());
		}
		catch (IOException exception) {
			throw new NgrokLocalApiException("無法連線 ngrok local API", exception);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new NgrokLocalApiException("ngrok local API 查詢被中斷", exception);
		}
		catch (RuntimeException exception) {
			if (exception instanceof NgrokLocalApiException safeException) throw safeException;

			throw new NgrokLocalApiException("ngrok local API 回應格式無效", exception);
		}
	}

	// 方法：解析 tunnel 清單並只接受具有 HTTPS scheme 的公開網址。
	private Optional<String> parseHttpsUrl(String responseBody) {
		// 外部函式：使用專案既有 Jackson 解析 local API JSON，不以字串拼接判斷結構。
		JsonNode root = objectMapper.readTree(responseBody);
		JsonNode tunnels = root.get("tunnels");

		if (tunnels == null || !tunnels.isArray()) {
			throw new NgrokLocalApiException("ngrok local API 回應格式無效", null);
		}

		for (JsonNode tunnel : tunnels) {
			JsonNode publicUrlNode = tunnel.get("public_url");
			String publicUrl = publicUrlNode == null ? "" : publicUrlNode.asText();

			if (publicUrl.startsWith("https://")) return Optional.of(publicUrl);
		}

		return Optional.empty();
	}

	// 方法：建立固定為 127.0.0.1 且 Port 有效的 local API endpoint。
	private static URI loopbackEndpoint(int port) {
		if (port < 1 || port > 65535) throw new IllegalArgumentException("ngrok local API Port 無效");

		return URI.create("http://127.0.0.1:" + port + "/api/tunnels");
	}

	//#endregion
}
