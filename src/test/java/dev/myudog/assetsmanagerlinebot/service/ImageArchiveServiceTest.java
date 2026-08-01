package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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
				firstFolder + "/" + date + "-01.jpg",
				firstFolder + "/" + date + "-02.jpg",
				firstFolder + "/" + date + "-03.jpg",
				firstFolder + "/" + date + "-04.jpg"
			);

		List<Asset> secondFolderAssets = assetService.search(sourceId, List.of(secondFolder.toLowerCase()), 10);
		assertThat(secondFolderAssets)
			.extracting(Asset::filePath)
			.containsExactly(secondFolder + "/" + date + "-01.png");
	}

	@Test
	void waitsUntilEveryImageInTheSetHasArrivedWithoutArchivingPartOfIt() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String sourceId = "C-" + suffix;
		String messageId = "message-" + suffix;
		archiveService.stage(
			messageId,
			"set-" + suffix,
			1,
			2,
			"group",
			sourceId,
			"U-" + suffix,
			image("first"),
			"image/jpeg"
		);

		ImageArchiveService.ArchiveResult result =
			archiveService.archive(messageId, sourceId, randomFolder("ZD"));

		assertThat(result.status()).isEqualTo(ImageArchiveService.ArchiveStatus.INCOMPLETE_SET);
		assertThat(result.imageCount()).isEqualTo(1);
		assertThat(result.expectedCount()).isEqualTo(2);
		assertThat(assetService.countBySource(sourceId)).isZero();
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
			.isEqualTo(ImageArchiveService.ArchiveStatus.INCOMPLETE_SET);
		assertThat(result.imageCount()).isEqualTo(1);
		assertThat(result.duplicateCount()).isZero();
		assertThat(result.expectedCount()).isEqualTo(2);
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
		Path directory = fileStorage.resolve(folderName);

		// 外部呼叫：建立既有的第 99 號檔案，驗證下一張會自動擴充為三位數。
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("20260101-99.jpg"), "existing");

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
		assertThat(fileStorage.resolve(folderName + "/" + date + "-100.jpg")).exists();
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
}
