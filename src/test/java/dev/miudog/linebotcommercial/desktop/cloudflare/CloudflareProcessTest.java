package dev.miudog.linebotcommercial.desktop.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 cloudflared agent 路徑、獨立參數、Token environment 與自有程序停止。
 */
class CloudflareProcessTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：只接受存在的絕對 exe 檔案，不接受目錄或非 exe。
	@Test
	void shouldRejectInvalidAgentPaths() throws Exception {
		Path directory = temporaryDirectory.resolve("cloudflared.exe");
		Files.createDirectories(directory);

		assertThatThrownBy(() -> CloudflareProcess.validateAgent(directory))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CloudflareProcess.validateAgent(Path.of("cloudflared.exe")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// 方法：Tunnel Token 只放入 child environment，命令列不包含 Token 原文。
	@Test
	void shouldKeepTunnelTokenOutOfCommandArguments() throws Exception {
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		Process child = mock(Process.class);
		ProcessBuilder[] capturedBuilder = new ProcessBuilder[1];
		CloudflareProcess process = new CloudflareProcess(builder -> {
			capturedBuilder[0] = builder;

			return child;

		});

		process.start(agent, "ey-test-sensitive-token");

		assertThat(capturedBuilder[0].command())
			.containsExactly(
				agent.toString(),
				"tunnel",
				"run"
			)
			.doesNotContain("ey-test-sensitive-token");
		assertThat(capturedBuilder[0].environment()).containsEntry("TUNNEL_TOKEN", "ey-test-sensitive-token");
	}

	// 方法：停止時只終止目前物件建立的 child process，逾時後才強制停止。
	@Test
	void shouldStopOnlyItsOwnedChildProcess() throws Exception {
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		Process child = mock(Process.class);
		when(child.isAlive()).thenReturn(true);
		CloudflareProcess process = new CloudflareProcess(builder -> child);

		process.start(agent, "test-token");
		process.stop(Duration.ofMillis(50));

		verify(child).destroy();
		assertThat(process.status()).isEqualTo(CloudflareStatus.STOPPED);
	}
}
