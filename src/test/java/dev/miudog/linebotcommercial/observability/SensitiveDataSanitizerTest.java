package dev.miudog.linebotcommercial.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataSanitizerTest {

	// 驗證安全下載網址不會把 bearer token 寫入 HTTP 日誌。
	@Test
	void redactsDownloadTokenFromPath() {
		String sanitized = SensitiveDataSanitizer.sanitizeRequestPath(
			"/api/quotations/download/super-secret-download-token"
		);

		assertThat(sanitized)
			.isEqualTo("/api/quotations/download/[REDACTED]")
			.doesNotContain("super-secret-download-token");
	}

	// 驗證一般固定路由維持原樣，方便依路由查詢 RED 日誌。
	@Test
	void preservesNonSensitiveRoute() {
		assertThat(SensitiveDataSanitizer.sanitizeRequestPath("/callback")).isEqualTo("/callback");
	}

	// 驗證正式報價下載路由改寫為低基數模板，原始 token 完全消失。
	@Test
	void templatesQuotationDownloadToken() {
		String sanitized = SensitiveDataSanitizer.sanitizeRequestPath(
			"/quotation-downloads/raw-quotation-token"
		);

		assertThat(sanitized)
			.isEqualTo("/quotation-downloads/{token}")
			.doesNotContain("raw-quotation-token");
	}

	// 驗證圖片分享網址同樣不保存資產分享 token。
	@Test
	void templatesMediaShareToken() {
		String sanitized = SensitiveDataSanitizer.sanitizeRequestPath("/media/raw-share-token");

		assertThat(sanitized)
			.isEqualTo("/media/{shareToken}")
			.doesNotContain("raw-share-token");
	}
}
