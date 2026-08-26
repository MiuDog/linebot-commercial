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
import java.util.UUID;
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
		CloudflareConnector connector = new CloudflareConnector(process);
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
		UUID tunnelId = UUID.fromString("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});

		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		when(process.awaitReady(Duration.ofSeconds(5))).thenReturn(true);
		when(process.identity()).thenReturn(new CloudflareAgentIdentity(
			tunnelId.toString(),
			"58cc3df9-a8f2-41e7-831a-8e699240eb25",
			"TEST-PC"
		));

		CloudflareConnector connector = new CloudflareConnector(process);
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "true")
			.withValue(AppConfigurationField.CLOUDFLARE_AGENT_PATH, agent.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_ID, tunnelId.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN, CloudflareTunnelTokenTest.token(tunnelId))
			.withValue(AppConfigurationField.PUBLIC_BASE_URL, "https://my-domain.example.com");

		CloudflareConnection connection = connector.start(configuration, Duration.ofSeconds(5));

		assertThat(connection.enabled()).isTrue();
		assertThat(connection.publicUrl()).isEqualTo("https://my-domain.example.com");
		verify(process).start(agent, CloudflareTunnelTokenTest.token(tunnelId), CloudflareProtocol.HTTP2);
		assertThat(connection.identity().connectorId()).isEqualTo("58cc3df9-a8f2-41e7-831a-8e699240eb25");
	}

	// 方法：Token 所屬 Tunnel 與綁定 UUID 不同時，在建立 connector 前拒絕啟動。
	@Test
	void shouldRejectATokenBoundToAnotherTunnel() throws Exception {
		UUID expectedTunnelId = UUID.fromString("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");
		UUID actualTunnelId = UUID.fromString("842e9b5f-b31b-4617-9575-b9aca33499bf");
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		CloudflareConnector connector = new CloudflareConnector(process);
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "true")
			.withValue(AppConfigurationField.CLOUDFLARE_AGENT_PATH, agent.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_ID, expectedTunnelId.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN, CloudflareTunnelTokenTest.token(actualTunnelId));

		assertThatThrownBy(() -> connector.start(configuration, Duration.ofSeconds(5)))
			.isInstanceOf(CloudflareConnectorException.class)
			.hasMessageContaining("Tunnel ID");
	}

	// 方法：啟用但缺少 Token 時應拋出例外。
	@Test
	void shouldRejectMissingTokenWhenEnabled() throws Exception {
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});

		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		CloudflareConnector connector = new CloudflareConnector(process);
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
		CloudflareConnector connector = new CloudflareConnector(process);

		connector.stop();

		verify(process).stop(Duration.ofSeconds(3));
	}

	// 方法：readiness 未通過時要帶回安全診斷並停止 child。
	@Test
	void shouldFailWhenTheTunnelNeverBecomesReady() throws Exception {
		UUID tunnelId = UUID.fromString("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");
		Path agent = temporaryDirectory.resolve("cloudflared.exe").toAbsolutePath();
		Files.write(agent, new byte[] {0});
		CloudflareProcessControl process = mock(CloudflareProcessControl.class);
		when(process.awaitReady(Duration.ofSeconds(2))).thenReturn(false);
		when(process.diagnostic()).thenReturn("TCP 7844 連線逾時");
		CloudflareConnector connector = new CloudflareConnector(process);
		AppConfiguration configuration = AppConfiguration.defaults(temporaryDirectory)
			.withValue(AppConfigurationField.CLOUDFLARE_ENABLED, "true")
			.withValue(AppConfigurationField.CLOUDFLARE_AGENT_PATH, agent.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_ID, tunnelId.toString())
			.withValue(AppConfigurationField.CLOUDFLARE_TUNNEL_TOKEN, CloudflareTunnelTokenTest.token(tunnelId));

		assertThatThrownBy(() -> connector.start(configuration, Duration.ofSeconds(2)))
			.isInstanceOf(CloudflareConnectorException.class)
			.hasMessageContaining("TCP 7844");
		verify(process).stop(Duration.ofSeconds(3));
	}
}
