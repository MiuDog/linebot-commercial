package dev.miudog.linebotcommercial.desktop.cloudflare;

import dev.miudog.linebotcommercial.observability.SensitiveDataSanitizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以獨立參數與 child environment 啟動使用者自行安裝的 cloudflared agent。
 */
public final class CloudflareProcess implements CloudflareProcessControl {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(CloudflareProcess.class);
	private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(200);

	// 外部函式：建立共用的 loopback 健康檢查 client，避免每次輪詢重建連線資源。
	private static final HttpClient READINESS_HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(1))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private final ProcessLauncher launcher;
	private final IntSupplier metricsPortSupplier;
	private final ReadinessProbe readinessProbe;
	private Process childProcess;
	private volatile CloudflareStatus status;
	private int metricsPort;
	private volatile String lastDiagnostic;
	private volatile CloudflareAgentIdentity identity;
	private String tunnelToken;

	//#endregion

	//#region 建構子

	// 方法：建立使用 Java ProcessBuilder 啟動 child process 的正式執行器。
	public CloudflareProcess() {
		this(ProcessBuilder::start, CloudflareProcess::findAvailableLoopbackPort, CloudflareProcess::probeReady);
	}

	// 方法：建立可替換程序啟動邊界的 cloudflared 執行器供測試使用。
	CloudflareProcess(
		ProcessLauncher launcher,
		IntSupplier metricsPortSupplier,
		ReadinessProbe readinessProbe
	) {
		this.launcher = Objects.requireNonNull(launcher, "cloudflared 程序啟動器不可為 null");
		this.metricsPortSupplier = Objects.requireNonNull(metricsPortSupplier, "metrics port 來源不可為 null");
		this.readinessProbe = Objects.requireNonNull(readinessProbe, "readiness probe 不可為 null");
		this.status = CloudflareStatus.STOPPED;
		this.lastDiagnostic = "";
		this.identity = CloudflareAgentIdentity.empty();
		this.tunnelToken = "";
	}

	//#endregion

	//#region 方法

	// 方法：驗證 agent 與 Token 後，以不含 Token 的參數陣列啟動 tunnel。
	@Override
	public synchronized void start(
		Path agent,
		String tunnelToken,
		CloudflareProtocol protocol
	) {
		validateAgent(agent);
		Objects.requireNonNull(protocol, "Cloudflare 協定不可為 null");
		String token = sanitizeToken(tunnelToken);

		if (token.isBlank()) throw new IllegalArgumentException("Cloudflare Tunnel Token 不可為空白");

		if (childProcess != null) throw new IllegalStateException("cloudflared child process 已經啟動");

		status = CloudflareStatus.STARTING;
		metricsPort = metricsPortSupplier.getAsInt();
		this.tunnelToken = token;
		lastDiagnostic = "";
		identity = CloudflareAgentIdentity.empty();
		List<String> command = List.of(
			agent.toString(),
			"tunnel",
			"--no-autoupdate",
			"--protocol",
			protocol.argument(),
			"--loglevel",
			"info",
			"--metrics",
			"127.0.0.1:" + metricsPort,
			"run"
		);
		ProcessBuilder builder = new ProcessBuilder(command);

		// 外部函式：Tunnel Token 只注入 child environment，不放入命令列或 Log。
		builder.environment().put("TUNNEL_TOKEN", token);
		builder.redirectErrorStream(true);

		try {
			Process startedProcess = launcher.start(builder);
			childProcess = startedProcess;

			// 外部函式：以 daemon 執行緒讀取 cloudflared 診斷，避免 child pipe 填滿而停住。
			Thread.ofPlatform()
				.daemon()
				.name("cloudflared-diagnostics")
				.start(() -> readDiagnostics(startedProcess));

			// 日誌：記錄 cloudflared child 已啟動及固定低基數協定，不輸出命令列或 Token。
			log.info("event=cloudflare_process_started protocol={}", protocol.argument());
		}
		catch (IOException exception) {
			status = CloudflareStatus.FAILED;

			throw new CloudflareProcessException("無法啟動 cloudflared agent", exception);
		}
	}

	// 方法：輪詢只綁定 loopback 的官方 readiness endpoint，確認 Tunnel 已連上 Cloudflare Edge。
	@Override
	public boolean awaitReady(Duration timeout) {
		Objects.requireNonNull(timeout, "cloudflared readiness timeout 不可為 null");

		if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("readiness timeout 必須大於零");

		long deadline = System.nanoTime() + timeout.toNanos();

		while (System.nanoTime() < deadline) {
			Process observedProcess;
			synchronized (this) {
				observedProcess = childProcess;
			}

			if (observedProcess == null || !observedProcess.isAlive()) {
				status = CloudflareStatus.FAILED;
				return false;
			}

			try {
				// 外部函式：readiness 只有在至少一條 Cloudflare Edge 連線存在時回傳成功。
				if (readinessProbe.ready(metricsPort, remaining(deadline))) {
					status = CloudflareStatus.RUNNING;

					return true;
				}

				// 外部函式：短暫退避，避免 VPN 尚在建立路由時密集輪詢 loopback。
				Thread.sleep(READINESS_POLL_INTERVAL);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				status = CloudflareStatus.FAILED;
				lastDiagnostic = "等待 Cloudflare Tunnel 就緒時被中斷";

				return false;
			}
		}

		status = CloudflareStatus.FAILED;

		return false;
	}

	// 方法：取得最後一筆已清理的 cloudflared 診斷。
	@Override
	public String diagnostic() {
		return lastDiagnostic;
	}

	// 方法：取得 cloudflared 官方啟動訊息中已識別的 Tunnel、Connector 與電腦身分。
	@Override
	public CloudflareAgentIdentity identity() {
		return identity;
	}

	// 方法：正常終止本物件建立的 child，逾時後才強制停止同一程序。
	@Override
	public synchronized void stop(Duration timeout) {
		Objects.requireNonNull(timeout, "cloudflared 停止 timeout 不可為 null");

		if (childProcess == null) {
			status = CloudflareStatus.STOPPED;
			return;
		}

		Process ownedProcess = childProcess;
		childProcess = null;

		try {
			if (ownedProcess.isAlive()) {
				ownedProcess.destroy();

				if (!ownedProcess.waitFor(timeout)) ownedProcess.destroyForcibly();
			}

			status = CloudflareStatus.STOPPED;

			// 日誌：記錄本 App 建立的 cloudflared child 已完成停止。
			log.info("event=cloudflare_process_stopped");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			ownedProcess.destroyForcibly();
			status = CloudflareStatus.FAILED;

			throw new CloudflareProcessException("停止 cloudflared agent 時被中斷", exception);
		}
	}

	// 方法：取得目前 cloudflared child process 狀態。
	@Override
	public synchronized CloudflareStatus status() {
		if (childProcess != null && !childProcess.isAlive() && status != CloudflareStatus.FAILED) {
			status = CloudflareStatus.STOPPED;
		}

		return status;
	}

	// 方法：持續讀取 child 診斷並只把警告與錯誤提升為正式 App Log。
	private void readDiagnostics(Process observedProcess) {
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(observedProcess.getInputStream(), StandardCharsets.UTF_8)
		)) {
			String line;

			while ((line = reader.readLine()) != null) {
				String safeLine = sanitizeDiagnostic(line);
				if (safeLine.isBlank()) continue;

				lastDiagnostic = safeLine;
				identity = identity.withDiagnostic(safeLine);

				// 日誌：cloudflared 的網路警告與錯誤需保留，正常連線細節只在 DEBUG 顯示。
				if (isWarningOrError(safeLine)) {
					// 日誌：保留已清理的 cloudflared 網路警告供客戶排除 VPN 問題。
					log.warn("event=cloudflare_agent_diagnostic detail={}", safeLine);
				}
				else {
					// 日誌：正常連線細節降為 DEBUG，避免日常 Log 被大量訊息淹沒。
					log.debug("event=cloudflare_agent_diagnostic detail={}", safeLine);
				}
			}
		}
		catch (IOException exception) {
			lastDiagnostic = "無法讀取 cloudflared 診斷";

			// 日誌：只記錄讀取失敗類型，不輸出 child 原始內容或 Tunnel Token。
			log.warn("event=cloudflare_diagnostic_reader_failed errorType={}", exception.getClass().getSimpleName());
		}
	}

	// 方法：再次套用共用清理器並移除本次 Tunnel Token 原文。
	private String sanitizeDiagnostic(String line) {
		String safeLine = SensitiveDataSanitizer.sanitizeLogLine(line);

		return tunnelToken.isBlank() ? safeLine : safeLine.replace(tunnelToken, "[REDACTED]");
	}

	// 方法：辨識 cloudflared 官方等級與常見連線失敗文字。
	private boolean isWarningOrError(String line) {
		String normalized = line.toLowerCase(Locale.ROOT);

		return normalized.contains(" err ")
			|| normalized.startsWith("err ")
			|| normalized.contains(" wrn ")
			|| normalized.startsWith("wrn ")
			|| normalized.contains("error")
			|| normalized.contains("failed")
			|| normalized.contains("timeout");
	}

	// 方法：取得截止時間前剩餘的正值期間，供單次 readiness request 使用。
	private Duration remaining(long deadline) {
		return Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
	}

	// 方法：向作業系統取得只供 loopback readiness 使用的暫時可用埠。
	private static int findAvailableLoopbackPort() {
		try {
			// 外部函式：由 Windows 配置可用 loopback 埠，避免固定 metrics 埠互相衝突。
			try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
				return socket.getLocalPort();
			}
		}
		catch (IOException exception) {
			throw new CloudflareProcessException("無法配置 cloudflared readiness 埠", exception);
		}
	}

	// 方法：呼叫 cloudflared 官方 readiness endpoint，非 200 或尚未監聽時回傳 false。
	private static boolean probeReady(
		int port,
		Duration timeout
	) throws InterruptedException {
		Duration requestTimeout = timeout.compareTo(Duration.ofSeconds(1)) > 0
			? Duration.ofSeconds(1)
			: timeout;
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:" + port + "/ready"))
			.timeout(requestTimeout)
			.GET()
			.build();

		try {
			// 外部函式：只連線到 loopback metrics endpoint，不接受重新導向或外部網址。
			HttpResponse<Void> response = READINESS_HTTP_CLIENT.send(
				request,
				HttpResponse.BodyHandlers.discarding()
			);

			return response.statusCode() == 200;
		}
		catch (IOException exception) {
			return false;
		}
	}

	// 方法：淨化使用者可能包含指令前綴或前後空格的 Tunnel Token。
	public static String sanitizeToken(String raw) {
		if (raw == null) return "";

		String trimmed = raw.trim();
		int eyIndex = trimmed.indexOf("eyJ");

		if (eyIndex >= 0) {
			String candidate = trimmed.substring(eyIndex).trim();
			int spaceIdx = candidate.indexOf(' ');

			if (spaceIdx > 0) candidate = candidate.substring(0, spaceIdx);

			int quoteIdx = candidate.indexOf('"');

			if (quoteIdx > 0) candidate = candidate.substring(0, quoteIdx);

			return candidate;
		}

		return trimmed;
	}

	// 方法：解析或搜尋可用的 cloudflared agent 路徑。
	public static Path resolveAgent(String configuredPath) {
		if (configuredPath != null && !configuredPath.isBlank()) return validateAgent(Path.of(configuredPath));

		String userHome = System.getProperty("user.home", "");
		List<Path> candidateLocations = List.of(
			Path.of("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe"),
			Path.of("C:\\Program Files\\cloudflared\\cloudflared.exe"),
			Path.of("C:\\ProgramData\\cloudflared\\cloudflared.exe"),
			Path.of("C:\\cloudflared\\cloudflared.exe"),
			Path.of(userHome, "Downloads", "cloudflared-windows-amd64.exe"),
			Path.of(userHome, "Downloads", "cloudflared.exe")
		);

		for (Path candidate : candidateLocations) {
			if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
		}

		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String entry : pathEnv.split(File.pathSeparator)) {
				Path inPath = Path.of(entry).resolve("cloudflared.exe");
				if (Files.isRegularFile(inPath)) return inPath.toAbsolutePath().normalize();
			}
		}

		throw new IllegalArgumentException("找不到 cloudflared 執行檔，請在設定中指定 cloudflared 執行檔路徑");
	}

	// 方法：只接受存在、絕對且副檔名為 exe 的一般檔案。
	public static Path validateAgent(Path agent) {
		Objects.requireNonNull(agent, "cloudflared agent 路徑不可為 null");
		Path normalized = agent.normalize();
		String fileName = normalized.getFileName() == null
			? ""
			: normalized.getFileName().toString().toLowerCase(Locale.ROOT);

		if (!normalized.isAbsolute() || !Files.isRegularFile(normalized) || !fileName.endsWith(".exe")) {
			throw new IllegalArgumentException("cloudflared agent 必須是存在的絕對 exe 檔案");
		}

		return normalized;
	}

	//#endregion

	/**
	 * 隔離 ProcessBuilder 的外部程序建立操作。
	 */
	@FunctionalInterface
	interface ProcessLauncher {

		// 方法：依已完成安全設定的 ProcessBuilder 啟動 child process。
		Process start(ProcessBuilder builder) throws IOException;
	}

	/**
	 * 隔離 readiness HTTP 呼叫，讓 VPN 與失敗測試不依賴真實網路。
	 */
	@FunctionalInterface
	interface ReadinessProbe {

		// 方法：確認指定 loopback port 是否已具備有效 Cloudflare Edge 連線。
		boolean ready(
			int port,
			Duration timeout
		) throws InterruptedException;
	}
}
