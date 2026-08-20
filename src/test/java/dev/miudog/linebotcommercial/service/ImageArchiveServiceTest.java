package dev.miudog.linebotcommercial.service;

import dev.miudog.linebotcommercial.domain.Asset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
	properties =
	{"app.storage.root=${java.io.tmpdir}/assets-manager-archive-test",
		"spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/assets-manager-archive-test/test.db"}
)
class ImageArchiveServiceTest {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

	@Autowired
	ImageArchiveService archiveService;

	@Autowired
	AssetService assetService;

	@Autowired
	FileStorageService fileStorage;

	@Test
	void exposesAnImageSetOnlyAfterEveryExpectedPositionWasStaged() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "U-" + suffix;
		String imageSetId = "SET-" + suffix;
		String firstMessageId = "M1-" + suffix;
		String secondMessageId = "M2-" + suffix;
		archiveService.stage(
			firstMessageId,
			imageSetId,
			1,
			2,
			"user",
			sourceId,
			sourceId,
			image("first"),
			"image/jpeg"
		);

		assertThat(archiveService.completedSetMessageIds(sourceId, imageSetId, firstMessageId, 2)).isEmpty();

		archiveService.stage(
			secondMessageId,
			imageSetId,
			2,
			2,
			"user",
			sourceId,
			sourceId,
			image("second"),
			"image/jpeg"
		);

		assertThat(archiveService.completedSetMessageIds(sourceId, imageSetId, secondMessageId, 2))
			.containsExactly(firstMessageId, secondMessageId);
	}

	@Test
	void archivesEveryImageImmediatelyAndAssignsFolderSpecificSequences() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String requesterId = "U-" + suffix;
		String firstFolder = randomFolder("ZD");
		String secondFolder = randomFolder("YJ");
		String date = DAY.format(ZonedDateTime.now(TAIPEI));

		stageSet(sourceId, requesterId, "set-1-" + suffix, "message-1-" + suffix, "message-2-" + suffix);
		ImageArchiveService.ArchiveResult first =
			archiveService.archive("message-1-" + suffix, sourceId, firstFolder);

		stageSet(sourceId, requesterId, "set-2-" + suffix, "message-3-" + suffix, "message-4-" + suffix);
		ImageArchiveService.ArchiveResult second =
			archiveService.archive("message-3-" + suffix, sourceId, firstFolder);

		archiveService.stage(
			"message-5-" + suffix,
			"set-3-" + suffix,
			1,
			1,
			"group",
			sourceId,
			requesterId,
			image("fifth"),
			"image/png"
		);
		ImageArchiveService.ArchiveResult other =
			archiveService.archive("message-5-" + suffix, sourceId, secondFolder);

		assertThat(first.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(first.imageCount()).isEqualTo(2);
		assertThat(first.firstSequence()).isEqualTo("01");
		assertThat(first.lastSequence()).isEqualTo("02");
		assertThat(second.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(second.firstSequence()).isEqualTo("03");
		assertThat(second.lastSequence()).isEqualTo("04");
		assertThat(other.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(other.firstSequence()).isEqualTo("01");
		assertThat(other.lastSequence()).isEqualTo("01");

		List<Asset> firstFolderAssets = assetService.search(sourceId, List.of(firstFolder.toLowerCase()), 10);
		assertThat(firstFolderAssets)
			.extracting(Asset::filePath)
			.containsExactlyInAnyOrder(
				firstFolder + "/" + date + "/" + date + "-01.jpg",
				firstFolder + "/" + date + "/" + date + "-02.jpg",
				firstFolder + "/" + date + "/" + date + "-03.jpg",
				firstFolder + "/" + date + "/" + date + "-04.jpg"
			);

		List<Asset> secondFolderAssets = assetService.search(sourceId, List.of(secondFolder.toLowerCase()), 10);
		assertThat(secondFolderAssets)
			.extracting(Asset::filePath)
			.containsExactly(secondFolder + "/" + date + "/" + date + "-01.png");
	}

	@Test
	void archivesAvailableImagesAndReportsMissingCountWhenTheSetIsIncomplete(
		CapturedOutput output
	) throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String firstMessageId = "message-1-" + suffix;
		String secondMessageId = "message-2-" + suffix;
		String imageSetId = "set-" + suffix;
		String folderName = randomFolder("ZD");
		archiveService.stage(
			firstMessageId,
			imageSetId,
			1,
			3,
			"group",
			sourceId,
			"U-" + suffix,
			image("first"),
			"image/jpeg"
		);
		archiveService.stage(
			secondMessageId,
			imageSetId,
			2,
			3,
			"group",
			sourceId,
			"U-" + suffix,
			image("second"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(firstMessageId, sourceId, folderName);

		assertThat(result.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(result.imageCount()).isEqualTo(2);
		assertThat(result.expectedCount()).isEqualTo(3);
		assertThat(assetService.countBySource(sourceId)).isEqualTo(2);
		assertThat(output).contains(
			"event=image_archive_set_incomplete",
			"imageSetId=" + imageSetId,
			"expectedCount=3",
			"fetchedCount=2",
			"fetchedIndexes=[1, 2]"
		);
	}

	@Test
	void archivesEveryImageEvenWhenOneWasAlreadyStored() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String duplicateMessageId = "duplicate-" + suffix;
		String fetchedMessageId = "fetched-" + suffix;

		archiveService.stage(
			duplicateMessageId,
			"old-set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("already-archived"),
			"image/jpeg"
		);
		archiveService.archive(
			duplicateMessageId,
			sourceId,
			randomFolder("ZD")
		);

		archiveService.stage(
			duplicateMessageId,
			"new-set-" + suffix,
			1,
			2,
			"group",
			sourceId,
			"U-" + suffix,
			image("duplicate"),
			"image/jpeg"
		);
		archiveService.stage(
			fetchedMessageId,
			"new-set-" + suffix,
			2,
			2,
			"group",
			sourceId,
			"U-" + suffix,
			image("fetched"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(
				duplicateMessageId,
				sourceId,
				randomFolder("ZD")
			);

		assertThat(result.status())
			.isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(result.imageCount()).isEqualTo(2);
		assertThat(result.duplicateCount()).isZero();
		assertThat(result.expectedCount()).isEqualTo(2);
	}

	@Test
	void reportsSuccessfulDownloadsEvenWhenAnotherImageDownloadFailed() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String failedMessageId = "failed-" + suffix;

		archiveService.recordFetchFailure(
			failedMessageId,
			"set-" + suffix,
			1,
			2,
			sourceId
		);
		archiveService.stage(
			"fetched-" + suffix,
			"set-" + suffix,
			2,
			2,
			"group",
			sourceId,
			"U-" + suffix,
			image("fetched"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(
				failedMessageId,
				sourceId,
				randomFolder("ZD")
			);

		assertThat(result.status())
			.isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(result.imageCount()).isEqualTo(1);
		assertThat(result.duplicateCount()).isZero();
		assertThat(result.expectedCount()).isEqualTo(2);
	}

	@Test
	void reportsDetailedIndexesWhenNoImageCouldBeArchived(CapturedOutput output) throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String messageId = "failed-" + suffix;
		String imageSetId = "set-" + suffix;
		archiveService.recordFetchFailure(
			messageId,
			imageSetId,
			1,
			2,
			sourceId
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(messageId, sourceId, randomFolder("ZD"));

		assertThat(result.status()).isEqualTo(ImageArchiveService.ArchiveStatus.INCOMPLETE_SET);
		assertThat(result.imageCount()).isZero();
		assertThat(result.expectedCount()).isEqualTo(2);
		assertThat(assetService.countBySource(sourceId)).isZero();
		JsonNode incompleteEvent = structuredEvent(output, "image_archive_set_incomplete");
		assertThat(output).contains("event=line_image_fetch_failed");
		assertThat(incompleteEvent).isNotNull();
		assertThat(kvp(incompleteEvent, "imageSetId")).isEqualTo(imageSetId);
		assertThat(kvp(incompleteEvent, "fetchedIndexes")).isEqualTo("[]");
		assertThat(kvp(incompleteEvent, "fetchAttempts")).isEqualTo("[1:FAILED/2]");
	}

	@Test
	void immediatelyDownloadsTheSameLineMessageAgainAfterItsFileWasDeleted() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String messageId = "message-" + suffix;

		archiveService.stage(
			messageId,
			"first-set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("first"),
			"image/jpeg"
		);
		archiveService.archive(messageId, sourceId, randomFolder("ZD"));
		Asset first = assetService.findByMessageId(messageId).orElseThrow();

		// 外部呼叫：模擬使用者在 Explorer 直接刪除已歸檔圖片。
		Files.delete(fileStorage.resolve(first.filePath()));
		archiveService.stage(
			messageId,
			"second-set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("downloaded-again"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(messageId, sourceId, randomFolder("ZD"));

		assertThat(result.status())
			.isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(assetService.findByMessageId(messageId)).isPresent();
	}

	@Test
	void archivesAnAlreadyStoredImageAgainWhenItsCommandIsRepeated() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String messageId = "message-" + suffix;
		String firstFolder = randomFolder("ZD");
		String secondFolder = randomFolder("YJ");

		archiveService.stage(
			messageId,
			"set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("same-image"),
			"image/jpeg"
		);
		archiveService.archive(messageId, sourceId, firstFolder);

		ImageArchiveService.ArchiveResult repeated =
			archiveService.archive(messageId, sourceId, secondFolder);

		assertThat(repeated.status())
			.isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(repeated.imageCount()).isEqualTo(1);
		assertThat(assetService.search(
			sourceId,
			List.of(secondFolder.toLowerCase()),
			10
		)).hasSize(1);
	}

	@Test
	void expandsTheFolderSequenceToThreeDigitsAfterNinetyNine() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String folderName = randomFolder("ZD");
		String date = DAY.format(ZonedDateTime.now(TAIPEI));
		Path directory = fileStorage.resolve(folderName + "/" + date);

		// 外部呼叫：建立既有的第 99 號檔案，驗證下一張會自動擴充為三位數。
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(date + "-99.jpg"), "existing");

		String messageId = "message-" + suffix;
		archiveService.stage(
			messageId,
			"set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("next"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(messageId, sourceId, folderName);

		assertThat(result.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(result.firstSequence()).isEqualTo("100");
		assertThat(result.lastSequence()).isEqualTo("100");
		assertThat(fileStorage.resolve(folderName + "/" + date + "/" + date + "-100.jpg")).exists();
	}

	@Test
	void resetsTheFolderSequenceForEachDate() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String folderName = randomFolder("ZD");
		String date = DAY.format(ZonedDateTime.now(TAIPEI));
		String previousDate = DAY.format(ZonedDateTime.now(TAIPEI).minusDays(1));
		Path previousDirectory = fileStorage.resolve(folderName + "/" + previousDate);

		// 前一天即使已有高流水號，也不得影響今天的獨立計數。
		Files.createDirectories(previousDirectory);
		Files.writeString(previousDirectory.resolve(previousDate + "-99.jpg"), "existing");

		String messageId = "message-" + suffix;
		archiveService.stage(
			messageId,
			"set-" + suffix,
			1,
			1,
			"group",
			sourceId,
			"U-" + suffix,
			image("today"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(messageId, sourceId, folderName);

		assertThat(result.status()).isEqualTo(ImageArchiveService.ArchiveStatus.ARCHIVED);
		assertThat(result.firstSequence()).isEqualTo("01");
		assertThat(result.lastSequence()).isEqualTo("01");
		assertThat(fileStorage.resolve(folderName + "/" + date + "/" + date + "-01.jpg")).exists();
	}

	private void stageSet(
		String sourceId,
		String requesterId,
		String imageSetId,
		String firstMessageId,
		String secondMessageId
	) throws Exception {
		// LINE 不保證 webhook 順序，因此刻意先暫存第二張圖片。
		archiveService.stage(
			secondMessageId,
			imageSetId,
			2,
			2,
			"group",
			sourceId,
			requesterId,
			image("second"),
			"image/jpeg"
		);
		archiveService.stage(
			firstMessageId,
			imageSetId,
			1,
			2,
			"group",
			sourceId,
			requesterId,
			image("first"),
			"image/jpeg"
		);
	}

	private static String randomFolder(String prefix) {
		int value = Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000);
		return prefix + String.format(prefix.equals("ZD") ? "%05d" : "%06d", value);
	}

	private static ByteArrayInputStream image(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	// 方法：從 Spring 測試的 JSON Lines 輸出尋找指定 structured event。
	private static JsonNode structuredEvent(CapturedOutput output, String eventName) {
		ObjectMapper mapper = new ObjectMapper();
		return output.getOut()
			.lines()
			.filter(line -> line.startsWith("{"))
			.map(line -> parseJson(mapper, line))
			.filter(node -> node != null && eventName.equals(kvp(node, "event")))
			.findFirst()
			.orElse(null);
	}

	// 方法：安全解析單行 JSON，略過測試框架的非 JSON 訊息。
	private static JsonNode parseJson(ObjectMapper mapper, String line) {
		try {
			return mapper.readTree(line);
		}
		catch (Exception exception) {
			return null;
		}
	}

	// 方法：讀取 Logback JsonEncoder 產生的單鍵 kvpList 欄位。
	private static String kvp(JsonNode event, String fieldName) {
		for (JsonNode field : event.path("kvpList")) {
			JsonNode value = field.get(fieldName);
			if (value != null) return value.asString();
		}
		return null;
	}
}
