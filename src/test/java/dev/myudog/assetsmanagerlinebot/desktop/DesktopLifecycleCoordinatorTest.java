package dev.myudog.assetsmanagerlinebot.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.myudog.assetsmanagerlinebot.desktop.config.AppConfiguration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面後端的啟動、停止、重啟與失敗狀態轉換。
 */
class DesktopLifecycleCoordinatorTest {

	// 方法：啟動與停止時依序發布合法狀態並操作後端一次。
	@Test
	void shouldPublishLegalStatesWhenStartingAndStopping() {
		RecordingBackend backend = new RecordingBackend();
		DesktopLifecycleCoordinator coordinator = coordinator(backend);
		List<DesktopStatus> statuses = new ArrayList<>();
		coordinator.addStatusListener(statuses::add);

		coordinator.start(configuration(), new String[] {"--test=true"});
		coordinator.stop();

		assertThat(statuses).containsExactly(
			DesktopStatus.STARTING,
			DesktopStatus.RUNNING,
			DesktopStatus.STOPPING,
			DesktopStatus.STOPPED
		);
		assertThat(backend.operations).containsExactly("start", "stop");
	}

	// 方法：重新啟動時必須先停止舊 Context 再建立新 Context。
	@Test
	void shouldReleaseThePreviousBackendBeforeRestarting() {
		RecordingBackend backend = new RecordingBackend();
		DesktopLifecycleCoordinator coordinator = coordinator(backend);

		coordinator.start(configuration(), new String[0]);
		coordinator.restart(configuration(), new String[0]);

		assertThat(backend.operations).containsExactly("start", "stop", "start");
		assertThat(coordinator.status()).isEqualTo(DesktopStatus.RUNNING);
	}

	// 方法：後端啟動失敗時進入 FAILED 並保留原始例外供上層處理。
	@Test
	void shouldEnterFailedStateWhenBackendCannotStart() {
		RecordingBackend backend = new RecordingBackend();
		backend.failOnStart = true;
		DesktopLifecycleCoordinator coordinator = coordinator(backend);

		assertThatThrownBy(() -> coordinator.start(configuration(), new String[0]))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("backend failed");
		assertThat(coordinator.status()).isEqualTo(DesktopStatus.FAILED);
	}

	// 方法：建立使用測試後端及固定 Spring 映射器的生命週期協調器。
	private DesktopLifecycleCoordinator coordinator(DesktopBackend backend) {
		return new DesktopLifecycleCoordinator(backend, configuration -> Map.of("server.port", "8088"));
	}

	// 方法：建立具有必要欄位的測試設定。
	private AppConfiguration configuration() {
		return AppConfiguration.defaults(Path.of("C:/local"));
	}

	/**
	 * 記錄後端操作順序並可模擬啟動失敗。
	 */
	private static final class RecordingBackend implements DesktopBackend {

		private final List<String> operations = new ArrayList<>();
		private boolean failOnStart;

		// 方法：記錄後端啟動並依測試條件拋出錯誤。
		@Override
		public void start(
			Map<String, Object> properties,
			String[] arguments
		) {
			operations.add("start");

			if (failOnStart) throw new IllegalStateException("backend failed");
		}

		// 方法：記錄後端停止順序。
		@Override
		public void stop() {
			operations.add("stop");
		}
	}
}
