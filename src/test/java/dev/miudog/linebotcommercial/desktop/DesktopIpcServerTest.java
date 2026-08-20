package dev.miudog.linebotcommercial.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 驗證桌面 IPC 的 loopback、nonce、命令格式與拒絕行為。
 */
class DesktopIpcServerTest {

	// 方法：正確 nonce 與命令能送達第一個執行個體。
	@Test
	void shouldDeliverAuthenticatedLoopbackCommand() throws Exception {
		LinkedBlockingQueue<DesktopIpcCommand> commands = new LinkedBlockingQueue<>();

		try (DesktopIpcServer server = new DesktopIpcServer("secure-nonce", commands::add)) {
			server.start();

			boolean accepted = DesktopIpcServer.send(
				server.port(),
				"secure-nonce",
				DesktopIpcCommand.SHOW_WINDOW,
				Duration.ofSeconds(2)
			);

			assertThat(accepted).isTrue();
			assertThat(commands.poll(2, TimeUnit.SECONDS)).isEqualTo(DesktopIpcCommand.SHOW_WINDOW);
		}
	}

	// 方法：錯誤 nonce 與格式錯誤資料都會被拒絕且不執行命令。
	@Test
	void shouldRejectWrongNonceAndMalformedPayload() throws Exception {
		LinkedBlockingQueue<DesktopIpcCommand> commands = new LinkedBlockingQueue<>();

		try (DesktopIpcServer server = new DesktopIpcServer("secure-nonce", commands::add)) {
			server.start();

			assertThat(DesktopIpcServer.send(
				server.port(),
				"wrong-nonce",
				DesktopIpcCommand.OPEN_SETTINGS,
				Duration.ofSeconds(2)
			)).isFalse();
			assertThat(DesktopIpcServer.sendRaw(
				server.port(),
				"malformed payload",
				Duration.ofSeconds(2)
			)).isFalse();
			assertThat(commands).isEmpty();
		}
	}
}
