package dev.miudog.linebotcommercial.desktop.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面只在 service 不可用時啟動背景 launcher，並以有限探測等待就緒。
 */
class ServiceProcessSupervisorTest {

	// 方法：service 已運行時直接沿用，不重複啟動第二個 JVM。
	@Test
	void shouldReuseRunningServiceWithoutLaunchingAnotherProcess() {
		AtomicInteger launches = new AtomicInteger();
		ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(
			command -> ServiceControlResponse.RUNNING,
			launches::incrementAndGet,
			duration -> {
			}
		);

		assertThat(supervisor.ensureRunning(3, Duration.ofMillis(1))).isTrue();
		assertThat(launches).hasValue(0);
	}

	// 方法：service 不可用時只啟動一次，並等待受保護控制端點回報運行中。
	@Test
	void shouldLaunchOnceAndWaitForServiceReadiness() {
		Queue<ServiceControlResponse> responses = new ArrayDeque<>();
		responses.add(ServiceControlResponse.UNAVAILABLE);
		responses.add(ServiceControlResponse.UNAVAILABLE);
		responses.add(ServiceControlResponse.RUNNING);
		AtomicInteger launches = new AtomicInteger();
		AtomicInteger pauses = new AtomicInteger();
		ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(
			command -> responses.remove(),
			launches::incrementAndGet,
			duration -> pauses.incrementAndGet()
		);

		assertThat(supervisor.ensureRunning(3, Duration.ofMillis(1))).isTrue();
		assertThat(launches).hasValue(1);
		assertThat(pauses).hasValue(2);
	}

	// 方法：超過有限探測次數仍未就緒時回傳失敗，不建立無限重試迴圈。
	@Test
	void shouldStopPollingAfterConfiguredAttempts() {
		AtomicInteger probes = new AtomicInteger();
		ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(
			command -> {
				probes.incrementAndGet();

				return ServiceControlResponse.UNAVAILABLE;

			},
			() -> {
			},
			duration -> {
			}
		);

		assertThat(supervisor.ensureRunning(2, Duration.ofMillis(1))).isFalse();
		assertThat(probes).hasValue(3);
	}
}

