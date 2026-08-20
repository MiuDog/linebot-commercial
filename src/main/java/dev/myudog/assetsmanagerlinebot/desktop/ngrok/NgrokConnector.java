package dev.myudog.assetsmanagerlinebot.desktop.ngrok;

import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfiguration;
import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfigurationField;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 協調可選 ngrok child 與 local API，並在 Spring 前產生新的公開網址。
 */
public final class NgrokConnector {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(NgrokConnector.class);
	private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

	private final NgrokProcessControl process;
	private final NgrokTunnelProvider tunnelProvider;
	private final Sleeper sleeper;

	//#endregion

	//#region 建構子

	// 方法：建立使用正式 ngrok process 與預設 4040 local API 的 connector。
	public NgrokConnector() {
		this(new NgrokProcess(), new NgrokLocalApiClient(4040), Thread::sleep);
	}

	// 方法：建立可替換程序、local API 與等待機制的 connector。
	NgrokConnector(
		NgrokProcessControl process,
		NgrokTunnelProvider tunnelProvider,
		Sleeper sleeper
	) {
		this.process = Objects.requireNonNull(process, "ngrok process 不可為 null");
		this.tunnelProvider = Objects.requireNonNull(tunnelProvider, "ngrok tunnel provider 不可為 null");
		this.sleeper = Objects.requireNonNull(sleeper, "ngrok 等待機制不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：未啟用時保持本機模式；啟用時等待新的 HTTPS URL 並注入設定。
	public synchronized NgrokConnection start(
		AppConfiguration configuration,
		Duration startupTimeout
	) {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		Objects.requireNonNull(startupTimeout, "ngrok 啟動 timeout 不可為 null");
		boolean enabled = Boolean.parseBoolean(configuration.value(AppConfigurationField.NGROK_ENABLED));

		if (!enabled) {
			return new NgrokConnection(
				false,
				configuration.value(AppConfigurationField.PUBLIC_BASE_URL),
				configuration
			);
		}

		if (startupTimeout.isNegative() || startupTimeout.isZero()) {
			throw new IllegalArgumentException("ngrok 啟動 timeout 必須大於零");
		}

		AppConfiguration withoutStaleUrl = configuration.withValue(AppConfigurationField.PUBLIC_BASE_URL, "");
		Path agent = Path.of(configuration.value(AppConfigurationField.NGROK_AGENT_PATH));
		int localPort = Integer.parseInt(configuration.value(AppConfigurationField.SERVER_PORT));

		try {
			process.start(
				agent,
				configuration.value(AppConfigurationField.NGROK_AUTHTOKEN),
				localPort
			);

			return awaitTunnel(withoutStaleUrl, startupTimeout);
		}
		catch (RuntimeException exception) {
			process.stop(STOP_TIMEOUT);

			if (exception instanceof NgrokConnectorException safeException) throw safeException;

			throw new NgrokConnectorException("ngrok 啟動失敗，請檢查 agent 與設定", exception);
		}
	}

	// 方法：停止且只停止本 connector 所管理的 ngrok child process。
	public synchronized void stop() {
		process.stop(STOP_TIMEOUT);
	}

	// 方法：在總 timeout 內輪詢 local API，拒絕 child 提前結束與過期網址。
	private NgrokConnection awaitTunnel(
		AppConfiguration configuration,
		Duration startupTimeout
	) {
		long deadline = System.nanoTime() + startupTimeout.toNanos();

		while (System.nanoTime() < deadline) {
			if (process.status() != NgrokStatus.RUNNING) {
				throw new NgrokConnectorException("ngrok agent 已提前結束", null);
			}

			Optional<String> publicUrl = tunnelProvider.fetchHttpsUrl(QUERY_TIMEOUT);

			if (publicUrl.isPresent()) {
				String connectedUrl = publicUrl.orElseThrow();
				AppConfiguration connectedConfiguration = configuration.withValue(
					AppConfigurationField.PUBLIC_BASE_URL,
					connectedUrl
				);

				// 日誌：記錄 ngrok tunnel 已就緒；公開 URL 可顯示，但不輸出任何 Authtoken。
				log.info("event=ngrok_tunnel_ready publicUrl={}", connectedUrl);

				return new NgrokConnection(true, connectedUrl, connectedConfiguration);
			}

			try {
				// 外部函式：短暫等待後再次查詢 local API，避免忙迴圈占用 CPU。
				sleeper.sleep(POLL_INTERVAL);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();

				throw new NgrokConnectorException("等待 ngrok tunnel 時被中斷", exception);
			}
		}

		throw new NgrokConnectorException("等待 ngrok HTTPS tunnel 逾時", null);
	}

	//#endregion

	/**
	 * 隔離輪詢等待以提供快速且可重現的 timeout 測試。
	 */
	@FunctionalInterface
	interface Sleeper {

		// 方法：暫停指定期間後繼續查詢 local API。
		void sleep(Duration duration) throws InterruptedException;
	}
}
