package dev.miudog.linebotcommercial.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以使用者設定目錄內的 FileLock 與認證 IPC 保證單一桌面後端。
 */
public final class SingleInstanceCoordinator implements AutoCloseable {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(SingleInstanceCoordinator.class);
	private static final String LOCK_FILE_NAME = "desktop.lock";
	private static final String METADATA_FILE_NAME = "desktop-instance.properties";
	private static final Duration IPC_TIMEOUT = Duration.ofSeconds(3);

	private final Path instanceDirectory;
	private final Path lockFile;
	private final Path metadataFile;
	private FileChannel lockChannel;
	private FileLock lock;
	private DesktopIpcServer ipcServer;

	//#endregion

	//#region 建構子

	// 方法：建立使用指定目前使用者目錄的單一執行個體協調器。
	public SingleInstanceCoordinator(Path instanceDirectory) {
		this.instanceDirectory = Objects.requireNonNull(instanceDirectory, "執行個體目錄不可為 null");
		this.lockFile = instanceDirectory.resolve(LOCK_FILE_NAME);
		this.metadataFile = instanceDirectory.resolve(METADATA_FILE_NAME);
	}

	//#endregion

	//#region 方法

	// 方法：嘗試成為主程序；鎖已占用時只向既有程序傳送指定命令。
	public synchronized SingleInstanceResult acquireOrNotify(
		DesktopIpcCommand command,
		Consumer<DesktopIpcCommand> commandHandler
	) {
		if (lock != null) throw new IllegalStateException("目前協調器已取得主程序鎖");

		Objects.requireNonNull(command, "IPC 命令不可為 null");
		Objects.requireNonNull(commandHandler, "IPC 命令處理器不可為 null");

		try {
			Files.createDirectories(instanceDirectory);
			lockChannel = FileChannel.open(
				lockFile,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
			);
			lock = tryLock(lockChannel);

			if (lock == null) {
				closeChannel();

				return notifyPrimary(command);
			}

			startPrimary(commandHandler);

			return SingleInstanceResult.PRIMARY;
		}
		catch (IOException | RuntimeException exception) {
			close();

			throw new IllegalStateException("無法協調桌面單一執行個體", exception);
		}
	}

	// 方法：關閉 IPC、刪除 metadata 並釋放檔案鎖與通道。
	@Override
	public synchronized void close() {
		if (ipcServer != null) {
			ipcServer.close();
			ipcServer = null;
		}

		if (lock != null) {
			try {
				Files.deleteIfExists(metadataFile);
				lock.release();
			}
			catch (IOException exception) {
				// 日誌：記錄單一執行個體資源釋放錯誤，不包含 nonce。
				log.warn("event=desktop_instance_release_failed errorType={}",
					exception.getClass().getSimpleName()
				);
			}
			finally {
				lock = null;
			}
		}

		closeChannel();
	}

	// 方法：取得檔案鎖，同 JVM 已持有時視為其他執行個體占用。
	private FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		}
		catch (OverlappingFileLockException exception) {
			return null;
		}
	}

	// 方法：建立隨機 nonce、啟動 loopback IPC 並原子發布連線資料。
	private void startPrimary(Consumer<DesktopIpcCommand> commandHandler) throws IOException {
		byte[] nonceBytes = new byte[32];

		// 外部函式：使用密碼學安全亂數建立每次啟動都不同的 IPC nonce。
		new SecureRandom().nextBytes(nonceBytes);
		String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
		ipcServer = new DesktopIpcServer(nonce, commandHandler);
		ipcServer.start();
		writeMetadata(ipcServer.port(), nonce);

		// 日誌：記錄目前程序取得主資格，不輸出 IPC nonce。
		log.info("event=desktop_primary_instance_acquired port={}", ipcServer.port());
	}

	// 方法：讀取主程序連線資料並傳送命令，失敗時回傳可判斷結果。
	private SingleInstanceResult notifyPrimary(DesktopIpcCommand command) throws IOException {
		Optional<InstanceMetadata> metadata = readMetadata();

		if (metadata.isEmpty()) return SingleInstanceResult.FAILED;

		InstanceMetadata primary = metadata.orElseThrow();
		boolean notified = DesktopIpcServer.send(
			primary.port(),
			primary.nonce(),
			command,
			IPC_TIMEOUT
		);

		return notified ? SingleInstanceResult.NOTIFIED : SingleInstanceResult.FAILED;
	}

	// 方法：以暫存檔原子發布 Port 與 nonce，避免第二個程序讀到半份資料。
	private void writeMetadata(
		int port,
		String nonce
	) throws IOException {
		Properties properties = new Properties();
		Path temporaryFile = metadataFile.resolveSibling(METADATA_FILE_NAME + ".tmp");

		properties.setProperty("port", Integer.toString(port));
		properties.setProperty("nonce", nonce);

		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			properties.store(output, null);
			Files.write(temporaryFile, output.toByteArray());

			try {
				Files.move(
					temporaryFile,
					metadataFile,
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE
				);
			}
			catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryFile, metadataFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally {
			Files.deleteIfExists(temporaryFile);
		}
	}

	// 方法：解析完整 metadata；缺少、損毀或超出 Port 範圍時安全失敗。
	private Optional<InstanceMetadata> readMetadata() throws IOException {
		if (!Files.exists(metadataFile)) return Optional.empty();

		Properties properties = new Properties();

		try (ByteArrayInputStream input = new ByteArrayInputStream(Files.readAllBytes(metadataFile))) {
			properties.load(input);
		}

		try {
			int port = Integer.parseInt(properties.getProperty("port", ""));
			String nonce = properties.getProperty("nonce", "");

			if (port < 1 || port > 65535 || nonce.isBlank()) return Optional.empty();

			return Optional.of(new InstanceMetadata(port, nonce));
		}
		catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	// 方法：關閉檔案通道；失敗僅記錄類型並清除本地參照。
	private void closeChannel() {
		if (lockChannel == null) return;

		try {
			lockChannel.close();
		}
		catch (IOException exception) {
			// 日誌：記錄 lock channel 關閉失敗，不包含檔案內容或認證資料。
			log.warn("event=desktop_instance_channel_close_failed errorType={}",
				exception.getClass().getSimpleName()
			);
		}
		finally {
			lockChannel = null;
		}
	}

	//#endregion

	/**
	 * 保存已驗證的主程序 loopback 連線資料。
	 */
	private record InstanceMetadata(int port, String nonce) {

		// 方法：建立不可含無效 Port 或空白 nonce 的連線資料。
		private InstanceMetadata {
			if (port < 1 || port > 65535) throw new IllegalArgumentException("IPC Port 無效");

			if (nonce == null || nonce.isBlank()) throw new IllegalArgumentException("IPC nonce 不可為空白");
		}
	}
}
