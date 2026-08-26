package dev.miudog.linebotcommercial;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 驗證桌面控制器、Windows 服務與一般 server 的啟動模式解析。
 */
class ApplicationRuntimeModeTest {

	// 方法：未指定模式時維持既有 server 啟動行為。
	@Test
	void shouldDefaultToServerMode() {
		assertThat(ApplicationRuntimeMode.resolve(new String[0], null, null))
			.isEqualTo(ApplicationRuntimeMode.SERVER);
	}

	// 方法：jpackage 桌面屬性必須優先於一般 server 模式。
	@Test
	void shouldResolveDesktopModeFromSystemProperty() {
		assertThat(ApplicationRuntimeMode.resolve(new String[0], "true", null))
			.isEqualTo(ApplicationRuntimeMode.DESKTOP);
	}

	// 方法：服務 launcher 以獨立屬性啟動 headless Spring 服務。
	@Test
	void shouldResolveServiceModeFromArgumentOrProperty() {
		assertThat(ApplicationRuntimeMode.resolve(
			new String[] {"--app.runtime.mode=service"},
			null,
			null
		)).isEqualTo(ApplicationRuntimeMode.SERVICE);
		assertThat(ApplicationRuntimeMode.resolve(new String[0], null, "SERVICE"))
			.isEqualTo(ApplicationRuntimeMode.SERVICE);
	}

	// 方法：固定桌面 launcher 屬性不可被額外 service 參數覆蓋。
	@Test
	void shouldKeepDesktopLauncherIsolatedFromServiceArguments() {
		assertThat(ApplicationRuntimeMode.resolve(
			new String[] {"--app.runtime.mode=service"},
			"true",
			null
		)).isEqualTo(ApplicationRuntimeMode.DESKTOP);
	}
}
