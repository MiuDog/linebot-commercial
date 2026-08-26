package dev.miudog.linebotcommercial.desktop.control;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 讓桌面控制器沿用或啟動單一背景 service，並以有限次數等待就緒。
 */
public final class ServiceProcessSupervisor {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(ServiceProcessSupervisor.class);

	private final Function<ServiceControlCommand, ServiceControlResponse> commandSender;
	private final Runnable processLauncher;
	private final Consumer<Duration> pauseAction;

	//#endregion

	//#region 建構子

	// 方法：建立使用正式控制 client、背景 launcher 與系統等待的 supervisor。
	public ServiceProcessSupervisor(
		ServiceControlClient controlClient,
		Runnable processLauncher
	) {
		this(controlClient::request, processLauncher, ServiceProcessSupervisor::pause);
	}

	// 方法：建立可替換探測、啟動與等待操作的 supervisor 測試邊界。
	ServiceProcessSupervisor(
		Function<ServiceControlCommand, ServiceControlResponse> commandSender,
		Runnable processLauncher,
		Consumer<Duration> pauseAction
	) {
		this.commandSender = Objects.requireNonNull(commandSender, "Service 控制命令傳送器不可為 null");
		this.processLauncher = Objects.requireNonNull(processLauncher, "Service 程序啟動器不可為 null");
		this.pauseAction = Objects.requireNonNull(pauseAction, "Service 探測等待操作不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：沿用已運行 service，否則只啟動一次並依設定次數等待控制端點就緒。
	public boolean ensureRunning(
		int readinessAttempts,
		Duration readinessInterval
	) {
		if (readinessAttempts < 1) throw new IllegalArgumentException("Service 就緒探測次數至少為 1");

		Objects.requireNonNull(readinessInterval, "Service 就緒探測間隔不可為 null");
		if (readinessInterval.isNegative()) throw new IllegalArgumentException("Service 就緒探測間隔不可為負數");

		if (running()) return true;

		// 日誌：記錄背景 service 尚未運行並將啟動獨立 launcher，不輸出執行路徑或設定值。
		log.info("event=service_supervisor_launching");
		processLauncher.run();

		for (int attempt = 1; attempt <= readinessAttempts; attempt++) {
			pauseAction.accept(readinessInterval);

			if (running()) {
				// 日誌：記錄 service 已於有限探測內就緒，供啟動延遲診斷。
				log.info("event=service_supervisor_ready attempt={}", attempt);

				return true;
			}
		}

		// 日誌：記錄 service 未於設定次數內就緒，不自動形成無限重啟迴圈。
		log.error("event=service_supervisor_readiness_failed attempts={}", readinessAttempts);

		return false;
	}

	// 方法：透過受保護控制通道判斷背景 service 是否完整運行。
	private boolean running() {
		return commandSender.apply(ServiceControlCommand.STATUS) == ServiceControlResponse.RUNNING;
	}

	// 方法：使用目前執行緒等待下次探測，中斷時恢復旗標並終止啟動流程。
	private static void pause(Duration duration) {
		try {
			// 外部 JVM：以可注入間隔等待背景 service 發布受保護控制端點。
			Thread.sleep(duration);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("等待 Service 就緒時遭中斷", exception);
		}
	}

	//#endregion
}

