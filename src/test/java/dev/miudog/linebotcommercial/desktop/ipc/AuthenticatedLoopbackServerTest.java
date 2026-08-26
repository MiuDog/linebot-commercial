package dev.miudog.linebotcommercial.desktop.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 驗證共用 loopback 控制通道的認證、白名單命令與固定回應。
 */
class AuthenticatedLoopbackServerTest {

	// 方法：正確 nonce 與白名單命令會取得命令處理器的固定列舉回應。
	@Test
	void shouldReturnResponseForAuthenticatedCommand() throws Exception {
		LinkedBlockingQueue<TestCommand> commands = new LinkedBlockingQueue<>();

		try (AuthenticatedLoopbackServer<TestCommand, TestResponse> server = server(commands)) {
			server.start();

			TestResponse response = AuthenticatedLoopbackServer.request(
				server.port(),
				"secure-nonce",
				TestCommand.STATUS,
				TestResponse.class,
				Duration.ofSeconds(2)
			);

			assertThat(response).isEqualTo(TestResponse.RUNNING);
			assertThat(commands.poll(2, TimeUnit.SECONDS)).isEqualTo(TestCommand.STATUS);
		}
	}

	// 方法：錯誤 nonce、未知命令與過長 payload 都必須拒絕且不執行處理器。
	@Test
	void shouldRejectUnauthenticatedAndMalformedRequests() {
		LinkedBlockingQueue<TestCommand> commands = new LinkedBlockingQueue<>();

		try (AuthenticatedLoopbackServer<TestCommand, TestResponse> server = server(commands)) {
			server.start();

			assertThat(AuthenticatedLoopbackServer.request(
				server.port(),
				"wrong-nonce",
				TestCommand.RESTART,
				TestResponse.class,
				Duration.ofSeconds(2)
			)).isEqualTo(TestResponse.REJECTED);
			assertThat(AuthenticatedLoopbackServer.requestRaw(
				server.port(),
				"secure-nonce\tUNKNOWN",
				TestResponse.class,
				Duration.ofSeconds(2)
			)).isEqualTo(TestResponse.REJECTED);
			assertThat(AuthenticatedLoopbackServer.requestRaw(
				server.port(),
				"x".repeat(4097),
				TestResponse.class,
				Duration.ofSeconds(2)
			)).isEqualTo(TestResponse.REJECTED);
			assertThat(commands).isEmpty();
		}
	}

	// 方法：建立將接收命令加入佇列並回報運行中的測試伺服器。
	private AuthenticatedLoopbackServer<TestCommand, TestResponse> server(
		LinkedBlockingQueue<TestCommand> commands
	) {
		return new AuthenticatedLoopbackServer<>(
			"test-control",
			"secure-nonce",
			TestCommand.class,
			TestResponse.REJECTED,
			command -> {
				commands.add(command);

				return TestResponse.RUNNING;

			}
		);
	}

	/**
	 * 定義測試用有限命令。
	 */
	private enum TestCommand {
		STATUS,
		RESTART
	}

	/**
	 * 定義測試用固定回應。
	 */
	private enum TestResponse {
		RUNNING,
		REJECTED
	}
}
