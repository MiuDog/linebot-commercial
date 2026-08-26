package dev.miudog.linebotcommercial.desktop.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 從 jpackage 桌面執行檔旁啟動固定名稱的背景 service launcher。
 */
public final class PackagedServiceLauncher implements Runnable {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(PackagedServiceLauncher.class);

	private final Path serviceExecutable;

	//#endregion

	//#region 建構子

	// 方法：建立只允許啟動已解析固定 service 執行檔的 launcher。
	public PackagedServiceLauncher(Path serviceExecutable) {
		this.serviceExecutable = Objects.requireNonNull(serviceExecutable, "Service 執行檔不可為 null")
			.toAbsolutePath()
			.normalize();
	}

	//#endregion

	//#region 方法

	// 方法：從目前 jpackage 桌面程序解析同目錄下的固定 service 執行檔。
	public static PackagedServiceLauncher fromCurrentProcess(String serviceFileName) {
		// 外部 JVM：取得目前桌面 launcher 的實際命令路徑，避免依賴可被修改的工作目錄。
		String desktopCommand = ProcessHandle.current()
			.info()
			.command()
			.orElseThrow(() -> new IllegalStateException("無法取得目前桌面執行檔位置"));

		return new PackagedServiceLauncher(resolveServiceExecutable(Path.of(desktopCommand), serviceFileName));
	}

	// 方法：限制 service 為桌面執行檔同目錄下的單一 exe 檔名。
	static Path resolveServiceExecutable(
		Path desktopExecutable,
		String serviceFileName
	) {
		Objects.requireNonNull(desktopExecutable, "桌面執行檔不可為 null");
		if (serviceFileName == null || !serviceFileName.matches("[A-Za-z0-9._-]+\\.exe")) {
			throw new IllegalArgumentException("Service 執行檔名稱無效");
		}

		Path desktopPath = desktopExecutable.toAbsolutePath().normalize();
		Path parent = desktopPath.getParent();

		if (parent == null) throw new IllegalArgumentException("桌面執行檔缺少安裝目錄");

		Path servicePath = parent.resolve(serviceFileName).normalize();

		if (!parent.equals(servicePath.getParent())) throw new IllegalArgumentException("Service 執行檔逸出安裝目錄");

		return servicePath;
	}

	// 方法：以無命令列機密的獨立程序啟動背景 service，並將 Console 輸出丟棄。
	@Override
	public void run() {
		if (!Files.isRegularFile(serviceExecutable)) {
			throw new IllegalStateException("找不到背景 Service 執行檔");
		}

		ProcessBuilder processBuilder = new ProcessBuilder(serviceExecutable.toString())
			.directory(serviceExecutable.getParent().toFile())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD);

		try {
			// 外部程序：只啟動安裝目錄內固定 launcher，不傳入 Token 或其他客戶設定。
			processBuilder.start();

			// 日誌：記錄背景 service launcher 已建立，不輸出安裝路徑或程序參數。
			log.info("event=packaged_service_process_started");
		}
		catch (IOException exception) {
			throw new IllegalStateException("無法啟動背景 Service", exception);
		}
	}

	//#endregion
}

