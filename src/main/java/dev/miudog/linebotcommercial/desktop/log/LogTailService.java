package dev.miudog.linebotcommercial.desktop.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量追蹤目前 application.json，並在 rolling file 替換後接續新檔。
 */
public final class LogTailService implements AutoCloseable {

	//#region 欄位

	private static final Logger log = LoggerFactory.getLogger(LogTailService.class);

	private final Path logFile;
	private final DesktopLogBuffer buffer;
	private Object fileKey;
	private long offset;
	private String partialLine;
	private volatile boolean running;
	private Thread pollingThread;

	//#endregion

	//#region 建構子

	// 方法：建立指定 active JSON Log 與固定容量 buffer 的追蹤服務。
	public LogTailService(
		Path logFile,
		DesktopLogBuffer buffer
	) {
		this.logFile = Objects.requireNonNull(logFile, "Log 檔案不可為 null");
		this.buffer = Objects.requireNonNull(buffer, "Log buffer 不可為 null");
		this.partialLine = "";
	}

	//#endregion

	//#region 方法

	// 方法：啟動固定間隔的 daemon 輪詢，不阻止桌面 App 受控結束。
	public synchronized void start(Duration interval) {
		Objects.requireNonNull(interval, "Log 輪詢間隔不可為 null");

		if (running) return;

		if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Log 輪詢間隔必須大於零");

		running = true;

		// 外部函式：建立 daemon 執行緒持續追蹤 Log，結束時可由 close 中斷。
		pollingThread = Thread.ofPlatform().daemon().name("desktop-log-tail").start(() -> pollLoop(interval));
	}

	// 方法：執行一次增量讀取；檔案不存在時保持等待狀態。
	public synchronized void poll() {
		if (!Files.exists(logFile)) {
			fileKey = null;
			offset = 0;
			partialLine = "";
			buffer.updateStatus("等待 Log 檔案建立");
			return;
		}

		try {
			BasicFileAttributes attributes = Files.readAttributes(logFile, BasicFileAttributes.class);
			Object currentFileKey = attributes.fileKey();

			// rotation 或檔案截短後，從新 active Log 的開頭重新讀取。
			if (!Objects.equals(fileKey, currentFileKey) || attributes.size() < offset) {
				fileKey = currentFileKey;
				offset = 0;
				partialLine = "";
			}

			readNewBytes();
		}
		catch (IOException exception) {
			buffer.updateStatus("無法讀取 Log，請確認檔案權限或開啟 Log 資料夾");

			// 日誌：記錄 Log tail 讀取錯誤類型與檔案位置，不重複輸出檔案內容。
			log.warn("event=desktop_log_tail_failed file={} errorType={}",
				logFile,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：停止輪詢執行緒並釋放背景追蹤資源。
	@Override
	public synchronized void close() {
		running = false;

		if (pollingThread == null) return;

		pollingThread.interrupt();
		pollingThread = null;
	}

	// 方法：從上次 byte offset 讀至檔尾，並保留尚未換行的片段。
	private void readNewBytes() throws IOException {
		try (SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ);
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			channel.position(offset);
			ByteBuffer chunk = ByteBuffer.allocate(8192);
			int read;

			while ((read = channel.read(chunk)) > 0) {
				output.write(chunk.array(), 0, read);
				chunk.clear();
			}

			offset = channel.position();
			appendText(output.toString(StandardCharsets.UTF_8));
		}
	}

	// 方法：按換行切分完整 JSON Log，最後不完整片段留待下次讀取。
	private void appendText(String addedText) {
		if (addedText.isEmpty()) return;

		String combined = partialLine + addedText;
		String[] lines = combined.split("\\R", -1);

		for (int index = 0; index < lines.length - 1; index++) {
			buffer.add(lines[index]);
		}

		partialLine = lines[lines.length - 1];
	}

	// 方法：持續輪詢 active Log，受中斷時正常結束。
	private void pollLoop(Duration interval) {
		while (running) {
			poll();

			try {
				// 外部函式：依設定間隔暫停背景輪詢，避免持續占用磁碟與 CPU。
				Thread.sleep(interval);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	//#endregion
}
