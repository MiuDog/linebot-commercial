package dev.myudog.assetsmanagerlinebot.desktop.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 驗證 JSON Log 增量讀取、檔案 rotation 與可操作錯誤狀態。
 */
class LogTailServiceTest {

	@TempDir
	Path temporaryDirectory;

	// 方法：讀取新增行並在 active Log 被替換後從新檔開頭繼續追蹤。
	@Test
	void shouldContinueReadingAfterLogRotation() throws Exception {
		Path logFile = temporaryDirectory.resolve("application.json");
		DesktopLogBuffer buffer = new DesktopLogBuffer(10);
		LogTailService service = new LogTailService(logFile, buffer);

		Files.writeString(logFile, "{\"level\":\"INFO\",\"message\":\"before\"}\n", StandardCharsets.UTF_8);
		service.poll();
		Files.move(logFile, temporaryDirectory.resolve("application.old.json"));
		Files.writeString(logFile, "{\"level\":\"INFO\",\"message\":\"after\"}\n", StandardCharsets.UTF_8);
		service.poll();

		assertThat(buffer.entries("ALL", ""))
			.extracting(DesktopLogEntry::text)
			.anyMatch(line -> line.contains("before"))
			.anyMatch(line -> line.contains("after"));
	}

	// 方法：Log 尚未建立時提供等待狀態而不是拋出錯誤。
	@Test
	void shouldExposeActionableStatusWhenLogFileDoesNotExistYet() {
		DesktopLogBuffer buffer = new DesktopLogBuffer(10);
		LogTailService service = new LogTailService(
			temporaryDirectory.resolve("application.json"),
			buffer
		);

		service.poll();

		assertThat(buffer.status()).contains("等待");
	}
}
