package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存正式 XLSX 產生狀態與檔案 metadata。
 */
@Repository
public class QuotationGenerationRepository {

	private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private final JdbcTemplate jdbc;
	private final QuotationOutputDirectoryService outputDirectories;

	// 方法：建立正式報價檔案狀態儲存庫。
	public QuotationGenerationRepository(
		JdbcTemplate jdbc,
		QuotationOutputDirectoryService outputDirectories
	) {
		this.jdbc = jdbc;
		this.outputDirectories = outputDirectories;
	}

	// 方法：將報價與 XLSX 檔案標記為產生中。
	@Transactional
	public void markGenerating(long quotationId) {
		// 外部呼叫：只允許已確認或可重試失敗的報價進入 Excel 產生階段。
		int changed = jdbc.update("""
			UPDATE quotation SET status = 'GENERATING_EXCEL'
			WHERE id = ? AND status IN ('CONFIRMED', 'FAILED', 'PDF_FAILED', 'GENERATING_EXCEL')
			""", quotationId);
		if (changed != 1) throw new QuotationGenerationException("INVALID_GENERATION_STATE", "報價目前無法產生 Excel", null);

		// 外部呼叫：建立或重設同一報價唯一的 XLSX 狀態列。
		jdbc.update("""
			INSERT INTO quotation_file (quotation_id, file_kind, content_type, status)
			VALUES (?, 'XLSX', ?, 'GENERATING')
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				status = 'GENERATING', relative_path = NULL, content_hash = NULL,
				file_size = NULL, error_message = NULL, updated_at = CURRENT_TIMESTAMP
			""", quotationId, XLSX_CONTENT_TYPE);
	}

	// 方法：驗證正式 Excel 存在後保存相對路徑、雜湊與檔案大小。
	@Transactional
	public void markReady(long quotationId, Path path) {
		if (path == null || !Files.isRegularFile(path)) {
			throw new QuotationGenerationException("XLSX_NOT_FOUND", "Excel 產出檔不存在", null);
		}
		Path normalized = path.toAbsolutePath().normalize();
		String relativePath;
		try {
			relativePath = outputDirectories.relativeLocator(normalized);
		}
		catch (IllegalArgumentException exception) {
			throw new QuotationGenerationException("INVALID_XLSX_PATH", "Excel 產出路徑不在報價單目錄內", exception);
		}
		try {
			long size = Files.size(normalized);
			String hash = sha256(normalized);
			// 外部呼叫：將 XLSX metadata 原子標記為可供 PDF 階段讀取。
			int changed = jdbc.update("""
				UPDATE quotation_file
				SET relative_path = ?, content_hash = ?, file_size = ?, status = 'READY',
					error_message = NULL, updated_at = CURRENT_TIMESTAMP
				WHERE quotation_id = ? AND file_kind = 'XLSX'
				""", relativePath, hash, size, quotationId);
			if (changed != 1) throw new QuotationGenerationException("MISSING_XLSX_RECORD", "找不到 Excel 產生狀態", null);

			// 外部呼叫：保存報價輸出路徑並交由 PDF 產生流程接續。
			jdbc.update("""
				UPDATE quotation SET status = 'GENERATING_PDF', output_path = ?, exported_at = CURRENT_TIMESTAMP
				WHERE id = ?
				""", relativePath, quotationId);
		}
		catch (IOException exception) {
			throw new QuotationGenerationException("XLSX_METADATA_FAILED", "無法讀取 Excel 產出資訊", exception);
		}
	}

	// 方法：以穩定代碼記錄正式檔案產生失敗，避免保存內部例外內容。
	@Transactional
	public void markFailed(long quotationId, String errorCode) {
		String safeCode = errorCode == null || !errorCode.matches("[A-Z0-9_]{1,80}")
			? "GENERATION_FAILED"
			: errorCode;
		// 資料庫 API：只允許尚未完成 PDF／LINE 的 Excel 階段降為失敗。
		int quotationChanged = jdbc.update("""
			UPDATE quotation SET status = 'FAILED'
			WHERE id = ? AND status IN ('CONFIRMED', 'GENERATING_EXCEL', 'FAILED')
			""", quotationId);
		if (quotationChanged != 1) return;

		// 外部呼叫：確保失敗時也存在可由管理頁重試的 XLSX 狀態列。
		jdbc.update("""
			INSERT INTO quotation_file (quotation_id, file_kind, content_type, status, error_message)
			VALUES (?, 'XLSX', ?, 'FAILED', ?)
			ON CONFLICT (quotation_id, file_kind) DO UPDATE SET
				status = 'FAILED', error_message = excluded.error_message,
				updated_at = CURRENT_TIMESTAMP
			""", quotationId, XLSX_CONTENT_TYPE, safeCode);
	}

	// 方法：讀取背景恢復判斷所需的報價、XLSX 與 PDF 階段狀態。
	public GenerationStage stage(long quotationId) {
		// 資料庫 API：只讀取狀態，不載入檔案路徑或 LINE 目的地。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT q.status AS quotation_status,
				(SELECT status FROM quotation_file
				 WHERE quotation_id = q.id AND file_kind = 'XLSX') AS xlsx_status,
				(SELECT status FROM quotation_file
				 WHERE quotation_id = q.id AND file_kind = 'PDF') AS pdf_status
			FROM quotation q
			WHERE q.id = ?
			""", quotationId);
		if (rows.size() != 1) {
			throw new QuotationGenerationException(
				"GENERATION_SNAPSHOT_NOT_FOUND",
				"找不到正式報價產生狀態",
				null
			);
		}
		Map<String, Object> row = rows.getFirst();
		return new GenerationStage(
			(String) row.get("quotation_status"),
			(String) row.get("xlsx_status"),
			(String) row.get("pdf_status")
		);
	}

	// 方法：串流計算正式檔案 SHA-256，避免一次載入大型報表。
	private String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// 檔案系統：只讀取剛完成的正式 XLSX 以建立完整性 metadata。
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					if (read > 0) digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("執行環境不支援 SHA-256", exception);
		}
	}

	public record GenerationStage(String quotationStatus, String xlsxStatus, String pdfStatus) {

		// 方法：判斷正式 Excel metadata 及檔案階段是否已完成。
		public boolean xlsxReady() {
			return "READY".equals(xlsxStatus);
		}

		// 方法：判斷正式 PDF metadata 及檔案階段是否已完成。
		public boolean pdfReady() {
			return "READY".equals(pdfStatus);
		}

		// 方法：已傳送報價是不可逆終態，任何重複工作都直接完成。
		public boolean sent() {
			return "SENT".equals(quotationStatus);
		}
	}
}
