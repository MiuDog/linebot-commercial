package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationAdminException;
import dev.miudog.linebotcommercial.service.quotation.QuotationPdfStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 以既有報價與 quotation_file 記錄保存 Excel PDF 匯出狀態。
 */
@Repository
public class JdbcQuotationPdfStore implements QuotationPdfStore {

	private static final String PDF_CONTENT_TYPE = "application/pdf";

	private final JdbcTemplate jdbc;
	private final boolean legacyFilesystemEnabled;

	// 方法：建立報價 PDF 狀態儲存庫。
	public JdbcQuotationPdfStore(
		JdbcTemplate jdbc,
		@Value("${app.storage.legacy-filesystem-enabled:false}") boolean legacyFilesystemEnabled
	) {
		this.jdbc = jdbc;
		this.legacyFilesystemEnabled = legacyFilesystemEnabled;
	}

	// 方法：讀取既有報價及唯一已完成 XLSX，不建立新流水號。
	@Override
	public Optional<PdfJob> find(long quotationId) {
		// 外部呼叫：只查詢已存在且可供轉檔的報價與 XLSX 檔案記錄。
		String locator = legacyFilesystemEnabled
			? "qf.relative_path"
			: "COALESCE(qf.object_key, qf.relative_path)";
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT q.id, q.quotation_no, q.status, %s AS relative_path
			FROM quotation q
			JOIN quotation_file qf ON qf.quotation_id = q.id
			WHERE q.id = ?
			  AND qf.file_kind = 'XLSX'
			  AND qf.status = 'READY'
			""".formatted(locator), quotationId);
		if (rows.isEmpty()) return Optional.empty();

		if (rows.size() != 1) throw storage("報價的 XLSX 檔案記錄不唯一");

		Map<String, Object> row = rows.getFirst();
		return Optional.of(
			new PdfJob(
				((Number) row.get("id")).longValue(),
				(String) row.get("quotation_no"),
				(String) row.get("status"),
				(String) row.get("relative_path")
			)
		);
	}

	// 方法：以同一報價與相對路徑建立或重設 PDF 產生中記錄。
	@Override
	@Transactional
	public void markGenerating(long quotationId, String relativePdfPath) {
		int updated = jdbc.update(
			"UPDATE quotation SET status = 'GENERATING_PDF' WHERE id = ?",
			quotationId
		);
		if (updated != 1) throw storage("找不到要匯出 PDF 的報價");

		// 外部呼叫：以唯一鍵更新同一報價的 PDF 記錄，不建立第二份或更換路徑。
		if (legacyFilesystemEnabled) {
			markGeneratingLegacy(quotationId, relativePdfPath);
			return;
		}

		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, object_key, content_type, status, error_message
			)
			VALUES (?, 'PDF', ?, ?, ?, 'GENERATING', NULL)
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				relative_path = excluded.relative_path,
				object_key = excluded.object_key,
				object_version = NULL,
				content_type = excluded.content_type,
				content_hash = NULL,
				file_size = NULL,
				status = 'GENERATING',
				error_message = NULL,
				updated_at = CURRENT_TIMESTAMP
			""", quotationId, relativePdfPath, relativePdfPath, PDF_CONTENT_TYPE);
	}

	// 方法：舊資料庫結構不含物件欄位，僅供遷移測試與一次性匯入使用。
	private void markGeneratingLegacy(long quotationId, String relativePdfPath) {
		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, content_type, status, error_message
			)
			VALUES (?, 'PDF', ?, ?, 'GENERATING', NULL)
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				relative_path = excluded.relative_path,
				content_type = excluded.content_type,
				content_hash = NULL,
				file_size = NULL,
				status = 'GENERATING',
				error_message = NULL,
				updated_at = CURRENT_TIMESTAMP
			""", quotationId, relativePdfPath, PDF_CONTENT_TYPE);
	}

	// 方法：以同一報價完成 PDF 記錄並切換為 READY。
	@Override
	@Transactional
	public void markReady(long quotationId, String relativePdfPath, String contentHash, long fileSize) {
		String objectAssignments = legacyFilesystemEnabled
			? ""
			: "object_key = ?, ";
		Object[] arguments = legacyFilesystemEnabled
			? new Object[] { relativePdfPath, contentHash, fileSize, quotationId }
			: new Object[] { relativePdfPath, relativePdfPath, contentHash, fileSize, quotationId };
		int fileUpdated = jdbc.update("""
			UPDATE quotation_file
			SET relative_path = ?, %s content_hash = ?, file_size = ?, status = 'READY',
				error_message = NULL, updated_at = CURRENT_TIMESTAMP
			WHERE quotation_id = ? AND file_kind = 'PDF'
			""".formatted(objectAssignments), arguments);
		if (fileUpdated != 1) throw storage("找不到產生中的 PDF 檔案記錄");

		int quotationUpdated = jdbc.update("""
			UPDATE quotation
			SET status = 'READY', exported_at = CURRENT_TIMESTAMP
			WHERE id = ?
			""", quotationId);
		if (quotationUpdated != 1) throw storage("找不到已完成 PDF 的報價");
	}

	// 方法：保存安全錯誤摘要並保留原 XLSX 與報價流水號。
	@Override
	@Transactional
	public void markFailed(long quotationId, String relativePdfPath, String errorMessage) {
		int quotationUpdated = jdbc.update(
			"UPDATE quotation SET status = 'PDF_FAILED' WHERE id = ?",
			quotationId
		);
		if (quotationUpdated != 1) throw storage("找不到 PDF 匯出失敗的報價");

		// 外部呼叫：以唯一鍵保存失敗狀態，既有 XLSX 記錄完全不變。
		if (legacyFilesystemEnabled) {
			markFailedLegacy(quotationId, relativePdfPath, errorMessage);
			return;
		}

		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, object_key, content_type, status, error_message
			)
			VALUES (?, 'PDF', ?, ?, ?, 'FAILED', ?)
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				relative_path = excluded.relative_path,
				object_key = excluded.object_key,
				object_version = NULL,
				content_type = excluded.content_type,
				content_hash = NULL,
				file_size = NULL,
				status = 'FAILED',
				error_message = excluded.error_message,
				updated_at = CURRENT_TIMESTAMP
			""", quotationId, relativePdfPath, relativePdfPath, PDF_CONTENT_TYPE, errorMessage);
	}

	// 方法：舊資料庫以本機相對路徑保存 PDF 失敗狀態。
	private void markFailedLegacy(long quotationId, String relativePdfPath, String errorMessage) {
		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, content_type, status, error_message
			)
			VALUES (?, 'PDF', ?, ?, 'FAILED', ?)
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				relative_path = excluded.relative_path,
				content_type = excluded.content_type,
				content_hash = NULL,
				file_size = NULL,
				status = 'FAILED',
				error_message = excluded.error_message,
				updated_at = CURRENT_TIMESTAMP
			""", quotationId, relativePdfPath, PDF_CONTENT_TYPE, errorMessage);
	}

	// 方法：建立具有穩定錯誤代碼的 PDF 儲存失敗。
	private QuotationAdminException storage(String message) {
		return new QuotationAdminException("PDF_STORAGE_ERROR", message);
	}
}
