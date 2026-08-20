package dev.myudog.assetsmanagerlinebot.desktop;

import dev.myudog.assetsmanagerlinebot.desktop.log.DesktopLogBuffer;
import dev.myudog.assetsmanagerlinebot.desktop.log.DesktopLogPanel;
import dev.myudog.assetsmanagerlinebot.desktop.log.LogTailService;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * 在 Swing EDT 建立、顯示與關閉主視窗及系統匣資源。
 */
public final class DesktopUi {

	//#region 欄位

	private final DesktopWindowModel model;
	private final DesktopLogBuffer logBuffer;
	private final AtomicReference<Path> logDirectory;
	private DesktopWindow window;
	private DesktopTrayController trayController;
	private LogTailService logTailService;

	//#endregion

	//#region 建構子

	// 方法：建立尚未配置 Swing 元件的桌面 UI 執行環境。
	public DesktopUi(DesktopWindowModel model) {
		this.model = Objects.requireNonNull(model, "桌面視窗模型不可為 null");
		this.logBuffer = new DesktopLogBuffer(2000);
		this.logDirectory = new AtomicReference<>();
	}

	//#endregion

	//#region 方法

	// 方法：同步等待 EDT 建立主視窗與系統匣，完成後顯示主視窗。
	public void start(
		DesktopActions actions,
		Path logDirectory
	) {
		this.logDirectory.set(Objects.requireNonNull(logDirectory, "Log 目錄不可為 null"));

		runOnEdtAndWait(() -> {
			if (window != null) return;

			DesktopLogPanel logPanel = new DesktopLogPanel(logBuffer, this.logDirectory::get);
			window = new DesktopWindow(model, actions, logPanel.content());
			trayController = new DesktopTrayController(window, new AwtTrayAccess(), actions);
			window.setCloseHandler(trayController::windowClosing);
			trayController.install();
			model.addListener(trayController::updateStatus);
			trayController.updateStatus(model.snapshot());
			window.showWindow();
		});
		followLogDirectory(logDirectory);
	}

	// 方法：設定變更後停止舊追蹤器並追蹤新資料根目錄的 active Log。
	public synchronized void followLogDirectory(Path logDirectory) {
		this.logDirectory.set(Objects.requireNonNull(logDirectory, "Log 目錄不可為 null"));

		if (logTailService != null) logTailService.close();

		logTailService = new LogTailService(logDirectory.resolve("application.json"), logBuffer);
		logTailService.start(Duration.ofMillis(500));
	}

	// 方法：由系統匣或第二次開啟命令把既有視窗移至前景。
	public void show() {
		SwingUtilities.invokeLater(() -> {
			if (trayController != null) trayController.showWindow();
		});
	}

	// 方法：同步移除系統匣並釋放主視窗資源。
	public void close() {
		if (logTailService != null) {
			logTailService.close();
			logTailService = null;
		}

		// 從未建立視窗與系統匣時不可觸碰 Swing：invokeAndWait 會啟動非 daemon 的 AWT EDT，
		// 使 --shutdown 這類不顯示 UI 的流程在 main 返回後仍留住 JVM 而成為背景殭屍程序。
		if (window == null && trayController == null) return;

		runOnEdtAndWait(() -> {
			if (trayController != null) trayController.close();

			if (window != null) window.closeWindow();

			trayController = null;
			window = null;
		});
	}

	// 方法：在 EDT 或同步切換至 EDT 執行必須完成的 UI 操作。
	private void runOnEdtAndWait(Runnable operation) {
		if (SwingUtilities.isEventDispatchThread()) {
			operation.run();
			return;
		}

		AtomicReference<RuntimeException> failure = new AtomicReference<>();

		try {
			// 外部函式：同步等待 Swing EDT 完成主視窗與系統匣資源操作。
			SwingUtilities.invokeAndWait(() -> {
				try {
					operation.run();
				}
				catch (RuntimeException exception) {
					failure.set(exception);
				}
			});
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("桌面 UI 操作被中斷", exception);
		}
		catch (InvocationTargetException exception) {
			throw new IllegalStateException("桌面 UI 操作失敗", exception.getCause());
		}

		if (failure.get() != null) throw failure.get();
	}

	//#endregion
}
