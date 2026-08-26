package dev.miudog.linebotcommercial.desktop.diagnostic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 使用 JDK 網路 API 實際執行分階段連線診斷。
 */
public final class JavaConnectionProbe implements ConnectionProbe {

	//#region 欄位

	private final HttpClient httpClient;

	//#endregion

	//#region 建構子

	// 方法：建立會跟隨正常轉址的 JDK HTTP 診斷邊界。
	public JavaConnectionProbe() {
		// JDK 網路函式：建立可重複使用且不預設憑證的 HTTP Client。
		this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
	}

	// 方法：以可替換 HTTP Client 建立診斷邊界供整合測試使用。
	JavaConnectionProbe(HttpClient httpClient) {
		this.httpClient = httpClient;
	}

	//#endregion

	//#region 方法

	// 方法：解析主機並回傳去除重複的 IP 位址。
	@Override
	public List<String> resolve(String host) throws Exception {
		// JDK DNS 函式：使用目前 Windows 與 VPN 的 DNS 設定解析目標網域。
		return Arrays.stream(InetAddress.getAllByName(host))
			.map(InetAddress::getHostAddress)
			.distinct()
			.toList();
	}

	// 方法：建立後立即關閉 TCP Socket，用來判斷防火牆與埠號。
	@Override
	public void connect(
		String host,
		int port,
		Duration timeout
	) throws Exception {
		try (Socket socket = new Socket()) {
			// JDK Socket 函式：對指定主機與埠號發送 TCP 連線封包並限制等待時間。
			socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
		}
	}

	// 方法：執行真實 TLS 握手，同時驗證憑證鏈與主機名。
	@Override
	public void handshake(
		String host,
		int port,
		Duration timeout
	) throws Exception {
		// JDK TLS 函式：使用系統預設信任庫建立 SSL Socket。
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

		try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
			SSLParameters parameters = socket.getSSLParameters();

			parameters.setEndpointIdentificationAlgorithm("HTTPS");
			socket.setSSLParameters(parameters);
			socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));

			// JDK Socket 函式：先建立底層 TCP 連線再開始 TLS 交換。
			socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
			socket.startHandshake();
		}
	}

	// 方法：傳送可設定標頭的 GET，只保留狀態碼以避免讀取外部內容。
	@Override
	public ConnectionProbeResponse request(
		URI uri,
		Map<String, String> headers,
		Duration timeout
	) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.header("User-Agent", "LinebotDocument-ConnectionDiagnostic")
			.GET();

		for (Map.Entry<String, String> header : headers.entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}

		// JDK HTTP 函式：傳送受控 GET 請求並丟棄不需要顯示的外部回應內容。
		HttpResponse<Void> response = httpClient.send(
			builder.build(),
			HttpResponse.BodyHandlers.discarding()
		);

		return new ConnectionProbeResponse(response.statusCode(), "");
	}

	//#endregion
}

