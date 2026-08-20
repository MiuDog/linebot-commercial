package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以明確狀態機協調桌面殼層與 Spring 後端的啟停順序。
 */
public final class DesktopLifecycleCoordinator {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(DesktopLifecycleCoordinator.class);

	private final DesktopBackend backend;
	private final Function<AppConfiguration, Map<String, Object>> propertyMapper;
	private final List<Consumer<DesktopStatus>> statusListeners;
	private volatile DesktopStatus status;

	//#endregion

	//#region 建構子

	// 方法：建立指定後端與設定映射器的桌面生命週期協調器。
	public DesktopLifecycleCoordinator(
		DesktopBackend backend,
		Function<AppConfiguration, Map<String, Object>> propertyMapper
	) {
		this.backend = Objects.requireNonNull(backend, "桌面後端不可為 null");
		this.propertyMapper = Objects.requireNonNull(propertyMapper, "Spring 屬性映射器不可為 null");
		this.statusListeners = new CopyOnWriteArrayList<>();
		this.status = DesktopStatus.STOPPED;
	}

	//#endregion

	//#region 方法

	// 方法：在設定完成後啟動後端，成功回傳時代表 Spring 已經就緒。
	public synchronized void start(
		AppConfiguration configuration,
		String[] arguments
	) {
		if (status != DesktopStatus.STOPPED && status != DesktopStatus.FAILED) {
			throw new IllegalStateException("目前狀態不可啟動桌面後端：" + status);
		}

		transitionTo(DesktopStatus.STARTING);

		try {
			backend.start(propertyMapper.apply(configuration), arguments.clone());
			transitionTo(DesktopStatus.RUNNING);
		}
		catch (RuntimeException exception) {
			stopAfterFailedStart();
			transitionTo(DesktopStatus.FAILED);

			throw exception;
		}
	}

	// 方法：停止後端；重複停止時不重複關閉資源。
	public synchronized void stop() {
		if (status == DesktopStatus.STOPPED) return;

		transitionTo(DesktopStatus.STOPPING);

		try {
			backend.stop();
			transitionTo(DesktopStatus.STOPPED);
		}
		catch (RuntimeException exception) {
			transitionTo(DesktopStatus.FAILED);

			throw exception;
		}
	}

	// 方法：完整停止舊後端後，以新設定建立新的 Spring Context。
	public synchronized void restart(
		AppConfiguration configuration,
		String[] arguments
	) {
		stop();
		start(configuration, arguments);
	}

	// 方法：取得目前桌面後端狀態。
	public DesktopStatus status() {
		return status;
	}

	// 方法：加入狀態監聽器供視窗與系統匣同步顯示。
	public void addStatusListener(Consumer<DesktopStatus> listener) {
		statusListeners.add(Objects.requireNonNull(listener, "狀態監聽器不可為 null"));
	}

	// 方法：啟動中途失敗時盡力關閉可能已建立的部分資源。
	private void stopAfterFailedStart() {
		try {
			backend.stop();
		}
		catch (RuntimeException cleanupException) {
			// 日誌：記錄失敗啟動後的清理問題，不覆蓋真正的啟動例外。
			log.error("event=desktop_backend_failed_start_cleanup_failed errorType={}",
				cleanupException.getClass().getSimpleName()
			);
		}
	}

	// 方法：更新狀態、記錄流程事件並通知所有桌面顯示元件。
	private void transitionTo(DesktopStatus nextStatus) {
		DesktopStatus previousStatus = status;
		status = nextStatus;

		// 日誌：記錄不含機密的桌面後端狀態轉換供整體流程追蹤。
		log.info("event=desktop_status_changed previousStatus={} currentStatus={}", previousStatus, nextStatus);

		for (Consumer<DesktopStatus> listener : statusListeners) {
			listener.accept(nextStatus);
		}
	}

	//#endregion
}
