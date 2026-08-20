package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * 驗證系統匣可用與不可用時的關閉視窗安全行為。
 */
class DesktopTrayControllerTest {

	// 方法：系統匣安裝成功時關閉視窗只隱藏並保持後端執行。
	@Test
	void shouldHideWindowWhenTrayIsAvailable() throws Exception {
		RecordingWindow window = new RecordingWindow();
		RecordingTray tray = new RecordingTray(true);
		DesktopTrayController controller = controller(window, tray);

		onEdt(() -> {
			assertThat(controller.install()).isTrue();
			controller.windowClosing();
		});

		assertThat(window.hidden).isTrue();
		assertThat(window.shown).isFalse();
	}

	// 方法：系統匣不可用時拒絕隱藏並保持主視窗可操作。
	@Test
	void shouldKeepWindowVisibleWhenTrayIsUnavailable() throws Exception {
		RecordingWindow window = new RecordingWindow();
		RecordingTray tray = new RecordingTray(false);
		DesktopTrayController controller = controller(window, tray);

		onEdt(() -> {
			assertThat(controller.install()).isFalse();
			controller.windowClosing();
		});

		assertThat(window.hidden).isFalse();
		assertThat(window.shown).isTrue();
	}

	// 方法：建立具備四項系統匣操作的測試控制器。
	private DesktopTrayController controller(
		DesktopWindowHandle window,
		DesktopTrayAccess tray
	) {
		return new DesktopTrayController(
			window,
			tray,
			new DesktopActions(
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				}
			)
		);
	}

	// 方法：在 Swing EDT 執行系統匣控制測試。
	private void onEdt(Runnable operation) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(operation);
	}

	/**
	 * 記錄視窗顯示與隱藏操作。
	 */
	private static final class RecordingWindow implements DesktopWindowHandle {

		private boolean shown;
		private boolean hidden;

		// 方法：記錄視窗已顯示。
		@Override
		public void showWindow() {
			shown = true;
			hidden = false;
		}

		// 方法：記錄視窗已隱藏。
		@Override
		public void hideWindow() {
			hidden = true;
			shown = false;
		}
	}

	/**
	 * 模擬可用或不可用的作業系統匣。
	 */
	private static final class RecordingTray implements DesktopTrayAccess {

		private final boolean available;

		// 方法：建立指定可用狀態的測試系統匣。
		private RecordingTray(boolean available) {
			this.available = available;
		}

		// 方法：回傳測試指定的系統匣支援狀態。
		@Override
		public boolean install(DesktopActions actions) {
			return available;
		}

		// 方法：測試替身不需更新實際系統匣文字。
		@Override
		public void updateStatus(DesktopWindowSnapshot snapshot) {
		}

		// 方法：測試替身不需移除實際系統匣圖示。
		@Override
		public void remove() {
		}
	}
}
