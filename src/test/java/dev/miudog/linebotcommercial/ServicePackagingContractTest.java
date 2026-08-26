package dev.miudog.linebotcommercial;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 驗證 Windows app image 同時提供桌面控制器與內部背景服務 launcher。
 */
class ServicePackagingContractTest {

	// 方法：服務 launcher 必須使用 headless service mode，且不得固定啟用桌面模式。
	@Test
	void shouldPackageAnIsolatedHeadlessServiceLauncher() throws IOException {
		String launcher = read("packaging/windows/service-launcher.properties");
		String packageScript = read("scripts/package-windows-app.ps1");

		assertThat(launcher)
			.contains("arguments=--app.runtime.mode=service")
			.contains("-Djava.awt.headless=true")
			.doesNotContain("app.desktop.enabled=true");
		assertThat(packageScript)
			.contains("--add-launcher")
			.contains("LinebotCommercialService")
			.contains("service-launcher.properties");
	}

	// 方法：從專案根目錄讀取封裝契約檔案。
	private String read(String relativePath) throws IOException {
		// 外部檔案系統：只讀取版本控制內的封裝設定供契約測試使用。
		return Files.readString(Path.of(relativePath));
	}
}
