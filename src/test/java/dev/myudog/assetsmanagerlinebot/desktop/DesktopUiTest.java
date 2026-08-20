package dev.myudog.assetsmanagerlinebot.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

/**
 * 驗證未建立 UI 的桌面流程不會啟動 AWT EDT 而留住 JVM。
 */
class DesktopUiTest {

	private static final String EDT_THREAD_PREFIX = "AWT-EventQueue";

	// 方法：--shutdown 等不顯示 UI 的流程關閉時不可啟動非 daemon 的 AWT EDT。
	@Test
	void shouldNotStartEventDispatchThreadWhenClosingBeforeStart() {
		// 同一個測試 JVM 若已被其他 Swing 測試啟動 EDT，本檢查無法再分辨來源。
		assumeFalse(eventDispatchThreadExists(), "AWT EDT 已由其他測試啟動");

		DesktopUi desktopUi = new DesktopUi(new DesktopWindowModel(8088));

		assertThatCode(desktopUi::close).doesNotThrowAnyException();
		assertThat(eventDispatchThreadExists()).isFalse();
	}

	// 方法：掃描目前所有執行緒，判斷 AWT EDT 是否已建立。
	private static boolean eventDispatchThreadExists() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.anyMatch(thread -> thread.getName().startsWith(EDT_THREAD_PREFIX));
	}
}
