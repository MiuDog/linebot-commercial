package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * 以跨程序檔案鎖避免同一台電腦的不同 App 共用同一 Cloudflare Tunnel。
 */
public final class CloudflareTunnelLease {

	//#region 欄位

	private final Path leaseRoot;
	private final boolean enabled;
	private FileChannel channel;
	private FileLock lock;

	//#endregion

	//#region 建構子

	// 方法：建立使用指定共用目錄的正式 Tunnel 租約。
	CloudflareTunnelLease(Path leaseRoot) {
		this(leaseRoot, true);
	}

	// 方法：建立可停用檔案 I/O 的測試租約。
	private CloudflareTunnelLease(
		Path leaseRoot,
		boolean enabled
	) {
		this.leaseRoot = Objects.requireNonNull(leaseRoot, "Tunnel 租約目錄不可為 null");
		this.enabled = enabled;
	}

	//#endregion

	//#region 方法

	// 方法：建立目前 Windows 使用者下供兩個 App 共用的 Tunnel 租約管理器。
	public static CloudflareTunnelLease platformDefault() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path root = localAppData == null || localAppData.isBlank()
			? Path.of(System.getProperty("user.home"), "AppData", "Local")
			: Path.of(localAppData);

		return new CloudflareTunnelLease(root.resolve("MiuDog").resolve("CloudflareTunnelLocks"));
	}

	// 方法：建立不操作檔案系統的測試租約，讓 connector 單元測試保持隔離。
	static CloudflareTunnelLease disabled() {
		return new CloudflareTunnelLease(Path.of("."), false);
	}

	// 方法：嘗試獨占指定 Tunnel，失敗代表同機已有另一個 App connector。
	public synchronized boolean acquire(
		UUID tunnelId,
		String productName
	) {
		Objects.requireNonNull(tunnelId, "Tunnel ID 不可為 null");
		if (!enabled) return true;

		release();

		try {
			// Java 檔案函式庫：建立兩個 App 共用的非機密 Tunnel 鎖定目錄。
			Files.createDirectories(leaseRoot);
			Path leaseFile = leaseRoot.resolve(tunnelId + ".lock");
			channel = FileChannel.open(
				leaseFile,
				StandardOpenOption.CREATE,
				StandardOpenOption.READ,
				StandardOpenOption.WRITE
			);

			// Java NIO 函式庫：以作業系統跨程序鎖判斷同機是否已有 connector。
			lock = channel.tryLock();
			if (lock == null) {
				closeChannel();

				return false;
			}

			String metadata = Objects.requireNonNullElse(productName, "UnknownProduct")
				+ System.lineSeparator()
				+ ProcessHandle.current().pid();

			// Java NIO 函式庫：只記錄產品與程序編號，禁止將 Token 寫入租約檔。
			channel.truncate(0);
			channel.write(StandardCharsets.UTF_8.encode(metadata));
			channel.force(true);

			return true;
		}
		catch (IOException | OverlappingFileLockException exception) {
			release();

			return false;
		}
	}

	// 方法：釋放目前 Tunnel 的跨程序鎖與檔案控制代碼。
	public synchronized void release() {
		if (lock != null) {
			try {
				lock.release();
			}
			catch (IOException exception) {
				// 關閉 channel 時仍會由作業系統回收鎖，無需阻止 App 結束。
			}

			lock = null;
		}

		closeChannel();
	}

	// 方法：關閉租約檔案控制代碼並容忍關機階段的重複釋放。
	private void closeChannel() {
		if (channel == null) return;

		try {
			channel.close();
		}
		catch (IOException exception) {
			// 作業系統會在程序結束時回收控制代碼，不額外掩蓋主要流程結果。
		}

		channel = null;
	}

	//#endregion
}
