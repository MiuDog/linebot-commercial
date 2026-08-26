package dev.miudog.linebotcommercial.desktop.control;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以產品設定目錄內的跨程序 FileLock 保證背景 service 單一執行個體。
 */
public final class ServiceInstanceLock implements ServiceInstanceResource {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(ServiceInstanceLock.class);
	private static final String LOCK_FILE_NAME = "service.lock";

	private final Path instanceDirectory;
	private final Path lockFile;
	private FileChannel lockChannel;
	private FileLock lock;

	//#endregion

	//#region 建構子

	// 方法：建立使用指定產品設定目錄的背景 service 鎖。
	public ServiceInstanceLock(Path instanceDirectory) {
		this.instanceDirectory = Objects.requireNonNull(instanceDirectory, "Service 執行個體目錄不可為 null");
		this.lockFile = instanceDirectory.resolve(LOCK_FILE_NAME);
	}

	//#endregion

	//#region 方法

	// 方法：嘗試取得跨程序鎖，已有 service 運行時安全回傳 false。
	@Override
	public synchronized boolean acquire() {
		if (lock != null) throw new IllegalStateException("目前 Service 已持有執行個體鎖");

		try {
			// 外部檔案系統：先建立固定產品目錄，再開啟專用鎖檔而不操作其他路徑。
			Files.createDirectories(instanceDirectory);
			lockChannel = FileChannel.open(
				lockFile,
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
			);

			try {
				lock = lockChannel.tryLock();
			}
			catch (OverlappingFileLockException exception) {
				lock = null;
			}

			if (lock != null) return true;

			closeChannel();

			return false;
		}
		catch (IOException exception) {
			close();

			throw new IllegalStateException("無法取得 Service 執行個體鎖", exception);
		}
	}

	// 方法：釋放跨程序鎖與檔案通道，重複呼叫不產生額外操作。
	@Override
	public synchronized void close() {
		if (lock != null) {
			try {
				lock.release();
			}
			catch (IOException exception) {
				// 日誌：記錄 service 鎖釋放失敗類型，不輸出使用者路徑。
				log.warn("event=service_instance_lock_release_failed errorType={}",
					exception.getClass().getSimpleName()
				);
			}
			finally {
				lock = null;
			}
		}

		closeChannel();
	}

	// 方法：關閉鎖檔通道並清除本地參照。
	private void closeChannel() {
		if (lockChannel == null) return;

		try {
			lockChannel.close();
		}
		catch (IOException exception) {
			// 日誌：記錄鎖檔通道關閉失敗類型，不輸出產品資料位置。
			log.warn("event=service_instance_channel_close_failed errorType={}",
				exception.getClass().getSimpleName()
			);
		}
		finally {
			lockChannel = null;
		}
	}

	//#endregion
}

