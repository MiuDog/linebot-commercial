package dev.miudog.linebotcommercial.desktop.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.miudog.linebotcommercial.desktop.config.AppConfiguration;
import dev.miudog.linebotcommercial.desktop.config.AppConfigurationField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 CloudflareConnector 在啟用與未啟用時的行為與錯誤處理。
 */
class CloudflareConnectorTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：未啟用 Cloudflare 時直接回傳原始設定且不啟動程序。
	@Test
	void shouldPassThroughWhenCloudflareDisabled() {
		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		CloudflareConnector connector = new CloudflareConnector(process, duration -> {});
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "false")
			.withValue(AppConfigurationField.PUBLIC_BASE_URL, "https://my-domain.example.com");

		CloudflareConnection connection = connector.start(configuration, Duration.ofSeconds(5));

		assertThat(connection.enabled()).isFalse();
		assertThat(connection.publicUrl()).isEqualTo("https://my-domain.example.com");
	}

	// 方法：啟用且設定有效時啟動 child process 並維持公開網址。
	@Test
	void shouldStartProcessWhenCloudflareEnabled() throws Exception {
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});

		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		when(process.status()).thenReturn(CloudflareStatus.RUNNING);

		CloudflareConnector connector = new CloudflareConnector(process, duration -> {});
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "true")
			.withValue(AppConfigurationField.CLOUDFLARE_AGENT_PATH, agent.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN, "ey-token")
			.withValue(AppConfigurationField.PUBLIC_BASE_URL, "https://my-domain.example.com");

		CloudflareConnection connection = connector.start(configuration, Duration.ofSeconds(5));

		assertThat(connection.enabled()).isTrue();
		assertThat(connection.publicUrl()).isEqualTo("https://my-domain.example.com");
		verify(process).start(agent, "ey-token");
	}

	// 方法：啟用但缺少 Token 時應拋出例外。
	@Test
	void shouldRejectMissingTokenWhenEnabled() throws Exception {
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});

		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		CloudflareConnector connector = new CloudflareConnector(process, duration -> {});
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "true")
			.withValue(AppConfigurationField.CLOUDFLARE_AGENT_PATH, agent.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN, "");

		assertThatThrownBy(() -> connector.start(configuration, Duration.ofSeconds(5)))
			.isInstanceOf(CloudflareConnectorException.class)
			.hasMessageContaining("Token");
	}

	// 方法：stop 時停止 child process。
	@Test
	void shouldStopProcessOnConnectorStop() {
		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		CloudflareConnector connector = new CloudflareConnector(process, duration -> {});

		connector.stop();

		verify(process).stop(Duration.ofSeconds(3));
	}
}
