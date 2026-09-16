package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.FileStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationAssetArchiveServiceTest {

	@TempDir Path root;
	Path assetsRoot;
	Path quotationRoot;
	Connection connection;
	JdbcTemplate jdbc;
	QuotationAssetArchiveService service;
	QuotationOutputDirectoryService outputDirectories;
	QuotationConfirmationResult confirmation;

	@BeforeEach
	void setUp() throws Exception {
		connection = DriverManager.getConnection("jdbc:sqlite::memory:");
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
		jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
		assetsRoot = root.resolve("assets");
		quotationRoot = root.resolve("quotations");
		outputDirectories = new QuotationOutputDirectoryService(quotationRoot.toString());
		service = new QuotationAssetArchiveService(
			jdbc,
			new FileStorageService(assetsRoot.toString()),
			outputDirectories
		);
		confirmation = seedConfirmedQuotation("CONFIRMED");
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	@Test
	void movesAllPendingOriginalsOnceAndPersistsAssetLinksWithSelectedPath() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", false, 0.7, 0.9);
		seedPendingImage("IMG2", ".pending/two.jpg", true, 0.95, 0.8);

		QuotationArchivedAssets archived = service.archive(confirmation);

		assertThat(archived.assets()).hasSize(2);
		assertThat(archived.selectedImagePath()).isNotNull().isRegularFile();
		assertThat(archived.selectedImagePath().getParent().getFileName().toString())
			.isEqualTo("20260811-01");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_asset", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_asset WHERE is_selected = 1",
			Integer.class
		)).isEqualTo(1);

		QuotationArchivedAssets repeated = service.archive(confirmation);
		assertThat(repeated.assets()).hasSize(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isEqualTo(2);
	}

	// 測試：使用者明確拒絕嵌入後，所有未選中候選仍會隨正式報價完整歸檔。
	@Test
	void archivesEveryCandidateWithoutSelectingOneAfterExplicitDecline() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", false, 0.7, 0.9);
		seedPendingImage("IMG2", ".pending/two.jpg", false, 0.95, 0.8);
		jdbc.update(
			"UPDATE quotation_draft SET image_declined = 1 WHERE id = (SELECT draft_id FROM quotation WHERE id = ?)",
			confirmation.quotationId()
		);

		QuotationArchivedAssets archived = service.archive(confirmation);

		assertThat(archived.assets()).hasSize(2);
		assertThat(archived.selectedImagePath()).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_asset", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_asset WHERE is_selected = 1",
			Integer.class
		)).isZero();
	}

	@Test
	void allowsFailedQuotationWithoutCandidatesToContinueWorkbookRetry() {
		jdbc.update("UPDATE quotation SET status = 'FAILED' WHERE id = ?", confirmation.quotationId());

		QuotationArchivedAssets archived = service.archive(confirmation);

		assertThat(archived.assets()).isEmpty();
		assertThat(archived.selectedImagePath()).isNull();
		assertThat(quotationRoot.resolve("報價單/20260811-01")).doesNotExist();
	}

	@Test
	void preflightFailureLeavesEveryPendingOriginalInPlaceAndCreatesNoQuotationFolder() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", false, 0.7, 0.9);
		seedPendingImageRecordOnly("IMG2", ".pending/missing.jpg", true, 0.95, 0.8);

		assertThatThrownBy(() -> service.archive(confirmation))
			.isInstanceOf(QuotationAssetArchiveException.class);
		assertThat(assetsRoot.resolve(".pending/one.jpg")).isRegularFile();
		assertThat(quotationRoot.resolve("報價單/20260811-01")).doesNotExist();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isZero();
	}

	@Test
	void cancelledQuotationNeverCreatesFormalFolder() throws Exception {
		QuotationConfirmationResult cancelled = seedConfirmedQuotation("CANCELLED");
		seedPendingImage("CANCEL-IMG", ".pending/cancel.jpg", true, 0.9, 0.9, 2L);

		assertThatThrownBy(() -> service.archive(cancelled))
			.isInstanceOf(QuotationAssetArchiveException.class);
		assertThat(quotationRoot.resolve("報價單/20260811-02")).doesNotExist();
	}

	@Test
	void resumesMoveCompletedBeforeDatabaseCommitFromVerifiedJournal() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", true, 0.9, 0.9);
		Path source = assetsRoot.resolve(".pending/one.jpg");
		Path directory = outputDirectories.resolveFormalDirectory(confirmation.folderName());
		Files.createDirectories(directory);
		Path target = directory.resolve("image-01.jpg");
		Files.move(source, target);
		writeJournal(directory, "IMG1", target, "image/jpeg", 4, sha256(target));

		QuotationArchivedAssets archived = service.archive(confirmation);

		assertThat(archived.assets()).hasSize(1);
		assertThat(archived.selectedImagePath()).isEqualTo(target);
		assertThat(target).isRegularFile();
		assertThat(source).doesNotExist();
		assertThat(directory.resolve(".image-01.archive.json")).doesNotExist();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_asset", Integer.class)).isEqualTo(1);
	}

	@Test
	void refusesToAdoptTargetThatDoesNotMatchTheJournalHash() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", true, 0.9, 0.9);
		Path source = assetsRoot.resolve(".pending/one.jpg");
		Path directory = outputDirectories.resolveFormalDirectory(confirmation.folderName());
		Files.createDirectories(directory);
		Path target = directory.resolve("image-01.jpg");
		Files.move(source, target);
		writeJournal(directory, "IMG1", target, "image/jpeg", 4, sha256(target));
		Files.writeString(target, "EVIL");

		assertThatThrownBy(() -> service.archive(confirmation))
			.isInstanceOf(QuotationAssetArchiveException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isZero();
	}

	@Test
	void successfulArchiveLeavesNoRecoveryJournal() throws Exception {
		seedPendingImage("IMG1", ".pending/one.jpg", true, 0.9, 0.9);

		service.archive(confirmation);

		Path directory = outputDirectories.resolveFormalDirectory(confirmation.folderName());
		assertThat(directory.resolve(".image-01.archive.json")).doesNotExist();
	}

	private QuotationConfirmationResult seedConfirmedQuotation(String status) {
		long draftId = jdbc.queryForObject("""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name, work_name, scheme_id, status
			)
			SELECT lower(hex(randomblob(16))), 'user', 'U1', 'U1', '範例', '工程', id, 'CONFIRMED'
			FROM quotation_scheme WHERE code = 'GENERAL'
			RETURNING id
			""", Long.class);
		long quotationId = jdbc.queryForObject("""
			INSERT INTO quotation (
				draft_id, quotation_no, quotation_name, sequence_date, sequence_number,
				company_name, work_name, quotation_date, valid_until, scheme_id, template_id, status
			)
			SELECT ?, ?, '範例-工程', '2026-08-11', ?, '範例', '工程', '2026-08-11', '2026-08-26',
				s.id, t.id, ?
			FROM quotation_scheme s JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE s.code = 'GENERAL'
			RETURNING id
			""", Long.class, draftId, "Q-" + draftId, draftId, status);
		return new QuotationConfirmationResult(
			quotationId,
			"Q-" + draftId,
			java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26),
			(int) draftId,
			"20260811-" + String.format("%02d", draftId),
			"範例-工程 20260811-" + String.format("%02d", draftId)
		);
	}

	private void seedPendingImage(
		String messageId,
		String relativePath,
		boolean selected,
		double distinctiveness,
		double quality
	) throws Exception {
		seedPendingImage(messageId, relativePath, selected, distinctiveness, quality, 1L);
	}

	private void seedPendingImage(
		String messageId,
		String relativePath,
		boolean selected,
		double distinctiveness,
		double quality,
		long quotationId
	) throws Exception {
		Path path = assetsRoot.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.writeString(path, messageId);
		seedPendingImageRecordOnly(messageId, relativePath, selected, distinctiveness, quality, quotationId);
	}

	private void seedPendingImageRecordOnly(
		String messageId,
		String relativePath,
		boolean selected,
		double distinctiveness,
		double quality
	) {
		seedPendingImageRecordOnly(messageId, relativePath, selected, distinctiveness, quality, 1L);
	}

	private void seedPendingImageRecordOnly(
		String messageId,
		String relativePath,
		boolean selected,
		double distinctiveness,
		double quality,
		long quotationId
	) {
		Long draftId = jdbc.queryForObject("SELECT draft_id FROM quotation WHERE id = ?", Long.class, quotationId);
		jdbc.update("""
			INSERT INTO pending_image (
				message_id, image_set_id, image_index, image_total, source_type,
				source_id, uploader_id, staging_path, content_type, file_size, received_at
			) VALUES (?, ?, 1, 1, 'user', 'U1', 'U1', ?, 'image/jpeg', 4, ?)
			""", messageId, messageId, relativePath, Instant.now().toString());
		jdbc.update("""
			INSERT INTO quotation_draft_image (
				draft_id, message_id, distinctiveness_score, quality_score, is_selected
			) VALUES (?, ?, ?, ?, ?)
			""", draftId, messageId, distinctiveness, quality, selected ? 1 : 0);
	}

	private void writeJournal(
		Path directory,
		String messageId,
		Path target,
		String contentType,
		long fileSize,
		String sha256
	) throws Exception {
		Files.writeString(directory.resolve(".image-01.archive.json"), """
			{"messageId":"%s","targetFileName":"%s","contentType":"%s","fileSize":%d,"sha256":"%s"}
			""".formatted(messageId, target.getFileName(), contentType, fileSize, sha256));
	}

	private String sha256(Path path) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
	}
}
