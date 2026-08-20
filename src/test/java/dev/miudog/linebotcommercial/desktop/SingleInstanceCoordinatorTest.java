package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證單一執行個體鎖、第二次開啟通知與結束後資源釋放。
 */
class SingleInstanceCoordinatorTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：第二個執行個體只通知第一個顯示視窗，不取得主程序資格。
	@Test
	void shouldNotifyThePrimaryInstanceInsteadOfAcquiringASecondLock() throws Exception {
		LinkedBlockingQueue<DesktopIpcCommand> commands = new LinkedBlockingQueue<>();

		try (SingleInstanceCoordinator primary = new SingleInstanceCoordinator(temporaryDirectory);
			SingleInstanceCoordinator secondary = new SingleInstanceCoordinator(temporaryDirectory)) {
			assertThat(primary.acquireOrNotify(DesktopIpcCommand.SHOW_WINDOW, commands::add))
				.isEqualTo(SingleInstanceResult.PRIMARY);
			assertThat(secondary.acquireOrNotify(DesktopIpcCommand.SHOW_WINDOW, command -> {
			}))
				.isEqualTo(SingleInstanceResult.NOTIFIED);
			assertThat(commands.poll(2, TimeUnit.SECONDS)).isEqualTo(DesktopIpcCommand.SHOW_WINDOW);
		}
	}

	// 方法：主程序結束後 lock、metadata 與 socket 都可由下一個執行個體重新取得。
	@Test
	void shouldReleaseAllResourcesAfterClosing() {
		SingleInstanceCoordinator first = new SingleInstanceCoordinator(temporaryDirectory);
		assertThat(first.acquireOrNotify(DesktopIpcCommand.SHOW_WINDOW, command -> {
		})).isEqualTo(SingleInstanceResult.PRIMARY);

		first.close();

		try (SingleInstanceCoordinator next = new SingleInstanceCoordinator(temporaryDirectory)) {
			assertThat(next.acquireOrNotify(DesktopIpcCommand.SHOW_WINDOW, command -> {
			})).isEqualTo(SingleInstanceResult.PRIMARY);
		}
	}
}
