package dev.miudog.linebotcommercial.desktop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證一般設定與受保護機密的保存、載入及失敗邊界。
 */
class AppConfigurationRepositoryTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：驗證一般設定檔與機密檔會分離保存並可完整載入。
	@Test
	void shouldSeparateAndReloadPublicAndSecretValues() throws IOException {
		AppConfigurationRepository repository = repository(new XorSecretStore());
		AppConfiguration configuration = validConfiguration()
			.withValue(AppConfigurationField.AI_MODEL, "gpt-test")
			.withValue(AppConfigurationField.AI_API_KEY, "sensitive-ai-key");

		repository.save(configuration);
		Optional<AppConfiguration> loaded = repository.load();
		String publicFile = Files.readString(repository.publicFile(), StandardCharsets.ISO_8859_1);
		String protectedFile = Files.readString(repository.secretFile(), StandardCharsets.ISO_8859_1);

		assertThat(loaded).isPresent();
		assertThat(loaded.orElseThrow().value(AppConfigurationField.AI_MODEL)).isEqualTo("gpt-test");
		assertThat(loaded.orElseThrow().value(AppConfigurationField.AI_API_KEY)).isEqualTo("sensitive-ai-key");
		assertThat(publicFile).contains("AI_MODEL=gpt-test").doesNotContain("sensitive-ai-key");
		assertThat(protectedFile).doesNotContain("sensitive-ai-key");
	}

	// 方法：驗證新欄位不存在時使用預設值，未知欄位不會使舊設定失敗。
	@Test
	void shouldLoadOlderConfigurationWithDefaultsAndIgnoreUnknownFields() throws IOException {
		AppConfigurationRepository repository = repository(new XorSecretStore());

		Files.createDirectories(repository.publicFile().getParent());
		Files.writeString(
			repository.publicFile(),
			"schema.version=1\nLINE_BOT_CHANNEL_TOKEN=token\nLINE_BOT_CHANNEL_SECRET=secret\nUNKNOWN_FUTURE_FIELD=value\n",
			StandardCharsets.ISO_8859_1
		);

		AppConfiguration loaded = repository.load().orElseThrow();

		assertThat(loaded.value(AppConfigurationField.SERVER_PORT)).isEqualTo("8088");
		assertThat(loaded.value(AppConfigurationField.LOG_MAX_HISTORY)).isEqualTo("30");
	}

	// 方法：舊設定升級時關閉永久方法追蹤，避免隱藏欄位持續消耗 CPU 與磁碟。
	@Test
	void shouldDisablePersistentMethodTracingWhenMigratingLegacyConfiguration() throws IOException {
		AppConfigurationRepository repository = repository(new XorSecretStore());

		Files.createDirectories(repository.publicFile().getParent());
		Files.writeString(
			repository.publicFile(),
			"schema.version=1\nMETHOD_TRACING_ENABLED=true\n",
			StandardCharsets.ISO_8859_1
		);

		AppConfiguration loaded = repository.load().orElseThrow();

		assertThat(loaded.schemaVersion()).isEqualTo(AppConfiguration.CURRENT_SCHEMA_VERSION);
		assertThat(loaded.value(AppConfigurationField.METHOD_TRACING_ENABLED)).isEqualTo("false");
	}

	// 方法：目前版本明確開啟追蹤時保留設定，供受控診斷流程使用。
	@Test
	void shouldPreserveExplicitMethodTracingInCurrentConfiguration() throws IOException {
		AppConfigurationRepository repository = repository(new XorSecretStore());

		Files.createDirectories(repository.publicFile().getParent());
		Files.writeString(
			repository.publicFile(),
			"schema.version=" + AppConfiguration.CURRENT_SCHEMA_VERSION + "\nMETHOD_TRACING_ENABLED=true\n",
			StandardCharsets.ISO_8859_1
		);

		AppConfiguration loaded = repository.load().orElseThrow();

		assertThat(loaded.value(AppConfigurationField.METHOD_TRACING_ENABLED)).isEqualTo("true");
	}

	// 方法：驗證保護機密失敗時不會覆蓋上一份有效設定。
	@Test
	void shouldPreserveThePreviousConfigurationWhenProtectionFails() {
		AppConfigurationRepository workingRepository = repository(new XorSecretStore());
		workingRepository.save(validConfiguration().withValue(AppConfigurationField.AI_MODEL, "stable-model"));
		AppConfigurationRepository failingRepository = repository(new FailingSecretStore());

		assertThatThrownBy(() -> failingRepository.save(
			validConfiguration().withValue(AppConfigurationField.AI_MODEL, "broken-model")
		)).isInstanceOf(SecretProtectionException.class);

		assertThat(workingRepository.load().orElseThrow().value(AppConfigurationField.AI_MODEL))
			.isEqualTo("stable-model");
	}

	// 方法：建立使用暫存設定目錄的 Repository。
	private AppConfigurationRepository repository(SecretStore secretStore) {
		Path localAppData = temporaryDirectory.resolve("LocalAppData");

		return new AppConfigurationRepository(
			AppConfiguration.configurationRoot(localAppData),
			AppConfiguration.defaults(localAppData),
			secretStore,
			new AppConfigurationValidator()
		);
	}

	// 方法：建立可通過驗證的最小設定。
	private AppConfiguration validConfiguration() {
		return AppConfiguration.defaults(temporaryDirectory.resolve("LocalAppData"))
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_TOKEN, "line-token")
			.withValue(AppConfigurationField.LINE_BOT_CHANNEL_SECRET, "line-secret");
	}

	/**
	 * 提供不依賴 native API 的可逆測試保護器。
	 */
	private static final class XorSecretStore implements SecretStore {

		// 方法：以固定遮罩保護測試資料。
		@Override
		public byte[] protect(byte[] plaintext) {
			return transform(plaintext);
		}

		// 方法：以相同固定遮罩還原測試資料。
		@Override
		public byte[] unprotect(byte[] protectedData) {
			return transform(protectedData);
		}

		// 方法：表示測試保護器永遠可用。
		@Override
		public boolean available() {
			return true;
		}

		// 方法：複製資料並逐 byte 套用固定遮罩。
		private byte[] transform(byte[] source) {
			byte[] transformed = source.clone();

			for (int index = 0; index < transformed.length; index++) {
				transformed[index] ^= 0x5A;
			}

			return transformed;
		}
	}

	/**
	 * 模擬平台機密保護失敗。
	 */
	private static final class FailingSecretStore implements SecretStore {

		// 方法：固定拒絕保護資料。
		@Override
		public byte[] protect(byte[] plaintext) {
			throw new SecretProtectionException("測試保護失敗", null);
		}

		// 方法：測試案例不會執行解密。
		@Override
		public byte[] unprotect(byte[] protectedData) {
			throw new UnsupportedOperationException("測試不支援解密");
		}

		// 方法：表示測試替代實作已被注入。
		@Override
		public boolean available() {
			return true;
		}
	}
}

