package dev.myudog.assetsmanagerlinebot.desktop.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 將一般設定與受平台保護的機密設定分檔保存。
 */
public final class AppConfigurationRepository {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(AppConfigurationRepository.class);
	private static final String PUBLIC_FILE_NAME = "application.properties";
	private static final String SECRET_FILE_NAME = "secrets.dat";
	private static final String SCHEMA_VERSION_KEY = "schema.version";

	private final Path configurationRoot;
	private final Path publicFile;
	private final Path secretFile;
	private final AppConfiguration defaults;
	private final SecretStore secretStore;
	private final AppConfigurationValidator validator;

	//#endregion

	//#region 建構子

	// 方法：建立指定設定目錄與平台機密保護服務的儲存庫。
	public AppConfigurationRepository(
		Path configurationRoot,
		AppConfiguration defaults,
		SecretStore secretStore,
		AppConfigurationValidator validator
	) {
		this.configurationRoot = Objects.requireNonNull(configurationRoot, "設定目錄不可為 null");
		this.publicFile = configurationRoot.resolve(PUBLIC_FILE_NAME);
		this.secretFile = configurationRoot.resolve(SECRET_FILE_NAME);
		this.defaults = Objects.requireNonNull(defaults, "預設設定不可為 null");
		this.secretStore = Objects.requireNonNull(secretStore, "機密保護服務不可為 null");
		this.validator = Objects.requireNonNull(validator, "設定驗證器不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：讀取一般與機密設定，並以預設值補齊舊版缺少的欄位。
	public Optional<AppConfiguration> load() {
		if (!Files.exists(publicFile)) return Optional.empty();

		byte[] protectedSecrets = null;
		byte[] plaintextSecrets = null;

		try {
			Properties publicProperties = loadProperties(Files.readAllBytes(publicFile));
			EnumMap<AppConfigurationField, String> values = new EnumMap<>(defaults.values());
			int schemaVersion = parseSchemaVersion(publicProperties);

			// 步驟一：只載入已知且非機密的一般設定欄位。
			applyKnownValues(publicProperties, values, false);

			// 步驟二：存在機密檔時先交由平台解密，再載入已知機密欄位。
			if (Files.exists(secretFile)) {
				protectedSecrets = Files.readAllBytes(secretFile);

				// 外部函式：透過平台機密服務還原目前 Windows 使用者的設定資料。
				plaintextSecrets = secretStore.unprotect(protectedSecrets);
				applyKnownValues(loadProperties(plaintextSecrets), values, true);
			}

			// 日誌：記錄設定成功載入，不輸出任何欄位值或機密內容。
			log.info("event=desktop_configuration_loaded schemaVersion={} publicFile={} secretFilePresent={}",
				schemaVersion,
				publicFile,
				Files.exists(secretFile)
			);

			return Optional.of(new AppConfiguration(schemaVersion, values));
		}
		catch (IOException exception) {
			throw new ConfigurationPersistenceException("無法讀取桌面設定檔", exception);
		}
		finally {
			clear(protectedSecrets);
			clear(plaintextSecrets);
		}
	}

	// 方法：驗證並以可回復的雙檔交易保存一般與機密設定。
	public void save(AppConfiguration configuration) {
		Objects.requireNonNull(configuration, "桌面設定不可為 null");
		List<AppConfigurationValidator.Violation> violations = validator.validate(configuration);

		if (!violations.isEmpty()) throw new InvalidConfigurationException(violations);

		byte[] publicData = serialize(configuration, false);
		byte[] plaintextSecrets = serialize(configuration, true);
		byte[] protectedSecrets = null;

		try {
			// 外部函式：先完成平台機密保護，失敗時不觸碰任何既有設定檔。
			protectedSecrets = secretStore.protect(plaintextSecrets);
			writeTransaction(publicData, protectedSecrets);

			// 日誌：記錄設定安全寫入完成，不輸出任何欄位值或機密內容。
			log.info("event=desktop_configuration_saved schemaVersion={} publicFile={}",
				configuration.schemaVersion(),
				publicFile
			);
		}
		finally {
			clear(plaintextSecrets);
			clear(protectedSecrets);
		}
	}

	// 方法：取得一般設定檔位置供介面與測試顯示。
	public Path publicFile() {
		return publicFile;
	}

	// 方法：取得受保護機密設定檔位置供診斷與測試使用。
	public Path secretFile() {
		return secretFile;
	}

	// 方法：將指定種類的設定欄位序列化為 Java properties 格式。
	private byte[] serialize(
		AppConfiguration configuration,
		boolean secrets
	) {
		Properties properties = new Properties();

		if (!secrets) properties.setProperty(SCHEMA_VERSION_KEY, Integer.toString(configuration.schemaVersion()));

		for (AppConfigurationField field : AppConfigurationField.values()) {
			if (field.secret() != secrets) continue;

			properties.setProperty(field.environmentKey(), configuration.value(field));
		}

		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			properties.store(output, null);

			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new ConfigurationPersistenceException("無法建立桌面設定資料", exception);
		}
	}

	// 方法：從記憶體資料解析 Java properties 格式。
	private Properties loadProperties(byte[] source) throws IOException {
		Properties properties = new Properties();

		try (ByteArrayInputStream input = new ByteArrayInputStream(source)) {
			properties.load(input);
		}

		return properties;
	}

	// 方法：將已知且符合指定機密種類的欄位套用至設定集合。
	private void applyKnownValues(
		Properties properties,
		Map<AppConfigurationField, String> values,
		boolean secrets
	) {
		for (String key : properties.stringPropertyNames()) {
			Optional<AppConfigurationField> matchedField = AppConfigurationField.fromEnvironmentKey(key);

			if (matchedField.isEmpty()) continue;

			AppConfigurationField field = matchedField.orElseThrow();

			if (field.secret() != secrets) continue;

			values.put(field, properties.getProperty(key, "").trim());
		}
	}

	// 方法：解析設定格式版本，無值或無法解析時退回目前版本。
	private int parseSchemaVersion(Properties properties) {
		String version = properties.getProperty(
			SCHEMA_VERSION_KEY,
			Integer.toString(AppConfiguration.CURRENT_SCHEMA_VERSION)
		);

		try {
			return Integer.parseInt(version);
		}
		catch (NumberFormatException exception) {
			return AppConfiguration.CURRENT_SCHEMA_VERSION;
		}
	}

	// 方法：先建立兩份暫存檔，再共同替換正式檔並於失敗時回復舊版。
	private void writeTransaction(
		byte[] publicData,
		byte[] protectedSecrets
	) {
		Path publicTemporary = publicFile.resolveSibling(PUBLIC_FILE_NAME + ".tmp");
		Path secretTemporary = secretFile.resolveSibling(SECRET_FILE_NAME + ".tmp");
		Path publicBackup = publicFile.resolveSibling(PUBLIC_FILE_NAME + ".bak");
		Path secretBackup = secretFile.resolveSibling(SECRET_FILE_NAME + ".bak");
		boolean publicFileExisted = Files.exists(publicFile);
		boolean secretFileExisted = Files.exists(secretFile);
		boolean replacementStarted = false;

		try {
			Files.createDirectories(configurationRoot);
			Files.write(publicTemporary, publicData);
			Files.write(secretTemporary, protectedSecrets);
			backup(publicFile, publicBackup);
			backup(secretFile, secretBackup);
			replacementStarted = true;
			moveReplacing(publicTemporary, publicFile);
			moveReplacing(secretTemporary, secretFile);
		}
		catch (IOException exception) {
			if (replacementStarted) {
				restore(publicFile, publicBackup, publicFileExisted);
				restore(secretFile, secretBackup, secretFileExisted);
			}

			throw new ConfigurationPersistenceException("無法安全寫入桌面設定檔", exception);
		}
		finally {
			deleteQuietly(publicTemporary);
			deleteQuietly(secretTemporary);
			deleteQuietly(publicBackup);
			deleteQuietly(secretBackup);
		}
	}

	// 方法：存在正式檔時建立交易回復用備份。
	private void backup(
		Path source,
		Path backup
	) throws IOException {
		Files.deleteIfExists(backup);

		if (Files.exists(source)) Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
	}

	// 方法：優先使用原子移動替換檔案，不支援時退回同磁碟一般替換。
	private void moveReplacing(
		Path source,
		Path target
	) throws IOException {
		try {
			Files.move(
				source,
				target,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING
			);
		}
		catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// 方法：依備份是否存在回復舊檔，避免留下只更新一半的設定。
	private void restore(
		Path target,
		Path backup,
		boolean existed
	) {
		try {
			if (existed && Files.exists(backup)) {
				moveReplacing(backup, target);
			}
			else if (!existed) {
				Files.deleteIfExists(target);
			}
		}
		catch (IOException exception) {
			// 日誌：回復失敗時只記錄檔案位置與錯誤類型，不包含設定內容。
			log.error("event=desktop_configuration_restore_failed target={} errorType={}",
				target,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：清理交易暫存檔，清理失敗只留下可安全覆寫的非機密殘檔。
	private void deleteQuietly(Path target) {
		try {
			Files.deleteIfExists(target);
		}
		catch (IOException exception) {
			// 日誌：記錄暫存檔清理失敗供後續診斷，不包含任何設定值。
			log.warn("event=desktop_configuration_temporary_cleanup_failed target={} errorType={}",
				target,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：覆寫記憶體中的機密位元組，縮短明文與密文停留時間。
	private void clear(byte[] source) {
		if (source == null) return;

		Arrays.fill(source, (byte) 0);
	}

	//#endregion
}
