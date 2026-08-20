package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

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
 * 以獨立參數與 child environment 啟動使用者自行安裝的 ngrok agent。
 */
public final class NgrokProcess implements NgrokProcessControl {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(NgrokProcess.class);

	private final ProcessLauncher launcher;
	private Process childProcess;
	private NgrokStatus status;

	//#endregion

	//#region 建構子

	// 方法：建立使用 Java ProcessBuilder 啟動 child process 的正式執行器。
	public NgrokProcess() {
		this(ProcessBuilder::start);
	}

	// 方法：建立可替換程序啟動邊界的 ngrok 執行器供測試使用。
	NgrokProcess(ProcessLauncher launcher) {
		this.launcher = Objects.requireNonNull(launcher, "ngrok 程序啟動器不可為 null");
		this.status = NgrokStatus.STOPPED;
	}

	//#endregion

	//#region 方法

	// 方法：驗證 agent 與 Port 後，以不含 Authtoken 的參數陣列啟動 tunnel。
	public synchronized void start(
		Path agent,
		String authtoken,
		int localPort
	) {
		validateAgent(agent);

		if (authtoken == null || authtoken.isBlank()) throw new IllegalArgumentException("ngrok Authtoken 不可為空白");

		if (localPort < 1 || localPort > 65535) throw new IllegalArgumentException("ngrok 本機 Port 無效");

		if (childProcess != null) throw new IllegalStateException("ngrok child process 已經啟動");

		status = NgrokStatus.STARTING;
		List<String> command = List.of(
			agent.toString(),
			"http",
			"http://127.0.0.1:" + localPort,
			"--log=stdout",
			"--log-format=json"
		);
		ProcessBuilder builder = new ProcessBuilder(command);

		// 外部函式：Authtoken 只注入 child environment，不放入命令列或 Log。
		builder.environment().put("NGROK_AUTHTOKEN", authtoken);
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

		try {
			childProcess = launcher.start(builder);
			status = NgrokStatus.RUNNING;

			// 日誌：記錄 ngrok child 已啟動與本機 Port，不輸出命令列或 Authtoken。
			log.info("event=ngrok_process_started localPort={}", localPort);
		}
		catch (IOException exception) {
			status = NgrokStatus.FAILED;

			throw new NgrokProcessException("無法啟動 ngrok agent", exception);
		}
	}

	// 方法：正常終止本物件建立的 child，逾時後才強制停止同一程序。
	public synchronized void stop(Duration timeout) {
		Objects.requireNonNull(timeout, "ngrok 停止 timeout 不可為 null");

		if (childProcess == null) {
			status = NgrokStatus.STOPPED;
			return;
		}

		Process ownedProcess = childProcess;
		childProcess = null;

		try {
			if (ownedProcess.isAlive()) {
				ownedProcess.destroy();

				if (!ownedProcess.waitFor(timeout)) ownedProcess.destroyForcibly();
			}

			status = NgrokStatus.STOPPED;

			// 日誌：記錄本 App 建立的 ngrok child 已完成停止。
			log.info("event=ngrok_process_stopped");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			ownedProcess.destroyForcibly();
			status = NgrokStatus.FAILED;

			throw new NgrokProcessException("停止 ngrok agent 時被中斷", exception);
		}
	}

	// 方法：取得目前 ngrok child process 狀態。
	public synchronized NgrokStatus status() {
		return status;
	}

	// 方法：只接受存在、絕對且副檔名為 exe 的一般檔案。
	public static Path validateAgent(Path agent) {
		Objects.requireNonNull(agent, "ngrok agent 路徑不可為 null");
		Path normalized = agent.normalize();
		String fileName = normalized.getFileName() == null
			? ""
			: normalized.getFileName().toString().toLowerCase(Locale.ROOT);

		if (!normalized.isAbsolute() || !Files.isRegularFile(normalized) || !fileName.endsWith(".exe")) {
			throw new IllegalArgumentException("ngrok agent 必須是存在的絕對 exe 檔案");
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
