package dev.myudog.assetsmanagerlinebot.service;

import dev.myudog.assetsmanagerlinebot.domain.Asset;
import dev.myudog.assetsmanagerlinebot.repository.AssetRepository;
import dev.myudog.assetsmanagerlinebot.repository.AssetRepository.FileIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 將圖片根目錄的實際狀態與 SQLite 索引保持一致。
 */
@Service
public class AssetFileReconciliationService {

	private static final Logger log =
		LoggerFactory.getLogger(AssetFileReconciliationService.class);
	private static final Set<String> IMAGE_EXTENSIONS =
		Set.of("jpg", "jpeg", "png", "gif", "webp");

	private final AssetRepository assetRepository;
	private final FileStorageService fileStorage;

	public record SyncResult(
		int updated,
		int deleted,
		int localized,
		int imported,
		int ambiguous
	) {}

	private record DiskFile(
		Path absolutePath,
		String relativePath,
		String fileKey,
		long fileSize,
		long lastModified
	) {}

	// 方法：建立檔案同步服務。
	public AssetFileReconciliationService(
		AssetRepository assetRepository,
		FileStorageService fileStorage
	) {
		this.assetRepository = assetRepository;
		this.fileStorage = fileStorage;
	}

	// 方法：在 LINE 圖片寫入後保存其檔案身分。
	public void register(long assetId, String relativePath) throws IOException {
		Path path = fileStorage.resolve(relativePath);

		// 外部 API：讀取檔案大小、修改時間與作業系統檔案識別碼。
		BasicFileAttributes attributes =
			Files.readAttributes(
				path,
				BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS
			);
		if (!attributes.isRegularFile()) {
			throw new IOException("資產路徑不是一般檔案：" + relativePath);
		}

		DiskFile file = diskFile(path, relativePath, attributes);
		upsertIdentity(assetId, file, new HashMap<>());
	}

	// 方法：在再次接收相同 LINE 訊息前立即清除實體檔案已刪除的舊索引。
	public synchronized void deleteIfFileMissing(String messageId) {
		Optional<Asset> existing = assetRepository.findByMessageId(messageId);
		if (existing.isEmpty()) return;

		Path path = fileStorage.resolve(existing.get().filePath());

		// 外部 API：直接確認實體檔案是否仍存在，避免等待下一次同步。
		if (Files.isRegularFile(path)) return;

		assetRepository.delete(existing.get().id());
	}

	// 方法：同步 Explorer 對圖片的刪除、編輯、移動、改名與外部加入。
	public synchronized SyncResult synchronize() throws IOException {
		List<Asset> assets = assetRepository.findAll();
		Map<Long, FileIdentity> identities = assetRepository.findFileIdentities();
		List<DiskFile> diskFiles = scanDiskFiles();
		Map<String, DiskFile> filesByPath = new HashMap<>();
		diskFiles.forEach(file -> filesByPath.put(file.relativePath(), file));

		Set<String> managedPaths = new HashSet<>();
		assets.forEach(asset -> managedPaths.add(asset.filePath()));
		List<DiskFile> unclaimed = diskFiles
			.stream()
			.filter(file -> !managedPaths.contains(file.relativePath()))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		Map<Path, String> hashCache = new HashMap<>();

		int updated = 0;
		int deleted = 0;
		int localized = 0;
		int imported = 0;
		int ambiguous = 0;

		// 步驟 1：同步資料庫中已存在的圖片。
		for (Asset asset : assets) {
			DiskFile current = filesByPath.get(asset.filePath());
			FileIdentity identity = identities.get(asset.id());
			if (current != null) {
				if (refreshExistingAsset(asset, current, identity, hashCache)) {
					localized++;
				}
				continue;
			}

			List<DiskFile> candidates =
				findCandidates(identity, unclaimed, hashCache);
			if (candidates.size() == 1) {
				DiskFile moved = candidates.get(0);
				updateMovedAsset(asset, moved, hashCache);
				unclaimed.remove(moved);
				updated++;
				continue;
			}
			if (candidates.size() > 1) {
				unclaimed.removeAll(candidates);
				ambiguous++;

				// 日誌：候選圖片不唯一時保留資料，等待使用者調整。
				log.warn(
					"event=asset_file_sync_ambiguous assetId={} candidateCount={}",
					asset.id(),
					candidates.size()
				);
				continue;
			}

			assetRepository.delete(asset.id());
			deleted++;
		}

		// 步驟 2：將尚未受管理的圖片登記為本地來源。
		for (DiskFile file : unclaimed) {
			importLocalAsset(file, hashCache);
			imported++;
		}

		return new SyncResult(
			updated,
			deleted,
			localized,
			imported,
			ambiguous
		);
	}

	// 方法：更新原路徑圖片的中繼資料，內容改變時轉為本地來源。
	private boolean refreshExistingAsset(
		Asset asset,
		DiskFile file,
		FileIdentity identity,
		Map<Path, String> hashCache
	) throws IOException {
		boolean metadataChanged =
			asset.fileSize() == null
			|| asset.fileSize() != file.fileSize()
			|| !contentType(file.relativePath()).equals(asset.contentType());
		if (metadataChanged) {
			assetRepository.updateFile(
				asset.id(),
				file.relativePath(),
				contentType(file.relativePath()),
				file.fileSize()
			);
		}

		boolean identityChanged =
			identity == null
			|| identity.fileSize() != file.fileSize()
			|| identity.lastModified() != file.lastModified()
			|| !java.util.Objects.equals(identity.fileKey(), file.fileKey());
		if (!identityChanged) return false;

		String currentHash = hash(file.absolutePath(), hashCache);
		boolean contentChanged =
			identity != null
			&& !identity.contentHash().equals(currentHash);
		boolean localized = contentChanged && !"local".equals(asset.sourceType());
		if (localized) {
			assetRepository.markAsLocal(asset.id(), localMessageId());
		}
		upsertIdentity(asset.id(), file, hashCache);
		return localized;
	}

	// 方法：尋找與舊檔案身分相同的移動或改名候選圖片。
	private List<DiskFile> findCandidates(
		FileIdentity identity,
		List<DiskFile> unclaimed,
		Map<Path, String> hashCache
	) throws IOException {
		if (identity == null) return List.of();

		if (identity.fileKey() != null) {
			List<DiskFile> byFileKey = unclaimed
				.stream()
				.filter(file -> identity.fileKey().equals(file.fileKey()))
				.toList();
			if (!byFileKey.isEmpty()) return byFileKey;
		}

		List<DiskFile> byHash = new ArrayList<>();
		for (DiskFile file : unclaimed) {
			if (file.fileSize() != identity.fileSize()) continue;

			if (identity.contentHash().equals(hash(file.absolutePath(), hashCache))) {
				byHash.add(file);
			}
		}
		return byHash;
	}

	// 方法：更新被移動或改名圖片的資料庫路徑與身分。
	private void updateMovedAsset(
		Asset asset,
		DiskFile moved,
		Map<Path, String> hashCache
	) throws IOException {
		assetRepository.updateFile(
			asset.id(),
			moved.relativePath(),
			contentType(moved.relativePath()),
			moved.fileSize()
		);
		upsertIdentity(asset.id(), moved, hashCache);
	}

	// 方法：為外部放入的圖片建立本地來源索引。
	private void importLocalAsset(
		DiskFile file,
		Map<Path, String> hashCache
	) throws IOException {
		// 外部 API：產生不與 LINE 訊息衝突的本地識別碼及分享代碼。
		String messageId = localMessageId();
		String shareToken = UUID.randomUUID().toString().replace("-", "");
		Asset asset = new Asset(
			null,
			messageId,
			shareToken,
			"local",
			null,
			null,
			file.relativePath(),
			contentType(file.relativePath()),
			file.fileSize(),
			Instant.now(),
			List.of()
		);
		Long assetId = assetRepository.insert(asset);
		if (assetId == null) throw new IOException("無法建立本地圖片索引");

		upsertIdentity(assetId, file, hashCache);
	}

	// 方法：新增或更新檔案內容身分。
	private void upsertIdentity(
		long assetId,
		DiskFile file,
		Map<Path, String> hashCache
	) throws IOException {
		assetRepository.upsertFileIdentity(
			new FileIdentity(
				assetId,
				file.fileKey(),
				hash(file.absolutePath(), hashCache),
				file.fileSize(),
				file.lastModified()
			)
		);
	}

	// 方法：掃描圖片根目錄內可管理的實體圖片。
	private List<DiskFile> scanDiskFiles() throws IOException {
		Path root = fileStorage.root();
		List<DiskFile> files = new ArrayList<>();

		// 外部 API：遞迴走訪圖片根目錄。
		try (var paths = Files.walk(root)) {
			for (Path path : paths.toList()) {
				if (path.equals(root) || isExcluded(root, path)) continue;

				// 外部 API：讀取檔案屬性以建立同步快照。
				BasicFileAttributes attributes =
					Files.readAttributes(
						path,
						BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS
					);
				if (!attributes.isRegularFile() || !isImage(path)) continue;

				String relativePath = root
					.relativize(path)
					.toString()
					.replace('\\', '/');
				files.add(diskFile(path, relativePath, attributes));
			}
		}
		return files;
	}

	// 方法：將檔案屬性轉為同步使用的磁碟圖片資料。
	private static DiskFile diskFile(
		Path path,
		String relativePath,
		BasicFileAttributes attributes
	) {
		return new DiskFile(
			path,
			relativePath,
			attributes.fileKey() == null
				? null
				: attributes.fileKey().toString(),
			attributes.size(),
			attributes.lastModifiedTime().toMillis()
		);
	}

	// 方法：排除暫存目錄及資料庫檔案。
	private static boolean isExcluded(Path root, Path path) {
		Path relative = root.relativize(path);
		if (relative.getNameCount() == 0) return true;

		String first = relative.getName(0).toString();
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return ".pending".equals(first)
			|| fileName.equals("assets.db")
			|| fileName.startsWith("assets.db-");
	}

	// 方法：判斷檔案副檔名是否為支援的圖片格式。
	private static boolean isImage(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) return false;

		return IMAGE_EXTENSIONS.contains(
			name.substring(dot + 1).toLowerCase(Locale.ROOT)
		);
	}

	// 方法：依副檔名推導圖片 MIME 類型。
	private static String contentType(String relativePath) {
		String lower = relativePath.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".png")) return "image/png";

		if (lower.endsWith(".gif")) return "image/gif";

		if (lower.endsWith(".webp")) return "image/webp";

		return "image/jpeg";
	}

	// 方法：計算檔案 SHA-256 並重用本輪同步快取。
	private static String hash(
		Path path,
		Map<Path, String> cache
	) throws IOException {
		String existing = cache.get(path);
		if (existing != null) return existing;

		MessageDigest digest;
		try {
			// 外部 API：取得 Java 內建 SHA-256 摘要演算法。
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("執行環境不支援 SHA-256", e);
		}

		// 外部 API：串流讀取檔案，避免大圖片一次載入記憶體。
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count > 0) digest.update(buffer, 0, count);
			}
		}

		String value = java.util.HexFormat.of().formatHex(digest.digest());
		cache.put(path, value);
		return value;
	}

	// 方法：產生僅供本地來源使用的唯一訊息編號。
	private static String localMessageId() {
		// 外部 API：以 UUID 避免本地圖片識別碼碰撞。
		return "local:" + UUID.randomUUID();
	}
}
