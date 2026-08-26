package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.ConfigurationWizardResult;
import dev.miudog.linebotcommercial.desktop.control.ServiceControlResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面 bootstrap 的模式判斷、首次設定與啟動順序。
 */
class DesktopApplicationTest {

	// 方法：只有明確指定 desktop enabled 才切換既有 server 啟動模式。
	@Test
	void shouldRequireExplicitDesktopModeArgument() {
		assertThat(DesktopApplication.desktopModeRequested(new String[0])).isFalse();
		assertThat(DesktopApplication.desktopModeRequested(
			new String[] {"--app.desktop.enabled=true"}
		)).isTrue();
	}

	// 方法：驗證 jpackage 的 JVM property 在額外命令列取代預設 arguments 時仍固定啟用桌面模式。
	@Test
	void shouldUseDesktopSystemPropertyWhenLauncherArgumentsAreReplaced() {
		assertThat(DesktopApplication.desktopModeRequested(new String[] {"--shutdown"}, "true")).isTrue();
		assertThat(DesktopApplication.desktopModeRequested(new String[] {"--configure"}, "TRUE")).isTrue();
		assertThat(DesktopApplication.desktopModeRequested(new String[0], "false")).isFalse();
	}

	// 方法：首次設定取消時不啟動 Spring 後端。
	@Test
	void shouldNotStartBackendWhenFirstConfigurationIsCancelled() {
		AtomicBoolean started = new AtomicBoolean();
		DesktopApplication application = new DesktopApplication(
			Optional::empty,
			configuration -> ConfigurationWizardResult.cancelled(configuration),
			configuration(),
			(coordinatorConfiguration, arguments) -> started.set(true)
		);

		boolean launched = application.start(new String[0]);

		assertThat(launched).isFalse();
		assertThat(started).isFalse();
	}

	// 方法：已有設定時略過首次精靈並在載入後啟動後端。
	@Test
	void shouldLoadConfigurationBeforeStartingBackend() {
		AppConfiguration configured = configuration();
		AppConfiguration[] startedWith = new AppConfiguration[1];
		DesktopApplication application = new DesktopApplication(
			() -> Optional.of(configured),
			configuration -> {
				throw new AssertionError("不應顯示首次設定");

			},
			configuration(),
			(coordinatorConfiguration, arguments) -> startedWith[0] = coordinatorConfiguration
		);

		boolean launched = application.start(new String[0]);

		assertThat(launched).isTrue();
		assertThat(startedWith[0]).isSameAs(configured);
	}

	// 方法：第二個執行個體已通知主程序時，不讀設定也不啟動後端。
	@Test
	void shouldStopBeforeConfigurationWhenPrimaryWasNotified() {
		AtomicBoolean loaded = new AtomicBoolean();
		AtomicBoolean started = new AtomicBoolean();
		DesktopApplication application = new DesktopApplication(
			command -> SingleInstanceResult.NOTIFIED,
			() -> {
			},
			() -> {
				loaded.set(true);

				return Optional.of(configuration());

			},
			configuration -> ConfigurationWizardResult.cancelled(configuration),
			configuration(),
			(coordinatorConfiguration, arguments) -> started.set(true),
			() -> {
			},
			() -> {
			}
		);

		assertThat(application.start(new String[0])).isFalse();
		assertThat(loaded).isFalse();
		assertThat(started).isFalse();
	}

	// 方法：主視窗與系統匣必須在讀取設定之前建立，否則首次設定精靈沒有工作列按鈕時 App 形同隱形。
	@Test
	void shouldBootstrapUiBeforeLoadingConfiguration() {
		List<String> order = new ArrayList<>();
		DesktopApplication application = new DesktopApplication(
			command -> SingleInstanceResult.PRIMARY,
			() -> order.add("ui"),
			() -> {
				order.add("configuration");

				return Optional.of(configuration());

			},
			configuration -> ConfigurationWizardResult.cancelled(configuration),
			configuration(),
			(coordinatorConfiguration, arguments) -> order.add("backend"),
			() -> {
			},
			() -> {
			}
		);

		assertThat(application.start(new String[0])).isTrue();
		assertThat(order).containsExactly("ui", "configuration", "backend");
	}

	// 方法：首次設定取消時必須走完整停止流程，否則已建立的系統匣與 EDT 會留住程序。
	@Test
	void shouldStopUiWhenFirstConfigurationIsCancelled() {
		AtomicBoolean stopped = new AtomicBoolean();
		AtomicBoolean closed = new AtomicBoolean();
		DesktopApplication application = new DesktopApplication(
			command -> SingleInstanceResult.PRIMARY,
			() -> {
			},
			Optional::empty,
			configuration -> ConfigurationWizardResult.cancelled(configuration),
			configuration(),
			(coordinatorConfiguration, arguments) -> {
				throw new AssertionError("取消設定不可啟動後端");

			},
			() -> stopped.set(true),
			() -> closed.set(true)
		);

		assertThat(application.start(new String[0])).isFalse();
		assertThat(stopped).isTrue();
		assertThat(closed).isTrue();
	}

	// 方法：驗證無主執行個體時的關閉命令只釋放鎖定資源，不啟動設定或後端。
	@Test
	void shouldReleasePrimaryResourceWithoutStartingForShutdownCommand() {
		AtomicBoolean loaded = new AtomicBoolean();
		AtomicBoolean stopped = new AtomicBoolean();
		AtomicBoolean closed = new AtomicBoolean();
		DesktopApplication application = new DesktopApplication(
			command -> {
				assertThat(command).isEqualTo(DesktopIpcCommand.SHUTDOWN);

				return SingleInstanceResult.PRIMARY;

			},
			() -> {
			},
			() -> {
				loaded.set(true);

				return Optional.of(configuration());

			},
			configuration -> ConfigurationWizardResult.cancelled(configuration),
			configuration(),
			(coordinatorConfiguration, arguments) -> {
				throw new AssertionError("關閉命令不可啟動後端");

			},
			() -> stopped.set(true),
			() -> closed.set(true)
		);

		assertThat(application.start(new String[] {"--shutdown"})).isFalse();
		assertThat(loaded).isFalse();
		assertThat(stopped).isTrue();
		assertThat(closed).isTrue();
	}

	// 方法：將背景 service 固定回應轉換成使用者可見的桌面狀態。
	@Test
	void shouldMapServiceControlResponseToDesktopStatus() throws Exception {
		DesktopWindowModel windowModel = new DesktopWindowModel(8088);

		DesktopApplication.applyServiceResponse(windowModel, ServiceControlResponse.RUNNING);

		// 外部 Swing：等待先前排入 EDT 的狀態更新完成後再驗證畫面模型。
		SwingUtilities.invokeAndWait(() -> {
		});
		assertThat(windowModel.snapshot().status()).isEqualTo(DesktopStatus.RUNNING);

		DesktopApplication.applyServiceResponse(windowModel, ServiceControlResponse.RESTARTED);

		// 外部 Swing：等待重新啟動狀態完成發布。
		SwingUtilities.invokeAndWait(() -> {
		});
		assertThat(windowModel.snapshot().status()).isEqualTo(DesktopStatus.RUNNING);

		DesktopApplication.applyServiceResponse(windowModel, ServiceControlResponse.UNAVAILABLE);

		// 外部 Swing：等待不可用狀態完成發布。
		SwingUtilities.invokeAndWait(() -> {
		});
		assertThat(windowModel.snapshot().status()).isEqualTo(DesktopStatus.FAILED);
	}

	// 方法：建立桌面 bootstrap 測試用預設設定。
	private AppConfiguration configuration() {
		return AppConfiguration.defaults(Path.of(System.getProperty("java.io.tmpdir")));
	}
}
