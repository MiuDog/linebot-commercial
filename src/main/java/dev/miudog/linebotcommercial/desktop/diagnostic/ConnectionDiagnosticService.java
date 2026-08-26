package dev.miudog.linebotcommercial.desktop.diagnostic;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 依存分層測試本機服務、指定網域與 LINE Bot API。
 */
public final class ConnectionDiagnosticService {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(ConnectionDiagnosticService.class);
	private static final URI LINE_BOT_INFO_URI = URI.create("https://api.line.me/v2/bot/info");

	private final ConnectionProbe probe;

	//#endregion

	//#region 建構子

	// 方法：建立使用真實 JDK 網路邊界的連線診斷服務。
	public ConnectionDiagnosticService() {
		this(new JavaConnectionProbe());
	}

	// 方法：以可替換 Probe 建立可重現失敗階段的診斷服務。
	ConnectionDiagnosticService(ConnectionProbe probe) {
		this.probe = Objects.requireNonNull(probe, "連線 Probe 不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：使用當前設定與指定網域執行 Commercial 完整連線診斷。
	public ConnectionDiagnosticReport run(
		AppConfiguration configuration,
		String requestedTarget
	) {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		URI target = normalizeTarget(requestedTarget);
		Duration connectTimeout = duration(configuration, AppConfigurationField.LINE_CONNECT_TIMEOUT_SECONDS);
		Duration requestTimeout = duration(configuration, AppConfigurationField.LINE_REQUEST_TIMEOUT_SECONDS);
		String diagnosticId = UUID.randomUUID().toString();
		List<ConnectionDiagnosticStep> steps = new ArrayList<>();

		// 日誌：記錄不含 Token 的診斷開始事件與目標主機。
		log.info("event=connection_diagnostic_started diagnosticId={} targetHost={}", diagnosticId, target.getHost());

		// 步驟一：確認桌面 App 內的 Spring 服務已實際回應。
		testLocalService(configuration, requestTimeout, diagnosticId, steps);

		// 步驟二：依序測試指定網域的 DNS、TCP、TLS 與 HTTP 回應。
		testTarget(target, connectTimeout, requestTimeout, diagnosticId, steps);

		// 步驟三：獨立驗證 LINE API 連線與 Channel Token，不被受測網域失敗中斷。
		testLineApi(configuration, requestTimeout, diagnosticId, steps);

		// 步驟四：匿名測試可注入 AI API endpoint，避免將 Key 傳送到未信任網址。
		testAiApi(configuration, requestTimeout, diagnosticId, steps);

		ConnectionDiagnosticReport report = new ConnectionDiagnosticReport(
			diagnosticId,
			target.toString(),
			steps
		);

		// 日誌：記錄整體診斷完成狀態與失敗數量供 Log 搜尋。
		log.info("event=connection_diagnostic_completed diagnosticId={} successful={} failedSteps={}",
			diagnosticId,
			report.successful(),
			steps.stream().filter(step -> step.status() == ConnectionDiagnosticStatus.FAILED).count()
		);

		return report;
	}

	// 方法：對 loopback health endpoint 傳送請求以分離後端未啟動問題。
	private void testLocalService(
		AppConfiguration configuration,
		Duration requestTimeout,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps
	) {
		URI localUri = URI.create(
			"http://127.0.0.1:"
				+ configuration.value(AppConfigurationField.SERVER_PORT)
				+ "/actuator/health"
		);

		addMeasuredStep(
			ConnectionDiagnosticStage.LOCAL_SERVICE,
			diagnosticId,
			steps,
			() -> {
				ConnectionProbeResponse response = probe.request(localUri, Map.of(), requestTimeout);

				if (response.statusCode() >= 200 && response.statusCode() < 300) return "本機 health endpoint 已回應 HTTP " + response.statusCode();

				throw new DiagnosticFailure("本機服務回應 HTTP " + response.statusCode());
			}
		);
	}

	// 方法：依存關係執行目標網域的四層連線測試。
	private void testTarget(
		URI target,
		Duration connectTimeout,
		Duration requestTimeout,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps
	) {
		String host = target.getHost();
		int port = port(target);
		boolean dnsReady = addMeasuredStep(
			ConnectionDiagnosticStage.TARGET_DNS,
			diagnosticId,
			steps,
			() -> {
				List<String> addresses = probe.resolve(host);

				if (addresses.isEmpty()) throw new DiagnosticFailure("DNS 未回傳 IP 位址");

				return "解析為 " + String.join(", ", addresses);
			}
		);

		if (!dnsReady) {
			addSkippedTargetSteps(steps, "DNS 失敗，無法繼續測試指定網域");

			return;
		}

		boolean tcpReady = addMeasuredStep(
			ConnectionDiagnosticStage.TARGET_TCP,
			diagnosticId,
			steps,
			() -> {
				probe.connect(host, port, connectTimeout);

				return "TCP " + host + ":" + port + " 已連線";
			}
		);

		if (!tcpReady) {
			addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_TLS, "TCP 失敗，無法進行 TLS");
			addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_HTTP, "TCP 失敗，無法進行 HTTP");

			return;
		}

		boolean tlsReady = testTls(target, host, port, connectTimeout, diagnosticId, steps);

		if (!tlsReady) {
			addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_HTTP, "TLS 失敗，無法傳送 HTTPS 請求");

			return;
		}

		addMeasuredStep(
			ConnectionDiagnosticStage.TARGET_HTTP,
			diagnosticId,
			steps,
			() -> {
				ConnectionProbeResponse response = probe.request(target, Map.of(), requestTimeout);

				return "目標已回應 HTTP " + response.statusCode() + "（代表網路與服務層可到達）";
			}
		);
	}

	// 方法：HTTPS 目標驗證憑證，HTTP 目標則明確標示不需要 TLS。
	private boolean testTls(
		URI target,
		String host,
		int port,
		Duration timeout,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps
	) {
		if ("http".equalsIgnoreCase(target.getScheme())) {
			addPassedStep(steps, ConnectionDiagnosticStage.TARGET_TLS, "HTTP 目標不使用 TLS");

			return true;
		}

		return addMeasuredStep(
			ConnectionDiagnosticStage.TARGET_TLS,
			diagnosticId,
			steps,
			() -> {
				probe.handshake(host, port, timeout);

				return "TLS 握手與憑證驗證成功";
			}
		);
	}

	// 方法：使用目前 Channel Token 取得 Bot 資訊以驗證外網與憑證。
	private void testLineApi(
		AppConfiguration configuration,
		Duration requestTimeout,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps
	) {
		String token = configuration.value(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN);

		if (token.isBlank()) {
			addFailedStep(steps, ConnectionDiagnosticStage.LINE_API, "尚未設定 LINE Channel Token");

			return;
		}

		addMeasuredStep(
			ConnectionDiagnosticStage.LINE_API,
			diagnosticId,
			steps,
			() -> {
				ConnectionProbeResponse response = probe.request(
					LINE_BOT_INFO_URI,
					Map.of("Authorization", "Bearer " + token),
					requestTimeout
				);

				if (response.statusCode() >= 200 && response.statusCode() < 300) return "LINE Bot API 已回應，Channel Token 有效";

				if (response.statusCode() == 401 || response.statusCode() == 403) {
					throw new DiagnosticFailure("LINE Channel Token 無效或無權限（HTTP " + response.statusCode() + "）");
				}

				throw new DiagnosticFailure("LINE Bot API 回應 HTTP " + response.statusCode());
			}
		);
	}

	// 方法：匿名呼叫 OpenAI-compatible models endpoint，只驗證網路與端點不傳送 AI API Key。
	private void testAiApi(
		AppConfiguration configuration,
		Duration requestTimeout,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps
	) {
		String apiUrl = configuration.value(AppConfigurationField.AI_API_URL);

		if (apiUrl.isBlank()) {
			addSkippedStep(steps, ConnectionDiagnosticStage.AI_API, "尚未設定 AI API 網址");

			return;
		}

		URI modelsUri = modelsUri(apiUrl);
		addMeasuredStep(
			ConnectionDiagnosticStage.AI_API,
			diagnosticId,
			steps,
			() -> {
				ConnectionProbeResponse response = probe.request(modelsUri, Map.of(), requestTimeout);

				if (response.statusCode() >= 200 && response.statusCode() < 300) return "AI API 已匿名回應 HTTP " + response.statusCode() + "，未傳送或驗證 API Key";

				if (response.statusCode() == 401 || response.statusCode() == 403) return "AI API 可到達且要求授權（HTTP " + response.statusCode() + "），為安全起見未傳送 API Key";

				if (response.statusCode() == 404) throw new DiagnosticFailure("AI API 網址不支援 /models，請檢查 AI API 網址");

				throw new DiagnosticFailure("AI API 回應 HTTP " + response.statusCode());
			}
		);
	}

	// 方法：計時執行單一 Probe，並將成功或安全錯誤轉為結構化步驟。
	private boolean addMeasuredStep(
		ConnectionDiagnosticStage stage,
		String diagnosticId,
		List<ConnectionDiagnosticStep> steps,
		DiagnosticOperation operation
	) {
		long startedAt = System.nanoTime();

		try {
			String detail = operation.run();
			long durationMillis = elapsedMillis(startedAt);
			steps.add(new ConnectionDiagnosticStep(stage, ConnectionDiagnosticStatus.PASSED, detail, durationMillis));

			// 日誌：記錄單一診斷階段成功與耗時，不記錄請求標頭。
			log.info("event=connection_diagnostic_step_completed diagnosticId={} stage={} status={} durationMs={}",
				diagnosticId,
				stage,
				ConnectionDiagnosticStatus.PASSED,
				durationMillis
			);

			return true;
		}
		catch (Exception exception) {
			long durationMillis = elapsedMillis(startedAt);
			String detail = failureDetail(stage, exception);
			steps.add(new ConnectionDiagnosticStep(stage, ConnectionDiagnosticStatus.FAILED, detail, durationMillis));

			// 日誌：記錄失敗階段、耗時與例外類型，不寫入 Token 或外部回應內容。
			log.warn("event=connection_diagnostic_step_completed diagnosticId={} stage={} status={} durationMs={} errorType={}",
				diagnosticId,
				stage,
				ConnectionDiagnosticStatus.FAILED,
				durationMillis,
				exception.getClass().getSimpleName()
			);

			return false;
		}
	}

	// 方法：加入因前置階段失敗而未執行的目標網域步驟。
	private void addSkippedTargetSteps(
		List<ConnectionDiagnosticStep> steps,
		String reason
	) {
		addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_TCP, reason);
		addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_TLS, reason);
		addSkippedStep(steps, ConnectionDiagnosticStage.TARGET_HTTP, reason);
	}

	// 方法：加入不需計時的成功診斷步驟。
	private void addPassedStep(
		List<ConnectionDiagnosticStep> steps,
		ConnectionDiagnosticStage stage,
		String detail
	) {
		steps.add(new ConnectionDiagnosticStep(stage, ConnectionDiagnosticStatus.PASSED, detail, 0L));
	}

	// 方法：加入因相依錯誤而略過的診斷步驟。
	private void addSkippedStep(
		List<ConnectionDiagnosticStep> steps,
		ConnectionDiagnosticStage stage,
		String detail
	) {
		steps.add(new ConnectionDiagnosticStep(stage, ConnectionDiagnosticStatus.SKIPPED, detail, 0L));
	}

	// 方法：加入未進行外部呼叫就能確定的失敗步驟。
	private void addFailedStep(
		List<ConnectionDiagnosticStep> steps,
		ConnectionDiagnosticStage stage,
		String detail
	) {
		steps.add(new ConnectionDiagnosticStep(stage, ConnectionDiagnosticStatus.FAILED, detail, 0L));
	}

	// 方法：將使用者輸入正規化為具有主機的 HTTP 或 HTTPS URI。
	private URI normalizeTarget(String requestedTarget) {
		String target = Objects.requireNonNullElse(requestedTarget, "").trim();

		if (target.isBlank()) throw new IllegalArgumentException("請輸入要測試的網域");

		if (!target.contains("://")) target = "https://" + target;

		URI uri = URI.create(target);
		String scheme = Objects.requireNonNullElse(uri.getScheme(), "");

		if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
			throw new IllegalArgumentException("只支援 HTTP 或 HTTPS 測試目標");
		}

		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("測試目標缺少有效網域");
		}

		return uri;
	}

	// 方法：取得 URI 明確埠號或依協定推導預設埠號。
	private int port(URI uri) {
		if (uri.getPort() > 0) return uri.getPort();

		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	// 方法：將可注入 AI API base URL 轉為不產生推論的 models 檢查網址。
	private URI modelsUri(String apiUrl) {
		String normalized = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;

		if (normalized.endsWith("/models")) return URI.create(normalized);

		return URI.create(normalized + "/models");
	}

	// 方法：將已驗證的正整數秒設定轉為連線時限。
	private Duration duration(
		AppConfiguration configuration,
		AppConfigurationField field
	) {
		return Duration.ofSeconds(Long.parseLong(configuration.value(field)));
	}

	// 方法：計算最少為零的步驟耗時毫秒。
	private long elapsedMillis(long startedAt) {
		return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
	}

	// 方法：轉換成不含機密回應內容的使用者可讀錯誤。
	private String failureDetail(
		ConnectionDiagnosticStage stage,
		Exception exception
	) {
		if (exception instanceof DiagnosticFailure) return exception.getMessage();

		return stage.label() + " 無法完成：" + exception.getClass().getSimpleName();
	}

	//#endregion

	/**
	 * 定義可計時且可失敗的單一診斷呼叫。
	 */
	@FunctionalInterface
	private interface DiagnosticOperation {

		// 方法：執行診斷呼叫並回傳成功說明。
		String run() throws Exception;
	}

	/**
	 * 表示外部服務有回應但語意上不符合診斷成功條件。
	 */
	private static final class DiagnosticFailure extends Exception {

		// 方法：建立可安全顯示給使用者的診斷錯誤。
		private DiagnosticFailure(String message) {
			super(message);
		}
	}
}

