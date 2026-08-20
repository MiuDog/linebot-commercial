package dev.miudog.linebotcommercial.service;

import dev.miudog.linebotcommercial.domain.Asset;
import dev.miudog.linebotcommercial.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(
	properties =
	{"app.storage.root=${java.io.tmpdir}/assets-manager-reconciliation-test",
		"spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/assets-manager-reconciliation-test/test.db",
		"app.storage.sync-enabled=false"}
)
class AssetFileReconciliationServiceTest {

	@Autowired
	AssetService assetService;

	@Autowired
	AssetRepository assetRepository;

	@Autowired
	FileStorageService fileStorage;

	@Autowired
	AssetFileReconciliationService reconciliationService;

	@Test
	void updatesTheDatabaseAfterExplorerMovesOrRenamesAnAsset() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String messageId = "message-" + suffix;
		String sourceId = "source-" + suffix;
		Asset original = ingest(messageId, sourceId, "same-image", "image/jpeg");
		assetService.tag(messageId, List.of("ZD12345", "測試標籤"));
		reconciliationService.synchronize();

		Path moved = fileStorage.resolve("移動資料夾-" + suffix + "/重新命名.jpg");

		// 模擬使用者透過 Explorer 將圖片移動並重新命名。
		Files.createDirectories(moved.getParent());
		Files.move(
			fileStorage.resolve(original.filePath()),
			moved,
			StandardCopyOption.ATOMIC_MOVE
		);

		reconciliationService.synchronize();

		Asset updated = assetService.findByMessageId(messageId).orElseThrow();
		assertThat(updated.filePath()).isEqualTo("移動資料夾-" + suffix + "/重新命名.jpg");
		assertThat(updated.tags()).containsExactlyInAnyOrder("ZD12345", "測試標籤");
	}

	@Test
	void deletesTheDatabaseAssetAndAllowsTheSameLineMessageToBeDownloadedAgain() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String messageId = "message-" + suffix;
		String sourceId = "source-" + suffix;
		Asset original = ingest(messageId, sourceId, "deleted-image", "image/png");
		reconciliationService.synchronize();

		// 模擬使用者透過 Explorer 永久刪除圖片。
		Files.delete(fileStorage.resolve(original.filePath()));

		reconciliationService.synchronize();

		assertThat(assetService.findByMessageId(messageId)).isEmpty();

		Asset redownloaded = ingest(
			messageId,
			sourceId,
			"downloaded-again",
			"image/png"
		);
		assertThat(redownloaded.messageId()).isEqualTo(messageId);
	}

	@Test
	void marksEditedLineImagesAsLocalAndReleasesTheirOriginalMessageIds() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String messageId = "message-" + suffix;
		String sourceId = "source-" + suffix;
		Asset original = ingest(messageId, sourceId, "original-image", "image/jpeg");
		assetService.tag(messageId, List.of("ZD12345"));
		reconciliationService.synchronize();

		// 模擬使用者直接修改已歸檔圖片的內容。
		Files.writeString(fileStorage.resolve(original.filePath()), "locally-edited-image");

		reconciliationService.synchronize();

		Asset local = assetRepository
			.findAll()
			.stream()
			.filter(asset -> asset.filePath().equals(original.filePath()))
			.findFirst()
			.orElseThrow();
		assertThat(local.sourceType()).isEqualTo("local");
		assertThat(local.messageId()).startsWith("local:");
		assertThat(assetRepository.findTagNames(local.id())).containsExactly("ZD12345");
		assertThat(assetService.findByMessageId(messageId)).isEmpty();

		Asset redownloaded = ingest(messageId, sourceId, "original-image", "image/jpeg");
		assertThat(redownloaded.messageId()).isEqualTo(messageId);
	}

	@Test
	void registersImagesPlacedDirectlyInTheAssetRootAsLocal() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String relativePath = "外部圖片-" + suffix + "/uploaded.jpg";
		Path external = fileStorage.resolve(relativePath);

		// 模擬使用者透過 Explorer 放入一張系統原本不知道的圖片。
		Files.createDirectories(external.getParent());
		Files.writeString(external, "external-image");

		reconciliationService.synchronize();

		Asset local = assetRepository
			.findAll()
			.stream()
			.filter(asset -> asset.filePath().equals(relativePath))
			.findFirst()
			.orElseThrow();
		assertThat(local.sourceType()).isEqualTo("local");
		assertThat(local.sourceId()).isNull();
		assertThat(local.messageId()).startsWith("local:");
		assertThat(assetRepository.findFileIdentities()).containsKey(local.id());
	}

	private Asset ingest(
		String messageId,
		String sourceId,
		String content,
		String contentType
	) throws Exception {
		return assetService
			.ingest(
				messageId,
				"group",
				sourceId,
				"user-" + messageId,
				new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
				contentType
			)
			.orElseThrow();
	}
}
