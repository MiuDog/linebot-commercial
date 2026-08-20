package dev.miudog.linebotcommercial.desktop.ngrok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 ngrok agent 路徑、獨立參數、Token environment 與自有程序停止。
 */
class NgrokProcessTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：只接受存在的絕對 exe 檔案，不接受附加命令或目錄。
	@Test
	void shouldRejectInvalidAgentPaths() throws Exception {
		Path directory = temporaryDirectory.resolve("ngrok.exe");
		Files.createDirectories(directory);

		assertThatThrownBy(() -> NgrokProcess.validateAgent(directory))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NgrokProcess.validateAgent(Path.of("ngrok.exe --config evil")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// 方法：Authtoken 只放入 child environment，命令列不包含秘密原文。
	@Test
	void shouldKeepAuthtokenOutOfCommandArguments() throws Exception {
		Path agent = temporaryDirectory.resolve("ngrok.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		Process child = mock(Process.class);
		ProcessBuilder[] capturedBuilder = new ProcessBuilder[1];
		NgrokProcess process = new NgrokProcess(builder -> {
			capturedBuilder[0] = builder;

			return child;

		});

		process.start(agent, "sensitive-token", 8088);

		assertThat(capturedBuilder[0].command())
			.containsExactly(
				agent.toString(),
				"http",
				"http://127.0.0.1:8088",
				"--log=stdout",
				"--log-format=json"
			)
			.doesNotContain("sensitive-token");
		assertThat(capturedBuilder[0].environment()).containsEntry("NGROK_AUTHTOKEN", "sensitive-token");
	}

	// 方法：停止時只終止目前物件建立的 child process，逾時後才強制停止。
	@Test
	void shouldStopOnlyItsOwnedChildProcess() throws Exception {
		Path agent = temporaryDirectory.resolve("ngrok.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		Process child = mock(Process.class);
		when(child.isAlive()).thenReturn(true);
		when(child.waitFor(Duration.ofMillis(10))).thenReturn(false);
		NgrokProcess process = new NgrokProcess(builder -> child);
		process.start(agent, "token", 8088);

		process.stop(Duration.ofMillis(10));

		verify(child).destroy();
		verify(child).destroyForcibly();
		assertThat(process.status()).isEqualTo(NgrokStatus.STOPPED);
	}
}
