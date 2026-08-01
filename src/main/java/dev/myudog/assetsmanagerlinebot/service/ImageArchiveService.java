package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.repository.AssetRepository;
import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository.FetchAttempt;
import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository.PendingImage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 【職責】協調 LINE 多圖組從暫存到直接歸檔的完整生命週期。
 *
 * <p><b>圖片事件：</b>
 * {@code LineWebhookController.handleImage → stage
 * → FileStorageService.savePending → PendingImageRepository.insert}。
 *
 * <p><b>歸檔事件：</b>
 * {@code CommandService.archive → archive
 * → PendingImageRepository.findByMessageId／findSet
 * → FileStorageService.archivePending
 * → AssetRepository.insert／linkTag
 * → 清除 .pending 與暫存資料列}。
 *
 * <p>檔案系統不受資料庫交易自動回滾，因此本類別會在資料庫失敗時主動刪除
 * 已建立的檔案；正式歸檔全部成功前不刪除暫存來源，讓操作可以重試。
 */
@Service
public class ImageArchiveService {

	public enum ArchiveStatus { ARCHIVED, NOT_FOUND, WRONG_SOURCE, INCOMPLETE_SET }

	public record ArchiveResult(
		ArchiveStatus status,
		String folderName,
		int imageCount,
		int expectedCount,
		int duplicateCount,
		String firstSequence,
		String lastSequence
	) {}

	private final PendingImageRepository pendingRepository;
	private final AssetRepository assetRepository;
	private final FileStorageService fileStorage;
	private final AssetFileReconciliationService reconciliationService;

	// 方法：初始化 ImageArchiveService。
	public ImageArchiveService(
		PendingImageRepository pendingRepository,
		AssetRepository assetRepository,
		FileStorageService fileStorage,
		AssetFileReconciliationService reconciliationService
	) {
		this.pendingRepository = pendingRepository;
		this.assetRepository = assetRepository;
		this.fileStorage = fileStorage;
		this.reconciliationService = reconciliationService;
	}

	/**
	 * 將一張 LINE 圖片存入待處理區。
	 *
	 * <p>呼叫鏈：
	 * {@code webhook image → LineStorageService.downloadContent → stage
	 * → 去重 → savePending → pending_image}。
	 */
	// 方法：暫存一張 LINE 圖片，並建立待歸檔索引。
	@Transactional
	public void stage(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String sourceType,
		String sourceId,
		String uploaderId,
		InputStream content,
		String contentType
	) throws IOException {
		String normalizedSetId = normalizeSetId(imageSetId, messageId);
		int normalizedIndex = Math.max(1, imageIndex);
		int normalizedTotal = Math.max(1, imageTotal);

		// 步驟 1：先釋放實體檔案已刪除的舊索引，再排除單純 webhook 重送。
		reconciliationService.deleteIfFileMissing(messageId);
		if (pendingRepository.findByMessageId(messageId).isPresent()) {
			// 外部呼叫：關閉不再使用的圖片輸入串流，立即釋放連線資源。
			content.close();
			recordFetchAttempt(
				messageId,
				normalizedSetId,
				normalizedIndex,
				normalizedTotal,
				sourceId,
				"FETCHED"
			);
			return;
		}

		// 步驟 2：將圖片寫入待處理區，取得後續資料庫需要的檔案資訊。
		FileStorageService.StoredFile stored = fileStorage.savePending(content, contentType);

		// 步驟 3：保存待歸檔索引；若資料庫失敗則刪除剛建立的檔案。
		try {
			pendingRepository.insert(new PendingImage(
				messageId,
				normalizedSetId,
				normalizedIndex,
				normalizedTotal,
				sourceType,
				sourceId,
				uploaderId,
				stored.relativePath(),
				stored.contentType(),
				stored.size(),
			// 外部呼叫：使用 Java 時間 API 記錄圖片實際接收時間。
				Instant.now()
			));
			recordFetchAttempt(
				messageId,
				normalizedSetId,
				normalizedIndex,
				normalizedTotal,
				sourceId,
				"FETCHED"
			);
		}
		catch (RuntimeException e) {
			fileStorage.delete(stored.relativePath());
			throw e;
		}
	}

	// 方法：記錄 LINE 圖片內容下載失敗，供後續歸檔指令彙總回報。
	@Transactional
	public void recordFetchFailure(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String sourceId
	) {
		recordFetchAttempt(
			messageId,
			normalizeSetId(imageSetId, messageId),
			Math.max(1, imageIndex),
			Math.max(1, imageTotal),
			sourceId,
			"FAILED"
		);
	}

	/**
	 * 將被引用圖片所屬的完整 imageSet 直接歸檔。
	 */
	// 方法：檢查圖片組完整性，直接歸檔並清除暫存狀態。
	@Transactional
	public synchronized ArchiveResult archive(
		String quotedMessageId,
		String sourceId,
		String folderName
	) throws IOException {
		// 步驟 1：確認被引用圖片存在於同一資料範圍。
		Optional<PendingImage> quoted = pendingRepository.findByMessageId(quotedMessageId);
		Optional<FetchAttempt> quotedAttempt =
			pendingRepository.findFetchAttemptByMessageId(quotedMessageId);
		if (quoted.isEmpty() && quotedAttempt.isEmpty()) {
			Optional<Asset> existing =
				assetRepository.findByMessageId(quotedMessageId);
			if (existing.isPresent()) {
				return archiveExistingAsset(
					existing.get(),
					sourceId,
					folderName
				);
			}
			return new ArchiveResult(
				ArchiveStatus.NOT_FOUND,
				folderName,
				0,
				0,
				0,
				"",
				""
			);
		}

		String quotedSourceId = quoted
			.map(PendingImage::sourceId)
			.orElseGet(() -> quotedAttempt.orElseThrow().sourceId());
		if (!quotedSourceId.equals(sourceId)) {
			return new ArchiveResult(
				ArchiveStatus.WRONG_SOURCE,
				folderName,
				0,
				0,
				0,
				"",
				""
			);
		}

		String imageSetId = quoted
			.map(PendingImage::imageSetId)
			.orElseGet(() -> quotedAttempt.orElseThrow().imageSetId());

		// 步驟 2：確認同一組圖片已完整接收，避免只歸檔部分內容。
		List<PendingImage> images = pendingRepository.findSet(sourceId, imageSetId);
		List<FetchAttempt> attempts =
			pendingRepository.findFetchSet(sourceId, imageSetId);
		int expected = attempts
			.stream()
			.mapToInt(FetchAttempt::imageTotal)
			.max()
			.orElseGet(
				() -> images
					.stream()
					.mapToInt(PendingImage::imageTotal)
					.max()
					.orElse(1)
			);
		int duplicateCount = (int) attempts
			.stream()
			.filter(attempt -> "DUPLICATE".equals(attempt.status()))
			.count();
		if (images.size() != expected) {
			return new ArchiveResult(
				ArchiveStatus.INCOMPLETE_SET,
				folderName,
				images.size(),
				expected,
				duplicateCount,
				"",
				""
			);
		}

		// 步驟 3：逐張搬移圖片、建立資產索引並連結完整資料夾名稱標籤。
		List<String> createdPaths = new ArrayList<>();
		String firstSequence = "";
		String lastSequence = "";
		try {
			long tagId = assetRepository.upsertTag(folderName.toLowerCase());
			for (PendingImage image : images) {
				FileStorageService.StoredFile stored =
					fileStorage
						.archivePending(image.stagingPath(), folderName, image.contentType());
				createdPaths.add(stored.relativePath());
				String sequence = archiveSequence(stored.relativePath());
				if (firstSequence.isEmpty()) firstSequence = sequence;

				lastSequence = sequence;
				Asset asset = new Asset(
					null,
					uniqueAssetMessageId(image.messageId()),
				// 外部呼叫：使用 UUID 產生不可預測的媒體分享權杖。
					UUID.randomUUID().toString().replace("-", ""),
					image.sourceType(),
					image.sourceId(),
					image.uploaderId(),
					stored.relativePath(),
					stored.contentType(),
					stored.size(),
				// 外部呼叫：使用 Java 時間 API 記錄正式歸檔時間。
					Instant.now(),
					List.of()
				);
				Long assetId = assetRepository.insert(asset);
				reconciliationService.register(assetId, stored.relativePath());
				assetRepository.linkTag(assetId, tagId);
			}
		}
		catch (IOException | RuntimeException e) {
			for (String createdPath : createdPaths) {
				try {
					fileStorage.delete(createdPath);
				}
				catch (IOException cleanupError) {
					e.addSuppressed(cleanupError);
				}
			}
			throw e;
		}

		// 步驟 4：全部資產建立成功後，清除整組待處理檔案與資料列。
		for (PendingImage image : images) {
			fileStorage.delete(image.stagingPath());
		}
		pendingRepository.deleteSet(sourceId, imageSetId);
		pendingRepository.deleteFetchSet(sourceId, imageSetId);
		return new ArchiveResult(
			ArchiveStatus.ARCHIVED,
			folderName,
			images.size(),
			expected,
			duplicateCount,
			firstSequence,
			lastSequence
		);
	}

	// 方法：將已歸檔圖片再次複製並建立新的流水號及資產資料。
	private ArchiveResult archiveExistingAsset(
		Asset existing,
		String sourceId,
		String folderName
	) throws IOException {
		if (!java.util.Objects.equals(existing.sourceId(), sourceId)) {
			return new ArchiveResult(
				ArchiveStatus.WRONG_SOURCE,
				folderName,
				0,
				0,
				0,
				"",
				""
			);
		}

		FileStorageService.StoredFile stored = null;
		try {
			stored = fileStorage.archiveExisting(
				existing.filePath(),
				folderName,
				existing.contentType()
			);
			long tagId = assetRepository.upsertTag(folderName.toLowerCase());
			Asset duplicate = new Asset(
				null,
				duplicateMessageId(),
				// 外部呼叫：使用 UUID 產生新的媒體分享權杖。
				UUID.randomUUID().toString().replace("-", ""),
				existing.sourceType(),
				existing.sourceId(),
				existing.uploaderId(),
				stored.relativePath(),
				stored.contentType(),
				stored.size(),
				// 外部呼叫：使用 Java 時間 API 記錄重複歸檔時間。
				Instant.now(),
				List.of()
			);
			Long assetId = assetRepository.insert(duplicate);
			reconciliationService.register(assetId, stored.relativePath());
			assetRepository.linkTag(assetId, tagId);
		}
		catch (IOException | RuntimeException e) {
			if (stored != null) {
				try {
					fileStorage.delete(stored.relativePath());
				}
				catch (IOException cleanupError) {
					e.addSuppressed(cleanupError);
				}
			}
			throw e;
		}

		String sequence = archiveSequence(stored.relativePath());
		return new ArchiveResult(
			ArchiveStatus.ARCHIVED,
			folderName,
			1,
			1,
			0,
			sequence,
			sequence
		);
	}

	// 方法：保留首次 LINE 訊息編號，重複歸檔時改用獨立內部編號。
	private String uniqueAssetMessageId(String messageId) {
		if (assetRepository.findByMessageId(messageId).isEmpty()) return messageId;

		return duplicateMessageId();
	}

	// 方法：產生重複歸檔資產專用的唯一內部訊息編號。
	private static String duplicateMessageId() {
		// 外部呼叫：使用 UUID 避免重複歸檔資料互相衝突。
		return "line-duplicate:" + UUID.randomUUID();
	}

	// 方法：保存單張圖片的抓取狀態。
	private void recordFetchAttempt(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String sourceId,
		String status
	) {
		pendingRepository.upsertFetchAttempt(
			new FetchAttempt(
				sourceId,
				imageSetId,
				imageIndex,
				imageTotal,
				messageId,
				status,
				// 外部呼叫：使用 Java 時間 API 記錄抓取狀態更新時間。
				Instant.now()
			)
		);
	}

	// 方法：未提供 LINE imageSet 編號時，以訊息編號建立單張圖片組。
	private static String normalizeSetId(
		String imageSetId,
		String messageId
	) {
		return imageSetId == null || imageSetId.isBlank()
			? messageId
			: imageSetId;
	}

	// 方法：從正式歸檔檔名取出保留前導零的流水號。
	private static String archiveSequence(String relativePath) {
		int separator = relativePath.lastIndexOf('-');
		int extension = relativePath.lastIndexOf('.');
		if (separator < 0 || extension <= separator + 1) {
			throw new IllegalStateException("無法從歸檔路徑取得流水號: " + relativePath);
		}
		return relativePath.substring(separator + 1, extension);
	}
}
