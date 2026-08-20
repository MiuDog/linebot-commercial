package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以獨立參數與 child environment 啟動使用者自行安裝的 cloudflared agent。
 */
public final class CloudflareProcess implements CloudflareProcessControl {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(CloudflareProcess.class);

	private final ProcessLauncher launcher;
	private Process childProcess;
	private CloudflareStatus status;

	//#endregion

	//#region 建構子

	// 方法：建立使用 Java ProcessBuilder 啟動 child process 的正式執行器。
	public CloudflareProcess() {
		this(ProcessBuilder::start);
	}

	// 方法：建立可替換程序啟動邊界的 cloudflared 執行器供測試使用。
	CloudflareProcess(ProcessLauncher launcher) {
		this.launcher = Objects.requireNonNull(launcher, "cloudflared 程序啟動器不可為 null");
		this.status = CloudflareStatus.STOPPED;
	}

	//#endregion

	//#region 方法

	// 方法：驗證 agent 與 Token 後，以不含 Token 的參數陣列啟動 tunnel。
	@Override
	public synchronized void start(
		Path agent,
		String tunnelToken
	) {
		validateAgent(agent);

		if (tunnelToken == null || tunnelToken.isBlank()) throw new IllegalArgumentException("Cloudflare Tunnel Token 不可為空白");

		if (childProcess != null) throw new IllegalStateException("cloudflared child process 已經啟動");

		status = CloudflareStatus.STARTING;
		List<String> command = List.of(
			agent.toString(),
			"tunnel",
			"run"
		);
		ProcessBuilder builder = new ProcessBuilder(command);

		// 外部函式：Tunnel Token 只注入 child environment，不放入命令列或 Log。
		builder.environment().put("TUNNEL_TOKEN", tunnelToken);
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

		try {
			childProcess = launcher.start(builder);
			status = CloudflareStatus.RUNNING;

			// 日誌：記錄 cloudflared child 已啟動，不輸出命令列或 Token。
			log.info("event=cloudflare_process_started");
		}
		catch (IOException exception) {
			status = CloudflareStatus.FAILED;

			throw new CloudflareProcessException("無法啟動 cloudflared agent", exception);
		}
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
		if (childProcess != null && !childProcess.isAlive()) status = CloudflareStatus.STOPPED;

		return status;
	}

	// 方法：解析或搜尋可用的 cloudflared agent 路徑。
	public static Path resolveAgent(String configuredPath) {
		if (configuredPath != null && !configuredPath.isBlank()) return validateAgent(Path.of(configuredPath));

		// 嘗試常見 Windows 位置與 PATH 搜尋
		List<Path> candidateLocations = List.of(
			Path.of("C:\\Program Files\\cloudflared\\cloudflared.exe"),
			Path.of("C:\\ProgramData\\cloudflared\\cloudflared.exe"),
			Path.of("C:\\cloudflared\\cloudflared.exe")
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
}
