package dev.miudog.linebotcommercial.desktop.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 驗證連線診斷能分離本機、目標網域與 LINE API 問題。
 */
class ConnectionDiagnosticServiceTest {

	//#region 測試

	// 方法：完整連線成功時回報每個階段與可讀回應。
	@Test
	void shouldReportEverySuccessfulConnectionStage() {
		RecordingProbe probe = new RecordingProbe();
		ConnectionDiagnosticService service = new ConnectionDiagnosticService(probe);

		ConnectionDiagnosticReport report = service.run(configuration(), "assets.example.com");

		assertThat(report.target()).isEqualTo("https://assets.example.com");
		assertThat(report.steps())
			.extracting(ConnectionDiagnosticStep::stage)
			.containsExactly(ConnectionDiagnosticStage.values());
		assertThat(report.steps())
			.extracting(ConnectionDiagnosticStep::status)
			.containsOnly(ConnectionDiagnosticStatus.PASSED);
		assertThat(probe.requestedUris).containsExactly(
			URI.create("http://127.0.0.1:8088/actuator/health"),
			URI.create("https://assets.example.com"),
			URI.create("https://api.line.me/v2/bot/info"),
			URI.create("https://api.openai.com/v1/models")
		);
		assertThat(probe.authorizationHeaders).containsExactly("", "", "Bearer channel-token", "");
	}

	// 方法：DNS 失敗時略過相依的目標測試，但仍獨立檢查 LINE API。
	@Test
	void shouldIsolateDomainFailureAndContinueLineApiCheck() {
		RecordingProbe probe = new RecordingProbe();
		probe.domainResolutionFailure = true;
		ConnectionDiagnosticService service = new ConnectionDiagnosticService(probe);

		ConnectionDiagnosticReport report = service.run(configuration(), "https://broken.example.com/callback");

		assertThat(report.step(ConnectionDiagnosticStage.TARGET_DNS).status())
			.isEqualTo(ConnectionDiagnosticStatus.FAILED);
		assertThat(report.step(ConnectionDiagnosticStage.TARGET_TCP).status())
			.isEqualTo(ConnectionDiagnosticStatus.SKIPPED);
		assertThat(report.step(ConnectionDiagnosticStage.TARGET_TLS).status())
			.isEqualTo(ConnectionDiagnosticStatus.SKIPPED);
		assertThat(report.step(ConnectionDiagnosticStage.TARGET_HTTP).status())
			.isEqualTo(ConnectionDiagnosticStatus.SKIPPED);
		assertThat(report.step(ConnectionDiagnosticStage.LINE_API).status())
			.isEqualTo(ConnectionDiagnosticStatus.PASSED);
	}

	// 方法：LINE API 的未授權回應應準確指向 Token，不顯示機密內容。
	@Test
	void shouldExplainInvalidLineTokenWithoutLeakingIt() {
		RecordingProbe probe = new RecordingProbe();
		probe.lineStatusCode = 401;
		ConnectionDiagnosticService service = new ConnectionDiagnosticService(probe);

		ConnectionDiagnosticReport report = service.run(configuration(), "https://assets.example.com");
		ConnectionDiagnosticStep lineStep = report.step(ConnectionDiagnosticStage.LINE_API);

		assertThat(lineStep.status()).isEqualTo(ConnectionDiagnosticStatus.FAILED);
		assertThat(lineStep.detail()).contains("Token").doesNotContain("channel-token");
	}

	// 方法：AI API 要求授權時仍能確認端點可到達，且絕不傳送已設定的 Key。
	@Test
	void shouldReachAiApiWithoutSendingConfiguredKey() {
		RecordingProbe probe = new RecordingProbe();
		probe.aiStatusCode = 401;
		ConnectionDiagnosticService service = new ConnectionDiagnosticService(probe);

		ConnectionDiagnosticReport report = service.run(configuration(), "https://assets.example.com");
		ConnectionDiagnosticStep aiStep = report.step(ConnectionDiagnosticStage.AI_API);

		assertThat(aiStep.status()).isEqualTo(ConnectionDiagnosticStatus.PASSED);
		assertThat(aiStep.detail()).contains("未傳送 API Key").doesNotContain("ai-key");
		assertThat(probe.authorizationHeaders.getLast()).isEmpty();
	}

	//#endregion

	//#region 輔助方法

	// 方法：建立具有連線診斷必要欄位的測試設定。
	private AppConfiguration configuration() {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")))
			.withValue(AppConfigurationField.SERVER_PORT, "8088")
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "channel-token")
			.withValue(AppConfigurationField.LINE_CONNECT_TIMEOUT_SECONDS, "3")
			.withValue(AppConfigurationField.LINE_REQUEST_TIMEOUT_SECONDS, "5")
			.withValue(AppConfigurationField.AI_API_URL, "https://api.openai.com/v1")
			.withValue(AppConfigurationField.AI_API_KEY, "ai-key");
	}

	//#endregion

	//#region 測試替代實作

	/**
	 * 記錄連線呼叫並可注入單一階段失敗的測試 Probe。
	 */
	private static final class RecordingProbe implements ConnectionProbe {

		private final List<URI> requestedUris = new ArrayList<>();
		private final List<String> authorizationHeaders = new ArrayList<>();
		private boolean domainResolutionFailure;
		private int lineStatusCode = 200;
		private int aiStatusCode = 200;

		// 方法：回傳固定 IP，或對受測網域注入 DNS 錯誤。
		@Override
		public List<String> resolve(String host) throws Exception {
			if (domainResolutionFailure && host.equals("broken.example.com")) {
				throw new IllegalStateException("DNS lookup failed");
			}

			return List.of("203.0.113.10");
		}

		// 方法：測試替代實作不開啟實際 TCP Socket。
		@Override
		public void connect(
			String host,
			int port,
			Duration timeout
		) {
		}

		// 方法：測試替代實作不進行實際 TLS 握手。
		@Override
		public void handshake(
			String host,
			int port,
			Duration timeout
		) {
		}

		// 方法：記錄 HTTP 目標與授權標頭，並回傳可控狀態碼。
		@Override
		public ConnectionProbeResponse request(
			URI uri,
			Map<String, String> headers,
			Duration timeout
		) {
			requestedUris.add(uri);
			authorizationHeaders.add(headers.getOrDefault("Authorization", ""));
			int statusCode = uri.getHost().equals("api.line.me")
				? lineStatusCode
				: uri.getHost().equals("api.openai.com")
					? aiStatusCode
					: 200;

			return new ConnectionProbeResponse(statusCode, "response-body");
		}
	}

	//#endregion
}

