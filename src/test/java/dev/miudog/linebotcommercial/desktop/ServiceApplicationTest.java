package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miudog.linebotcommercial.desktop.control.ServiceControlCommand;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlHost;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlResponse;
import dev.miudog.linebotcommercial.desktop.control.ServiceInstanceResource;
import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 驗證 headless service 對設定、Tunnel、Spring 與停止順序的完整所有權。
 */
class ServiceApplicationTest {

	// 方法：服務必須先載入設定、建立 Tunnel，再啟動 Spring 並註冊停止鉤子。
	@Test
	void shouldOwnTheCompleteHeadlessServiceLifecycle() {
		List<String> operations = new ArrayList<>();
		AppConfiguration configuration = configuration();
		ServiceApplication application = new ServiceApplication(
			() -> {
				operations.add("configuration");

				return Optional.of(configuration);

			},
			configured -> {
				operations.add("tunnel");

				return configured;

			},
			(configured, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		application.start(new String[] {"--app.runtime.mode=service"});

		assertThat(operations).containsExactly("configuration", "hook", "tunnel", "spring");
		assertThat(application.running()).isTrue();
	}

	// 方法：缺少已保存設定時拒絕啟動，避免服務以空白 Token 反覆重新啟動。
	@Test
	void shouldRejectStartupBeforeConfigurationExists() {
		List<String> operations = new ArrayList<>();
		ServiceApplication application = new ServiceApplication(
			Optional::empty,
			configured -> {
				operations.add("tunnel");

				return configured;

			},
			(configured, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		assertThatThrownBy(() -> application.start(new String[0]))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("尚未完成 App 設定");
		assertThat(operations).isEmpty();
	}

	// 方法：Tunnel 或 Spring 啟動失敗時只執行一次完整清理。
	@Test
	void shouldCleanUpOnceWhenStartupFails() {
		List<String> operations = new ArrayList<>();
		ServiceApplication application = new ServiceApplication(
			() -> Optional.of(configuration()),
			configured -> {
				operations.add("tunnel");

				return configured;

			},
			(configured, arguments) -> {
				operations.add("spring");

				throw new IllegalStateException("spring failed");

			},
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		assertThatThrownBy(() -> application.start(new String[0]))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("spring failed");
		application.shutdown();

		assertThat(operations).containsExactly("hook", "tunnel", "spring", "stop");
		assertThat(application.running()).isFalse();
	}

	// 方法：重新啟動時必須停止舊資源、重新載入設定，且不可重複註冊 JVM 停止鉤子。
	@Test
	void shouldReloadConfigurationWhenRestartingResources() {
		List<String> operations = new ArrayList<>();
		ServiceApplication application = new ServiceApplication(
			() -> {
				operations.add("configuration");

				return Optional.of(configuration());

			},
			configured -> {
				operations.add("tunnel");

				return configured;

			},
			(configured, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		application.start(new String[] {"--app.runtime.mode=service"});
		application.restart();

		assertThat(operations).containsExactly(
			"configuration",
			"hook",
			"tunnel",
			"spring",
			"stop",
			"configuration",
			"tunnel",
			"spring"
		);
		assertThat(application.running()).isTrue();
	}

	// 方法：重啟失敗時清理部分資源並保留再次重啟能力，不將 host 永久終止。
	@Test
	void shouldAllowRetryAfterRestartFailure() {
		List<String> operations = new ArrayList<>();
		int[] tunnelStarts = {0};
		ServiceApplication application = new ServiceApplication(
			() -> Optional.of(configuration()),
			configured -> {
				tunnelStarts[0]++;
				operations.add("tunnel-" + tunnelStarts[0]);

				if (tunnelStarts[0] == 2) throw new IllegalStateException("restart failed");

				return configured;

			},
			(configured, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		application.start(new String[0]);

		assertThatThrownBy(application::restart)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("restart failed");
		assertThat(application.running()).isFalse();

		application.restart();

		assertThat(application.running()).isTrue();
		assertThat(operations).containsExactly(
			"hook",
			"tunnel-1",
			"spring",
			"stop",
			"tunnel-2",
			"stop",
			"tunnel-3",
			"spring"
		);
	}

	// 方法：控制通道必須回報狀態、觸發重啟，並在 host 關閉時撤銷端點。
	@Test
	void shouldHandleAuthenticatedServiceControlCommands() {
		TestServiceControlHost controlHost = new TestServiceControlHost();
		List<String> operations = new ArrayList<>();
		ServiceApplication application = new ServiceApplication(
			() -> Optional.of(configuration()),
			configured -> configured,
			(configured, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			controlHost,
			new TestServiceInstanceResource()
		);

		application.start(new String[0]);

		assertThat(controlHost.request(ServiceControlCommand.STATUS)).isEqualTo(ServiceControlResponse.RUNNING);
		assertThat(controlHost.request(ServiceControlCommand.RESTART)).isEqualTo(ServiceControlResponse.RESTARTED);
		assertThat(operations).containsExactly("hook", "spring", "stop", "spring");

		application.shutdown();

		assertThat(controlHost.closed()).isTrue();
		assertThat(application.running()).isFalse();
	}

	// 方法：單一資源停止失敗時仍必須繼續釋放其餘 Tunnel 與 Spring。
	@Test
	void shouldContinueStoppingResourcesAfterOneFailure() {
		List<String> operations = new ArrayList<>();

		ServiceApplication.stopResources(
			() -> {
				operations.add("cloudflare");

				throw new IllegalStateException("cloudflare failed");

			},
			() -> operations.add("ngrok"),
			() -> operations.add("spring")
		);

		assertThat(operations).containsExactly("cloudflare", "ngrok", "spring");
	}

	// 方法：正常停止完成後必須解除緊急終止保護，避免延後誤關閉健康程序。
	@Test
	void shouldCancelEmergencyShutdownAfterResourcesStop() {
		List<String> operations = new ArrayList<>();
		ServiceApplication application = new ServiceApplication(
			() -> Optional.of(configuration()),
			configuration -> configuration,
			(configuration, arguments) -> operations.add("spring"),
			() -> operations.add("stop"),
			shutdownHook -> operations.add("hook"),
			() -> {
				operations.add("arm");

				return () -> operations.add("cancel");

			},
			new TestServiceControlHost(),
			new TestServiceInstanceResource()
		);

		application.start(new String[0]);
		application.shutdown();

		assertThat(operations).containsExactly("hook", "spring", "arm", "stop", "cancel");
	}

	// 方法：建立具有必要欄位的測試設定。
	private AppConfiguration configuration() {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")));
	}

	/**
	 * 保存 service host 註冊的控制命令處理器供生命週期測試呼叫。
	 */
	private static final class TestServiceControlHost implements ServiceControlHost {

		//#region 欄位

		private Function<ServiceControlCommand, ServiceControlResponse> commandHandler;
		private boolean closed;

		//#endregion

		//#region 方法

		// 方法：保存控制命令處理器並標記通道可使用。
		@Override
		public void start(Function<ServiceControlCommand, ServiceControlResponse> commandHandler) {
			this.commandHandler = commandHandler;
			this.closed = false;
		}

		// 方法：標記測試控制通道已撤銷。
		@Override
		public void close() {
			closed = true;
		}

		// 方法：將測試命令交給 service host 並取得固定結果。
		private ServiceControlResponse request(ServiceControlCommand command) {
			return commandHandler.apply(command);
		}

		// 方法：回傳控制通道是否已由 service host 關閉。
		private boolean closed() {
			return closed;
		}

		//#endregion
	}

	/**
	 * 模擬可取得且可重複安全釋放的背景 service 執行個體資格。
	 */
	private static final class TestServiceInstanceResource implements ServiceInstanceResource {

		// 方法：測試預設允許取得背景 service 執行資格。
		@Override
		public boolean acquire() {
			return true;
		}

		// 方法：測試釋放背景 service 執行資格時不需額外資源。
		@Override
		public void close() {
		}
	}
}
