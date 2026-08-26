package dev.miudog.linebotcommercial.desktop.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 驗證 Cloudflare Tunnel Token 只解讀官方 cloudflared 使用的憑證欄位，且不外洩機密。
 */
class CloudflareTunnelTokenTest {

	//#region 測試

	// 方法：從 cloudflared Token 的 TunnelID 欄位取得標準 UUID。
	@Test
	void shouldReadTunnelIdFromToken() {
		UUID tunnelId = UUID.fromString("b5b327f7-ead7-449c-b5eb-97fc74fccbfb");

		assertThat(CloudflareTunnelToken.tunnelId(token(tunnelId))).isEqualTo(tunnelId);
	}

	// 方法：拒絕無法解碼、缺少 TunnelID 或包含非 UUID 身分的 Token。
	@Test
	void shouldRejectMalformedTokensWithoutEchoingTheSecret() {
		String secret = "highly-sensitive-token";

		assertThatThrownBy(() -> CloudflareTunnelToken.tunnelId(secret))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(secret);
	}

	// 方法：建立與 cloudflared Token 憑證結構相同的測試資料。
	static String token(UUID tunnelId) {
		String payload = "{\"a\":\"account\",\"t\":\"" + tunnelId + "\",\"s\":\"secret\"}";

		// Java Base64 函式庫：模擬 Cloudflare 遠端管理 Tunnel Token 的編碼內容。
		return Base64.getEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	//#endregion
}
