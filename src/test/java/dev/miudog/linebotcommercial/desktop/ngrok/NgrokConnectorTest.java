package dev.miudog.linebotcommercial.desktop.ngrok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證可選 ngrok 的停用、成功、timeout 與安全停止流程。
 */
class NgrokConnectorTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：未啟用 ngrok 時不驗證 agent、不啟動程序也不查詢 local API。
	@Test
	void shouldDoNothingWhenNgrokIsDisabled() {
		RecordingProcess process = new RecordingProcess();
		RecordingTunnelProvider tunnels = new RecordingTunnelProvider();
		NgrokConnector connector = new NgrokConnector(process, tunnels, duration -> {
		});

		NgrokConnection connection = connector.start(configuration(false), Duration.ofMillis(10));

		assertThat(connection.enabled()).isFalse();
		assertThat(process.started).isFalse();
		assertThat(tunnels.calls).isZero();
	}

	// 方法：啟用時在 Spring 前取得 HTTPS URL 並寫入 PUBLIC_BASE_URL。
	@Test
	void shouldInjectHttpsUrlBeforeBackendStarts() throws Exception {
		Path agent = createAgent();
		RecordingProcess process = new RecordingProcess();
		RecordingTunnelProvider tunnels = new RecordingTunnelProvider();
		tunnels.responses.add(Optional.empty());
		tunnels.responses.add(Optional.of("https://example.ngrok.app"));
		NgrokConnector connector = new NgrokConnector(process, tunnels, duration -> {
		});

		NgrokConnection connection = connector.start(
			configuration(true).withValue(AppConfigurationField.NGROK_AGENT_PATH, agent.toString()),
			Duration.ofSeconds(1)
		);

		assertThat(connection.publicUrl()).isEqualTo("https://example.ngrok.app");
		assertThat(connection.configuration().value(AppConfigurationField.PUBLIC_BASE_URL))
			.isEqualTo("https://example.ngrok.app");
		assertThat(process.started).isTrue();
	}

	// 方法：timeout 時停止剛建立的 child 並拒絕沿用舊公開網址。
	@Test
	void shouldStopChildAndDiscardStaleUrlOnTimeout() throws Exception {
		Path agent = createAgent();
		RecordingProcess process = new RecordingProcess();
		RecordingTunnelProvider tunnels = new RecordingTunnelProvider();
		NgrokConnector connector = new NgrokConnector(process, tunnels, duration -> {
		});

		assertThatThrownBy(() -> connector.start(
			configuration(true)
				.withValue(AppConfigurationField.NGROK_AGENT_PATH, agent.toString())
				.withValue(AppConfigurationField.PUBLIC_BASE_URL, "https://stale.example"),
			Duration.ofMillis(1)
		)).isInstanceOf(NgrokConnectorException.class);
		assertThat(process.stopped).isTrue();
	}

	// 方法：建立測試用 ngrok.exe 一般檔案。
	private Path createAgent() throws Exception {
		Path agent = temporaryDirectory.resolve("ngrok.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});

		return agent;
	}

	// 方法：建立具有必要 ngrok 欄位的測試設定。
	private AppConfiguration configuration(boolean enabled) {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")))
			.withValue(AppConfigurationField.NGROK_ENABLED, Boolean.toString(enabled))
			.withValue(AppConfigurationField.NGROK_AUTHTOKEN, "test-token");
	}

	/**
	 * 記錄 connector 對 child process 的操作。
	 */
	private static final class RecordingProcess implements NgrokProcessControl {

		private boolean started;
		private boolean stopped;

		// 方法：記錄 ngrok child 已要求啟動。
		@Override
		public void start(
			Path agent,
			String authtoken,
			int localPort
		) {
			started = true;
		}

		// 方法：記錄 connector 已停止其 child。
		@Override
		public void stop(Duration timeout) {
			stopped = true;
		}

		// 方法：依是否啟動回傳測試 child 狀態。
		@Override
		public NgrokStatus status() {
			return started && !stopped ? NgrokStatus.RUNNING : NgrokStatus.STOPPED;
		}
	}

	/**
	 * 依序回傳預先排定的 local API 查詢結果。
	 */
	private static final class RecordingTunnelProvider implements NgrokTunnelProvider {

		private final ArrayDeque<Optional<String>> responses = new ArrayDeque<>();
		private int calls;

		// 方法：記錄查詢次數並回傳下一個 tunnel 結果。
		@Override
		public Optional<String> fetchHttpsUrl(Duration timeout) {
			calls++;

			return responses.isEmpty() ? Optional.empty() : responses.removeFirst();
		}
	}
}
