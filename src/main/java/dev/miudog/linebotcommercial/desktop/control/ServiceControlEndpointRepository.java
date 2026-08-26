package dev.miudog.linebotcommercial.desktop.control;

import dev.miudog.linebotcommercial.desktop.config.SecretStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 以平台機密保護與原子替換發布目前 service 控制端點。
 */
public final class ServiceControlEndpointRepository {

	//#region 欄位

	private static final String ENDPOINT_FILE_NAME = "service-control-endpoint.dat";

	private final Path endpointDirectory;
	private final Path endpointFile;
	private final SecretStore secretStore;

	//#endregion

	//#region 建構子

	// 方法：建立指定目錄與平台機密保護服務的控制端點儲存庫。
	public ServiceControlEndpointRepository(
		Path endpointDirectory,
		SecretStore secretStore
	) {
		this.endpointDirectory = Objects.requireNonNull(endpointDirectory, "Service 控制端點目錄不可為 null");
		this.endpointFile = endpointDirectory.resolve(ENDPOINT_FILE_NAME);
		this.secretStore = Objects.requireNonNull(secretStore, "Service 控制機密保護服務不可為 null");
	}

	//#endregion

	//#region 方法

	// 方法：保護 Port 與 nonce 後以原子替換發布完整控制端點。
	public void publish(ServiceControlEndpoint endpoint) {
		Objects.requireNonNull(endpoint, "Service 控制端點不可為 null");
		byte[] plaintext = serialize(endpoint);
		byte[] protectedData = null;
		Path temporaryFile = endpointFile.resolveSibling(ENDPOINT_FILE_NAME + ".tmp");

		try {
			// 外部平台函式：保護包含 nonce 的完整端點資料，避免磁碟明文洩漏控制權杖。
			protectedData = secretStore.protect(plaintext);
			Files.createDirectories(endpointDirectory);
			Files.write(temporaryFile, protectedData);
			moveReplacing(temporaryFile, endpointFile);
		}
		catch (IOException exception) {
			throw new IllegalStateException("無法發布 Service 控制端點", exception);
		}
		finally {
			clear(plaintext);
			clear(protectedData);

			try {
				Files.deleteIfExists(temporaryFile);
			}
			catch (IOException exception) {
				// 暫存檔只包含受保護資料，後續發布時可安全覆寫。
			}
		}
	}

	// 方法：解密並驗證目前控制端點，缺少檔案或格式損毀時安全回傳空值。
	public Optional<ServiceControlEndpoint> load() {
		if (!Files.exists(endpointFile)) return Optional.empty();

		byte[] protectedData = null;
		byte[] plaintext = null;

		try {
			protectedData = Files.readAllBytes(endpointFile);

			// 外部平台函式：只由可解密此端點的 Windows 身分取得每次啟動 nonce。
			plaintext = secretStore.unprotect(protectedData);

			return parse(plaintext);
		}
		catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
		finally {
			clear(protectedData);
			clear(plaintext);
		}
	}

	// 方法：移除已停止 service 的端點，避免 client 連線至過期 Port。
	public void clear() {
		try {
			Files.deleteIfExists(endpointFile);
		}
		catch (IOException exception) {
			throw new IllegalStateException("無法清除 Service 控制端點", exception);
		}
	}

	// 方法：取得受保護端點檔位置供診斷與測試使用。
	public Path endpointFile() {
		return endpointFile;
	}

	// 方法：將端點建立為固定 properties 格式的記憶體資料。
	private byte[] serialize(ServiceControlEndpoint endpoint) {
		Properties properties = new Properties();
		properties.setProperty("port", Integer.toString(endpoint.port()));
		properties.setProperty("nonce", endpoint.nonce());

		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			properties.store(output, null);

			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new IllegalStateException("無法建立 Service 控制端點資料", exception);
		}
	}

	// 方法：解析完整且有效的端點資料，未知或缺少欄位時拒絕載入。
	private Optional<ServiceControlEndpoint> parse(byte[] source) throws IOException {
		Properties properties = new Properties();

		try (ByteArrayInputStream input = new ByteArrayInputStream(source)) {
			properties.load(input);
		}

		try {
			int port = Integer.parseInt(properties.getProperty("port", ""));
			String nonce = properties.getProperty("nonce", "");

			return Optional.of(new ServiceControlEndpoint(port, nonce));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	// 方法：優先原子替換端點檔，不支援時退回同磁碟一般替換。
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

	// 方法：覆寫記憶體中的端點位元組，縮短 nonce 明文與密文停留時間。
	private void clear(byte[] source) {
		if (source == null) return;

		Arrays.fill(source, (byte) 0);
	}

	//#endregion
}
