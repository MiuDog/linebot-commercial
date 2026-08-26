package dev.miudog.linebotcommercial.desktop.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 驗證 cloudflared 官方啟動訊息可轉換為不含 Token 的 connector 身分。
 */
class CloudflareAgentIdentityTest {

	//#region 測試

	// 方法：從純文字啟動訊息累積 Tunnel 與 Connector UUID。
	@Test
	void shouldCollectIdentityFromCloudflaredMessages() {
		CloudflareAgentIdentity identity = CloudflareAgentIdentity.empty()
			.withDiagnostic("2026-08-25T10:00:00Z INF Starting tunnel tunnelID=b5b327f7-ead7-449c-b5eb-97fc74fccbfb")
			.withDiagnostic("2026-08-25T10:00:00Z INF Generated Connector ID: 58cc3df9-a8f2-41e7-831a-8e699240eb25");

		assertThat(identity.tunnelId()).isEqualTo("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");
		assertThat(identity.connectorId()).isEqualTo("58cc3df9-a8f2-41e7-831a-8e699240eb25");
		assertThat(identity.computerName()).isNotBlank();
	}

	// 方法：忽略不含官方身分欄位的診斷，不把任意內容帶入介面。
	@Test
	void shouldIgnoreUnrelatedDiagnostics() {
		CloudflareAgentIdentity identity = CloudflareAgentIdentity.empty()
			.withDiagnostic("token=do-not-display arbitrary text");

		assertThat(identity.tunnelId()).isEmpty();
		assertThat(identity.connectorId()).isEmpty();
	}

	//#endregion
}
