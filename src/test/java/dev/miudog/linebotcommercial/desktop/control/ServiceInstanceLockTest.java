package dev.miudog.linebotcommercial.desktop.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證背景 service 在建立 Tunnel 前只能由一個程序取得產品鎖。
 */
class ServiceInstanceLockTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：同一產品鎖同時只允許一個 service 持有，釋放後可由下一個程序取得。
	@Test
	void shouldAllowOnlyOneServiceInstanceAtATime() {
		ServiceInstanceLock first = new ServiceInstanceLock(temporaryDirectory);
		ServiceInstanceLock second = new ServiceInstanceLock(temporaryDirectory);

		assertThat(first.acquire()).isTrue();
		assertThat(second.acquire()).isFalse();

		first.close();

		assertThat(second.acquire()).isTrue();
		second.close();
	}
}

