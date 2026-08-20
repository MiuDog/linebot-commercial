package dev.miudog.linebotcommercial.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.jna.Platform;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 驗證 Windows DPAPI 機密保護與安全失敗行為。
 */
class DpapiSecretStoreTest {

	// 方法：驗證目前 Windows 使用者可以保護並還原相同機密內容。
	@Test
	void shouldProtectAndUnprotectForTheCurrentWindowsUser() {
		assumeTrue(Platform.isWindows());
		DpapiSecretStore secretStore = new DpapiSecretStore();
		byte[] plaintext = "desktop-secret-value".getBytes(StandardCharsets.UTF_8);

		byte[] protectedData = secretStore.protect(plaintext);
		byte[] unprotectedData = secretStore.unprotect(protectedData);

		assertThat(protectedData).isNotEqualTo(plaintext);
		assertThat(unprotectedData).isEqualTo(plaintext);
	}

	// 方法：驗證損毀的 DPAPI 密文只回報安全摘要而不洩漏輸入內容。
	@Test
	void shouldRejectCorruptedProtectedDataWithoutLeakingIt() {
		assumeTrue(Platform.isWindows());
		DpapiSecretStore secretStore = new DpapiSecretStore();
		byte[] corruptedData = "corrupted-secret-payload".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> secretStore.unprotect(corruptedData))
			.isInstanceOf(SecretProtectionException.class)
			.hasMessage("無法解密目前使用者的機密設定")
			.hasMessageNotContaining("corrupted-secret-payload");
	}

	// 方法：驗證跨平台測試可注入不載入 Windows native API 的替代實作。
	@Test
	void shouldAllowAnInMemoryProviderForCrossPlatformTests() {
		SecretStore secretStore = new SecretStore() {

			// 方法：複製測試資料以模擬保護結果。
			@Override
			public byte[] protect(byte[] plaintext) {
				return plaintext.clone();
			}

			// 方法：複製測試資料以模擬解密結果。
			@Override
			public byte[] unprotect(byte[] protectedData) {
				return protectedData.clone();
			}

			// 方法：表示測試替代實作可在所有平台使用。
			@Override
			public boolean available() {
				return true;
			}
		};
		byte[] plaintext = "test-only".getBytes(StandardCharsets.UTF_8);

		assertThat(secretStore.unprotect(secretStore.protect(plaintext))).isEqualTo(plaintext);
		assertThat(secretStore.available()).isTrue();
	}
}

