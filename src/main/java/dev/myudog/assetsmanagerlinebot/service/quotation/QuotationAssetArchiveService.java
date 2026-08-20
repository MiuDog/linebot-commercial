package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import java.io.InputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 將已確認草稿的全部候選原圖搬入正式報價資料夾，並同步建立資產關聯。
 */
@Service
public class QuotationAssetArchiveService {

	private static final String ARCHIVE_FAILED = "ARCHIVE_FAILED";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final JdbcTemplate jdbc;
	private final FileStorageService storage;
	private final QuotationOutputDirectoryService outputDirectories;

	// 方法：建立正式報價圖片歸檔服務。
	public QuotationAssetArchiveService(
		JdbcTemplate jdbc,
		FileStorageService storage,
		QuotationOutputDirectoryService outputDirectories
	) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.outputDirectories = outputDirectories;
	}

	// 方法：預檢後搬移全部候選原圖，資料庫失敗時將實體檔案搬回暫存區。
	@Transactional
	public QuotationArchivedAssets archive(QuotationConfirmationResult confirmation) {
		validateConfirmation(confirmation);
		QuotationRecord quotation = requireArchivableQuotation(confirmation);
		List<Candidate> candidates = loadCandidates(quotation.draftId(), confirmation.quotationId());
		if (candidates.isEmpty()) return new QuotationArchivedAssets(List.of(), null);

		Path directory = outputDirectories.resolveFormalDirectory(confirmation.folderName());
		if (candidates.stream().allMatch(Candidate::alreadyArchived)) {
			cleanupCommittedJournals(candidates, directory);
			return existingResult(candidates);
		}

		preflight(candidates, directory);
		List<MoveOperation> moved = new ArrayList<>();
		List<Path> journals = new ArrayList<>();
		boolean directoryExisted = false;
		try {
			directoryExisted = Files.isDirectory(directory);
			// 檔案系統：所有來源通過預檢後，才建立 YYYYMMDD-XX 正式報價資料夾。
			Files.createDirectories(directory);

			List<QuotationArchivedAsset> archived = new ArrayList<>();
			for (int index = 0; index < candidates.size(); index++) {
				Candidate candidate = candidates.get(index);
				QuotationArchivedAsset asset = candidate.alreadyArchived()
					? existingAsset(candidate)
					: moveAndPersist(
						candidate,
						directory,
						index + 1,
						confirmation.quotationId(),
						moved,
						journals
					);
				archived.add(asset);
			}
			registerJournalCleanup(journals);
			Path selectedPath = archived.stream()
				.filter(QuotationArchivedAsset::selected)
				.map(QuotationArchivedAsset::path)
				.findFirst()
				.orElse(null);
			return new QuotationArchivedAssets(archived, selectedPath);
		}
		catch (Exception exception) {
			compensate(moved, directory, directoryExisted, exception);
			if (exception instanceof QuotationAssetArchiveException archiveException) throw archiveException;

			throw new QuotationAssetArchiveException(ARCHIVE_FAILED, "圖片資產歸檔失敗", exception);
		}
	}

	// 方法：拒絕空白或不一致的正式確認結果。
	private void validateConfirmation(QuotationConfirmationResult confirmation) {
		if (confirmation == null) throw failure("正式確認結果不可留空");

		if (confirmation.folderName() == null || confirmation.fileBaseName() == null) {
			throw failure("正式報價資料夾資訊不完整");
		}
	}

	// 方法：確認報價仍處於可產生檔案狀態，取消案件不得建立正式資料夾。
	private QuotationRecord requireArchivableQuotation(QuotationConfirmationResult confirmation) {
		// 外部呼叫：讀取正式報價狀態及其來源草稿。
		List<Map<String, Object>> rows = jdbc.queryForList(
			"SELECT draft_id, status, sequence_date, sequence_number FROM quotation WHERE id = ?",
			confirmation.quotationId()
		);
		if (rows.size() != 1) throw failure("找不到正式報價");

		Map<String, Object> row = rows.getFirst();
		String status = (String) row.get("status");
		if (!List.of("CONFIRMED", "GENERATING_EXCEL", "GENERATING_PDF", "PDF_FAILED", "READY", "FAILED")
			.contains(status)) {
			throw failure("報價目前不可進行圖片歸檔");
		}
		String expectedFolder = ((String) row.get("sequence_date")).replace("-", "")
			+ "-" + String.format("%02d", ((Number) row.get("sequence_number")).intValue());
		if (!expectedFolder.equals(confirmation.folderName())) throw failure("正式報價流水號不一致");

		return new QuotationRecord(((Number) row.get("draft_id")).longValue());
	}

	// 方法：同時讀取暫存候選與已歸檔關聯，以支援安全重試。
	private List<Candidate> loadCandidates(long draftId, long quotationId) {
		// 外部呼叫：依草稿候選順序讀取暫存路徑或既有正式資產路徑。
		return jdbc.query("""
			SELECT di.message_id, di.candidate_order, di.distinctiveness_score,
				di.quality_score, di.selection_reason, di.is_selected,
				p.staging_path, p.content_type, p.file_size, p.source_type,
				p.source_id, p.uploader_id, p.received_at,
				a.id AS asset_id, a.file_path AS asset_path, qa.id AS quotation_asset_id
			FROM quotation_draft_image di
			LEFT JOIN pending_image p ON p.message_id = di.message_id
			LEFT JOIN asset a ON a.message_id = di.message_id
			LEFT JOIN quotation_asset qa ON qa.quotation_id = ? AND qa.asset_id = a.id
			WHERE di.draft_id = ?
			ORDER BY di.candidate_order, di.id
			""", (resultSet, rowNumber) -> new Candidate(
			resultSet.getString("message_id"),
			resultSet.getInt("candidate_order"),
			(Double) resultSet.getObject("distinctiveness_score"),
			(Double) resultSet.getObject("quality_score"),
			resultSet.getString("selection_reason"),
			resultSet.getInt("is_selected") == 1,
			resultSet.getString("staging_path"),
			resultSet.getString("content_type"),
			resultSet.getLong("file_size"),
			resultSet.getString("source_type"),
			resultSet.getString("source_id"),
			resultSet.getString("uploader_id"),
			resultSet.getString("received_at"),
			nullableLong(resultSet.getObject("asset_id")),
			resultSet.getString("asset_path"),
			nullableLong(resultSet.getObject("quotation_asset_id"))
		), quotationId, draftId);
	}

	// 方法：將 SQLite 可能回傳的 Integer 或 Long 主鍵統一轉成 long。
	private Long nullableLong(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	// 方法：建立資料夾前確認每一張尚未歸檔的原圖都存在且位於 .pending。
	private void preflight(List<Candidate> candidates, Path directory) {
		for (int index = 0; index < candidates.size(); index++) {
			Candidate candidate = candidates.get(index);
			if (candidate.alreadyArchived()) {
				Path existing = outputDirectories.resolveLocator(candidate.assetPath());
				if (!Files.isRegularFile(existing)) throw failure("正式圖片資產遺失");

				continue;
			}
			if (candidate.assetId() != null) throw failure("候選圖片已屬於其他資產範圍");

			if (candidate.pendingPath() == null || !candidate.pendingPath().replace('\\', '/').startsWith(".pending/")) {
				throw failure("候選圖片不在暫存區");
			}
			Path source = storage.resolve(candidate.pendingPath());
			Path target = targetPath(directory, index + 1, candidate);
			Path journal = journalPath(directory, index + 1);
			boolean sourceExists = Files.isRegularFile(source);
			boolean targetExists = Files.isRegularFile(target);
			boolean journalExists = Files.isRegularFile(journal);
			if (Files.isSymbolicLink(source) || Files.isSymbolicLink(target) || Files.isSymbolicLink(journal)) {
				throw failure("圖片封存路徑不可為符號連結");
			}
			if (!sourceExists && (!targetExists || !journalExists)) {
				throw failure("候選圖片原圖遺失，且沒有可驗證的復原紀錄");
			}
			if (targetExists && !journalExists) {
				throw failure("正式圖片已存在，但缺少可信任的復原紀錄");
			}
			if (sourceExists && !journalExists) {
				try {
					if (Files.size(source) != candidate.fileSize()) throw failure("候選圖片大小與暫存資料不一致");
				}
				catch (IOException exception) {
					throw new QuotationAssetArchiveException(
						ARCHIVE_FAILED,
						"候選圖片無法驗證",
						exception
					);
				}
			}
			if (journalExists) {
				verifyJournal(candidate, sourceExists ? source : target, target, journal, targetExists);
			}
		}
	}

	// 方法：將單張原圖以固定不碰撞檔名搬移，並建立 asset 與 quotation_asset。
	private QuotationArchivedAsset moveAndPersist(
		Candidate candidate,
		Path directory,
		int position,
		long quotationId,
		List<MoveOperation> moved,
		List<Path> journals
	) throws IOException {
		Path source = storage.resolve(candidate.pendingPath());
		Path target = targetPath(directory, position, candidate);
		Path journal = journalPath(directory, position);
		if (!Files.exists(journal)) writeJournal(journal, candidate, target, source);

		if (Files.isRegularFile(source) && Files.exists(target)) {
			// 檔案：合法 journal 證明此目標是前次未完成搬移留下的檔案，才允許刪除後重試。
			Files.delete(target);
		}
		if (Files.isRegularFile(source)) {
			// 檔案：journal 已持久化後才搬移，讓程序中斷後可驗證並接續。
			move(source, target);
			moved.add(new MoveOperation(source, target, journal));
		}
		journals.add(journal);
		String relativePath = outputDirectories.relativeLocator(target);
		long size = Files.size(target);
		long assetId = insertAsset(candidate, relativePath, size);
		insertQuotationAsset(quotationId, assetId, candidate);
		// 外部呼叫：正式資產與關聯完成後才移除暫存 metadata。
		jdbc.update("DELETE FROM pending_image WHERE message_id = ?", candidate.messageId());
		return new QuotationArchivedAsset(assetId, candidate.messageId(), target, candidate.selected());
	}

	// 方法：建立候選圖片的固定正式目標路徑。
	private Path targetPath(Path directory, int position, Candidate candidate) {
		return directory.resolve(String.format("image-%02d%s", position, extension(candidate.contentType())));
	}

	// 方法：建立候選圖片的復原 sidecar 路徑。
	private Path journalPath(Path directory, int position) {
		return directory.resolve(String.format(".image-%02d.archive.json", position));
	}

	// 方法：以原子換名寫入已同步至磁碟的圖片搬移 journal。
	private void writeJournal(
		Path journal,
		Candidate candidate,
		Path target,
		Path source
	) throws IOException {
		ArchiveJournal content = new ArchiveJournal(
			candidate.messageId(),
			target.getFileName().toString(),
			candidate.contentType(),
			Files.size(source),
			sha256(source)
		);
		byte[] bytes = JSON.writeValueAsBytes(Map.of(
			"messageId", content.messageId(),
			"targetFileName", content.targetFileName(),
			"contentType", content.contentType(),
			"fileSize", content.fileSize(),
			"sha256", content.sha256()
		));
		Path temporary = journal.resolveSibling(journal.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			// 檔案：先 force 暫存檔內容，再以原子換名公開完整 journal。
			try (FileChannel channel = FileChannel.open(
				temporary,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE
			)) {
				channel.write(java.nio.ByteBuffer.wrap(bytes));
				channel.force(true);
			}
			try {
				Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, journal);
			}
		}
		finally {
			Files.deleteIfExists(temporary);
		}
	}

	// 方法：驗證 journal 身分與來源或目標檔案內容完全相符。
	private ArchiveJournal verifyJournal(
		Candidate candidate,
		Path content,
		Path target,
		Path journal,
		boolean verifyTarget
	) {
		try {
			JsonNode json = JSON.readTree(Files.readString(journal));
			ArchiveJournal record = new ArchiveJournal(
				json.path("messageId").asText(),
				json.path("targetFileName").asText(),
				json.path("contentType").asText(),
				json.path("fileSize").asLong(-1),
				json.path("sha256").asText()
			);
			boolean metadataMatches = record.messageId().equals(candidate.messageId())
				&& record.targetFileName().equals(target.getFileName().toString())
				&& record.contentType().equals(candidate.contentType())
				&& record.fileSize() == candidate.fileSize();
			boolean contentMatches = Files.size(content) == record.fileSize()
				&& record.sha256().equals(sha256(content));
			boolean targetMatches = !verifyTarget
				|| Files.size(target) == record.fileSize() && record.sha256().equals(sha256(target));
			if (!metadataMatches || !contentMatches || !targetMatches) {
				throw failure("圖片搬移復原紀錄與檔案不一致");
			}

			return record;
		}
		catch (QuotationAssetArchiveException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new QuotationAssetArchiveException(
				ARCHIVE_FAILED,
				"圖片搬移復原紀錄無法驗證",
				exception
			);
		}
	}

	// 方法：計算檔案 SHA-256，供 journal 與復原流程比對。
	private String sha256(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境不支援 SHA-256", exception);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count > 0) digest.update(buffer, 0, count);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	// 方法：在資料庫提交後清除 journal；沒有交易代理的單元測試則立即清除。
	private void registerJournalCleanup(List<Path> journals) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			cleanupJournals(journals);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			// 方法：在資料庫交易成功後刪除已完成的圖片搬移 journal。
			@Override
			public void afterCompletion(int status) {
				if (status == TransactionSynchronization.STATUS_COMMITTED) cleanupJournals(journals);
			}
		});
	}

	// 方法：清除已完成交易的圖片搬移 journal。
	private void cleanupJournals(List<Path> journals) {
		for (Path journal : journals) {
			try {
				Files.deleteIfExists(journal);
			}
			catch (IOException ignored) {
				// 復原：刪除失敗不影響已提交資料，下次冪等呼叫會再次清理。
			}
		}
	}

	// 方法：清理由「資料庫已提交、程序尚未刪 sidecar」留下的合法 journal。
	private void cleanupCommittedJournals(List<Candidate> candidates, Path directory) {
		List<Path> verified = new ArrayList<>();
		for (int index = 0; index < candidates.size(); index++) {
			Candidate candidate = candidates.get(index);
			Path journal = journalPath(directory, index + 1);
			if (!Files.isRegularFile(journal) || Files.isSymbolicLink(journal)) continue;

			Path target = outputDirectories.resolveLocator(candidate.assetPath());
			verifyJournal(candidate, target, target, journal, true);
			verified.add(journal);
		}
		cleanupJournals(verified);
	}

	// 方法：建立正式資產 metadata 並回傳主鍵。
	private long insertAsset(Candidate candidate, String relativePath, long size) {
		// 外部呼叫：以 LINE message id 保證同一張原圖只建立一筆資產。
		Long assetId = jdbc.queryForObject("""
			INSERT INTO asset (
				message_id, share_token, source_type, source_id, uploader_id,
				file_path, content_type, file_size, created_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			RETURNING id
			""", Long.class,
			candidate.messageId(),
			UUID.randomUUID().toString(),
			candidate.sourceType(),
			candidate.sourceId(),
			candidate.uploaderId(),
			relativePath,
			candidate.contentType(),
			size,
			candidate.receivedAt()
		);
		if (assetId == null) throw failure("無法建立正式圖片資產");

		return assetId;
	}

	// 方法：保存候選順序、評分及唯一選中狀態。
	private void insertQuotationAsset(long quotationId, long assetId, Candidate candidate) {
		// 外部呼叫：建立正式報價與圖片資產的不可重複關聯。
		jdbc.update("""
			INSERT INTO quotation_asset (
				quotation_id, asset_id, candidate_order, distinctiveness_score,
				quality_score, selection_reason, is_selected
			) VALUES (?, ?, ?, ?, ?, ?, ?)
			""",
			quotationId,
			assetId,
			candidate.order(),
			candidate.distinctivenessScore(),
			candidate.qualityScore(),
			candidate.selectionReason(),
			candidate.selected() ? 1 : 0
		);
	}

	// 方法：回傳已完成歸檔的冪等結果。
	private QuotationArchivedAssets existingResult(List<Candidate> candidates) {
		List<QuotationArchivedAsset> archived = candidates.stream()
			.map(this::existingAsset)
			.toList();
		Path selectedPath = archived.stream()
			.filter(QuotationArchivedAsset::selected)
			.map(QuotationArchivedAsset::path)
			.findFirst()
			.orElse(null);
		return new QuotationArchivedAssets(archived, selectedPath);
	}

	// 方法：將既有正式資產資料轉成報表可使用的原圖路徑。
	private QuotationArchivedAsset existingAsset(Candidate candidate) {
		return new QuotationArchivedAsset(
			candidate.assetId(),
			candidate.messageId(),
			outputDirectories.resolveLocator(candidate.assetPath()),
			candidate.selected()
		);
	}

	// 方法：優先使用原子搬移，同磁碟不支援時退回一般搬移。
	private void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target);
		}
	}

	// 方法：資料庫或後續檔案操作失敗時，逆序將已搬圖片還原到原始暫存路徑。
	private void compensate(
		List<MoveOperation> moved,
		Path directory,
		boolean directoryExisted,
		Exception original
	) {
		List<MoveOperation> reversed = new ArrayList<>(moved);
		Collections.reverse(reversed);
		for (MoveOperation operation : reversed) {
			try {
				// 檔案系統：只還原本次確實搬移的檔案，不覆寫任何既有檔。
				if (Files.exists(operation.target()) && !Files.exists(operation.source())) {
					move(operation.target(), operation.source());
				}
				Files.deleteIfExists(operation.journal());
			}
			catch (IOException compensationFailure) {
				original.addSuppressed(compensationFailure);
			}
		}
		if (directory == null || directoryExisted) return;

		try {
			// 檔案系統：僅刪除本次建立且已因還原而清空的日期流水號資料夾。
			Files.deleteIfExists(directory);
		}
		catch (IOException compensationFailure) {
			original.addSuppressed(compensationFailure);
		}
	}

	// 方法：依可信 MIME 類型決定正式原圖副檔名。
	private String extension(String contentType) {
		if (contentType == null) return ".jpg";

		return switch (contentType.split(";", 2)[0].trim().toLowerCase()) {
			case "image/png" -> ".png";
			case "image/gif" -> ".gif";
			case "image/webp" -> ".webp";
			default -> ".jpg";
		};
	}

	// 方法：建立不揭露磁碟路徑的歸檔錯誤。
	private QuotationAssetArchiveException failure(String message) {
		return new QuotationAssetArchiveException(ARCHIVE_FAILED, message);
	}

	private record QuotationRecord(long draftId) {}

	private record ArchiveJournal(
		String messageId,
		String targetFileName,
		String contentType,
		long fileSize,
		String sha256
	) {}

	private record Candidate(
		String messageId,
		int order,
		Double distinctivenessScore,
		Double qualityScore,
		String selectionReason,
		boolean selected,
		String pendingPath,
		String contentType,
		long fileSize,
		String sourceType,
		String sourceId,
		String uploaderId,
		String receivedAt,
		Long assetId,
		String assetPath,
		Long quotationAssetId
	) {

		// 方法：確認候選已有正式資產及報價關聯。
		private boolean alreadyArchived() {
			return quotationAssetId != null && assetId != null && assetPath != null;
		}
	}

	private record MoveOperation(Path source, Path target, Path journal) {}
}
