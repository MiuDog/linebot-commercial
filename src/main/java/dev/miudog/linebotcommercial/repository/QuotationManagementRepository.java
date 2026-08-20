package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationAdminException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 查詢本機管理頁所需的正式報價快照、檔案及 LINE 交付狀態。
 */
@Repository
public class QuotationManagementRepository {

	private final JdbcTemplate jdbc;

	// 方法：建立正式報價管理資料存取層。
	public QuotationManagementRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：以固定條件片段和參數化值查詢正式報價分頁。
	public QuotationPage findQuotations(QuotationFilter filter) {
		SqlFilter sqlFilter = sqlFilter(filter);
		List<Object> listParameters = new ArrayList<>(sqlFilter.parameters());
		listParameters.add(filter.pageSize());
		listParameters.add((filter.page() - 1) * filter.pageSize());

		// 資料庫 API：使用固定排序與相關子查詢聚合檔案及最新交付狀態。
		List<QuotationSummary> data = jdbc.query("""
			SELECT q.id, q.quotation_no, q.quotation_name, q.company_name, q.work_name,
				q.quotation_date, q.currency, q.total_amount, q.status, q.created_at,
				s.code AS scheme_code,
				(SELECT f.status FROM quotation_file f
				 WHERE f.quotation_id = q.id AND f.file_kind = 'XLSX') AS xlsx_status,
				(SELECT f.status FROM quotation_file f
				 WHERE f.quotation_id = q.id AND f.file_kind = 'PDF') AS pdf_status,
				(SELECT f.error_message FROM quotation_file f
				 WHERE f.quotation_id = q.id AND f.file_kind = 'PDF') AS pdf_error,
				(SELECT d.status FROM quotation_delivery_attempt d
				 WHERE d.quotation_id = q.id AND d.delivery_kind = 'FINAL'
				 ORDER BY d.id DESC LIMIT 1) AS line_status,
				(SELECT d.error_message FROM quotation_delivery_attempt d
				 WHERE d.quotation_id = q.id AND d.delivery_kind = 'FINAL'
				 ORDER BY d.id DESC LIMIT 1) AS line_error,
				(SELECT COUNT(*) FROM quotation_delivery_attempt d
				 WHERE d.quotation_id = q.id AND d.delivery_kind = 'FINAL') AS line_attempt_count
			FROM quotation q
			JOIN quotation_scheme s ON s.id = q.scheme_id
			""" + sqlFilter.whereClause() + """
			ORDER BY q.created_at DESC, q.id DESC
			LIMIT ? OFFSET ?
			""", this::quotationSummary, listParameters.toArray());

		// 資料庫 API：以相同參數化條件計算分頁總筆數。
		Long totalItems = jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation q " + sqlFilter.whereClause(),
			Long.class,
			sqlFilter.parameters().toArray()
		);
		return new QuotationPage(data, totalItems == null ? 0 : totalItems);
	}

	// 方法：以參數化搜尋與狀態條件查詢草稿分頁。
	public DraftPage findDrafts(DraftFilter filter) {
		DraftSqlFilter sqlFilter = draftSqlFilter(filter);
		List<Object> listParameters = new ArrayList<>(sqlFilter.parameters());
		listParameters.add(filter.pageSize());
		listParameters.add((filter.page() - 1) * filter.pageSize());

		// 資料庫 API：只回傳管理清單需要的草稿欄位，不帶來源擁有者或圖片路徑。
		List<DraftSummary> data = jdbc.query("""
			SELECT d.id, d.quotation_name, d.company_name, d.work_name, d.status,
				d.revision, d.updated_at, s.code AS scheme_code,
				EXISTS (
					SELECT 1 FROM quotation_draft_image di
					WHERE di.draft_id = d.id AND di.is_selected = 1
				) AS has_selected_image
			FROM quotation_draft d
			LEFT JOIN quotation_scheme s ON s.id = d.scheme_id
			""" + sqlFilter.whereClause() + """
			ORDER BY d.updated_at DESC, d.id DESC
			LIMIT ? OFFSET ?
			""", this::draftSummary, listParameters.toArray());

		// 資料庫 API：以相同條件計算草稿分頁總筆數。
		Long totalItems = jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_draft d " + sqlFilter.whereClause(),
			Long.class,
			sqlFilter.parameters().toArray()
		);
		return new DraftPage(data, totalItems == null ? 0 : totalItems);
	}

	// 方法：讀取一筆草稿完整抬頭與來源擁有者供伺服器端安全判斷。
	public Optional<DraftHeader> findDraftHeader(long draftId) {
		// 資料庫 API：以草稿主鍵聚合固定抬頭及其他抬頭欄位。
		List<DraftHeader> rows = jdbc.query("""
			SELECT d.id, d.quotation_name, d.company_name, d.work_name, d.contact_name,
				d.customer_phone, d.customer_email, d.project_location, d.source_type,
				d.source_id, d.requester_id, d.status, d.revision, d.image_declined,
				d.created_at, d.updated_at, s.code AS scheme_code,
				(SELECT field_value FROM quotation_draft_field
				 WHERE draft_id = d.id AND field_key = 'additionalHeader') AS additional_header,
				(SELECT field_value FROM quotation_draft_field
				 WHERE draft_id = d.id AND field_key = 'salesRepresentative') AS sales_representative
			FROM quotation_draft d
			LEFT JOIN quotation_scheme s ON s.id = d.scheme_id
			WHERE d.id = ?
			""", this::draftHeader, draftId);
		return rows.stream().findFirst();
	}

	// 方法：依草稿顯示順序讀取完整品項快照，包含已明確刪除列。
	public List<DraftItem> findDraftItems(long draftId) {
		// 資料庫 API：讀取草稿不可變顯示值、AI 來源與移除狀態。
		return jdbc.query("""
			SELECT id, item_kind, item_code_snapshot, item_name_snapshot,
				specification_snapshot, quantity, unit_snapshot, unit_price_snapshot,
				remark_snapshot, source_text, confidence, is_removed, display_order
			FROM quotation_draft_item
			WHERE draft_id = ?
			ORDER BY display_order, id
			""", this::draftItem, draftId);
	}

	// 方法：讀取草稿所有頂部欄位的提取依據與確認狀態。
	public List<DraftFieldEvidence> findDraftFieldEvidence(long draftId) {
		// 資料庫 API：僅回傳欄位證據，不回傳 LINE 訊息識別碼。
		return jdbc.query("""
			SELECT field_key, field_value, source_text, confidence, confirmation_status
			FROM quotation_draft_field
			WHERE draft_id = ?
			ORDER BY field_key
			""", this::draftFieldEvidence, draftId);
	}

	// 方法：只解析所屬來源與草稿一致的選定暫存圖片。
	public Optional<AdminImageRecord> findSelectedDraftImage(long draftId) {
		// 資料庫 API：同時綁定草稿、來源及上傳者，避免跨擁有者圖片混用。
		List<AdminImageRecord> rows = jdbc.query("""
			SELECT p.staging_path AS locator, p.content_type, p.file_size
			FROM quotation_draft d
			JOIN quotation_draft_image di ON di.draft_id = d.id AND di.is_selected = 1
			JOIN pending_image p ON p.message_id = di.message_id
				AND p.source_type = d.source_type
				AND p.source_id = d.source_id
				AND COALESCE(p.uploader_id, '') = COALESCE(d.requester_id, '')
			WHERE d.id = ?
			""", this::adminImageRecord, draftId);
		return rows.stream().findFirst();
	}

	// 方法：只解析與正式報價關聯且被選定的資產圖片。
	public Optional<AdminImageRecord> findSelectedQuotationImage(long quotationId) {
		// 資料庫 API：透過 quotation_asset 關聯讀取唯一選圖，不暴露分享權杖。
		List<AdminImageRecord> rows = jdbc.query("""
			SELECT a.file_path AS locator, a.content_type, a.file_size
			FROM quotation_asset qa
			JOIN asset a ON a.id = qa.asset_id
			WHERE qa.quotation_id = ? AND qa.is_selected = 1
			""", this::adminImageRecord, quotationId);
		return rows.stream().findFirst();
	}

	// 方法：列出正式報價的安全稽核摘要與固定生命週期事件。
	public List<AuditRecord> findAuditRecords(long quotationId) {
		// 資料庫 API：以固定事件白名單組合資料，不讀取路徑、目的地、權杖或原始錯誤。
		return jdbc.query("""
			SELECT action, outcome, created_at
			FROM (
				SELECT action, COALESCE(json_extract(summary_json, '$.outcome'), 'RECORDED') AS outcome,
					created_at
				FROM admin_audit_log
				WHERE entity_type = 'QUOTATION' AND entity_id = ?
					AND action IN (
						'QUOTATION_CONFIRMED', 'QUOTATION_XLSX_RETRY', 'QUOTATION_PDF_RETRY',
						'QUOTATION_LINE_RETRY', 'QUOTATION_FILE_DOWNLOAD_XLSX',
						'QUOTATION_FILE_DOWNLOAD_PDF', 'QUOTATION_PDF_LINK_REGENERATE',
						'QUOTATION_PDF_LINKS_REVOKE'
					)
				UNION ALL
				SELECT 'QUOTATION_CREATED', 'CONFIRMED', created_at
				FROM quotation
				WHERE id = ?
				UNION ALL
				SELECT CASE file_kind
					WHEN 'XLSX' THEN 'QUOTATION_FILE_XLSX'
					WHEN 'PDF' THEN 'QUOTATION_FILE_PDF'
				END, status, updated_at
				FROM quotation_file
				WHERE quotation_id = ? AND file_kind IN ('XLSX', 'PDF')
				UNION ALL
				SELECT 'QUOTATION_GENERATION', status, updated_at
				FROM quotation_generation_job
				WHERE quotation_id = ?
				UNION ALL
				SELECT CASE delivery_kind
					WHEN 'PREVIEW' THEN 'QUOTATION_DELIVERY_PREVIEW'
					WHEN 'FINAL' THEN 'QUOTATION_DELIVERY_FINAL'
				END, status, COALESCE(completed_at, attempted_at)
				FROM quotation_delivery_attempt
				WHERE quotation_id = ? AND delivery_kind IN ('PREVIEW', 'FINAL')
			)
			ORDER BY created_at DESC
			LIMIT 100
			""",
			this::auditRecord,
			String.valueOf(quotationId),
			quotationId,
			quotationId,
			quotationId,
			quotationId
		);
	}

	// 方法：讀取一筆正式報價抬頭與不可變金額快照。
	public Optional<QuotationHeader> findHeader(long quotationId) {
		// 資料庫 API：只回傳管理預覽需要的欄位，不讀取檔案路徑或 LINE 目的地。
		List<QuotationHeader> rows = jdbc.query("""
			SELECT q.id, q.quotation_no, q.quotation_name, q.company_name, q.work_name,
				q.quotation_date, q.valid_until, q.currency, q.subtotal, q.tax_rate,
				q.tax_amount, q.total_amount, q.status, q.created_at,
				q.sales_representative, s.code AS scheme_code
			FROM quotation q
			JOIN quotation_scheme s ON s.id = q.scheme_id
			WHERE q.id = ?
			""", this::quotationHeader, quotationId);
		return rows.stream().findFirst();
	}

	// 方法：只列出報價單上可由客戶看見的正式快照列。
	public List<QuotationLine> findCustomerLines(long quotationId) {
		// 資料庫 API：依正式行號讀取已確認的客戶可見內容。
		return jdbc.query("""
			SELECT line_number, line_kind, item_code_snapshot, item_name_snapshot,
				specification_snapshot, quantity, unit_snapshot, unit_price_snapshot,
				line_amount, remark_snapshot
			FROM quotation_line
			WHERE quotation_id = ? AND visibility = 'CUSTOMER'
			ORDER BY line_number
			""", this::quotationLine, quotationId);
	}

	// 方法：讀取 XLSX 與 PDF 的狀態摘要，但不向 API 洩漏實體路徑。
	public List<FileState> findFileStates(long quotationId) {
		// 資料庫 API：只取檔案類型、狀態與安全錯誤摘要供管理頁顯示。
		return jdbc.query("""
			SELECT file_kind, status, error_message, file_size
			FROM quotation_file
			WHERE quotation_id = ?
			ORDER BY CASE file_kind WHEN 'XLSX' THEN 0 ELSE 1 END
			""", this::fileState, quotationId);
	}

	// 方法：讀取最新 LINE 交付摘要，不回傳目的地識別碼。
	public DeliveryState findDeliveryState(long quotationId) {
		// 資料庫 API：將最新狀態與總嘗試次數聚合成不含敏感目的地的摘要。
		List<DeliveryState> rows = jdbc.query("""
			SELECT latest.status, latest.error_message,
				(SELECT COUNT(*) FROM quotation_delivery_attempt all_attempts
				 WHERE all_attempts.quotation_id = ? AND all_attempts.delivery_kind = 'FINAL') AS attempt_count
			FROM quotation_delivery_attempt latest
			WHERE latest.quotation_id = ? AND latest.delivery_kind = 'FINAL'
			ORDER BY latest.id DESC
			LIMIT 1
			""", this::deliveryState, quotationId, quotationId);
		return rows.isEmpty() ? new DeliveryState(null, 0, null) : rows.getFirst();
	}

	// 方法：解析唯一已完成且由資料庫綁定的管理下載檔案。
	public Optional<AdminFileRecord> findReadyFile(long quotationId, String fileKind) {
		// 資料庫 API：只有 READY 檔案可被管理下載流程解析。
		List<AdminFileRecord> rows = jdbc.query("""
			SELECT relative_path, content_type, file_size
			FROM quotation_file
			WHERE quotation_id = ? AND file_kind = ? AND status = 'READY'
			""", (result, row) -> new AdminFileRecord(
			result.getString("relative_path"),
			result.getString("content_type"),
			result.getLong("file_size")
		), quotationId, fileKind);
		return rows.stream().findFirst();
	}

	// 方法：確認 PDF 失敗狀態可原號重試。
	public boolean isPdfRetryable(long quotationId) {
		// 資料庫 API：同時驗證正式報價與唯一 PDF 檔案均為失敗狀態。
		Integer count = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM quotation q
			JOIN quotation_file f ON f.quotation_id = q.id AND f.file_kind = 'PDF'
			WHERE q.id = ? AND q.status = 'PDF_FAILED' AND f.status = 'FAILED'
			""", Integer.class, quotationId);
		return count != null && count == 1;
	}

	// 方法：只從最新失敗交付紀錄取回既有 LINE 目的地供原報價重送。
	public Optional<String> findRetryDestination(long quotationId) {
		// 資料庫 API：只在最新交付確實失敗時取回目的地，避免重送已成功的舊失敗紀錄。
		List<String> rows = jdbc.query("""
			SELECT latest.destination_id
			FROM quotation q
			JOIN quotation_delivery_attempt latest ON latest.id = (
				SELECT candidate.id
				FROM quotation_delivery_attempt candidate
				WHERE candidate.quotation_id = q.id AND candidate.delivery_kind = 'FINAL'
				ORDER BY candidate.id DESC
				LIMIT 1
			)
			WHERE q.id = ?
			  AND latest.status = 'FAILED'
			  AND q.status = 'READY'
			""", (result, row) -> result.getString(1), quotationId);
		return rows.stream().findFirst();
	}

	// 方法：確認正式報價存在後才允許執行權杖撤銷。
	public boolean exists(long quotationId) {
		// 資料庫 API：以主鍵確認撤銷目標是既有正式報價。
		Integer count = jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation WHERE id = ?",
			Integer.class,
			quotationId
		);
		return count != null && count == 1;
	}

	// 方法：保存不含路徑、權杖、LINE 目的地或外部回應的管理操作稽核。
	@Transactional
	public void audit(long quotationId, String action, String outcome) {
		// 資料庫 API：以固定 JSON 結構記錄管理操作結果。
		jdbc.update("""
			INSERT INTO admin_audit_log (action, entity_type, entity_id, summary_json)
			VALUES (?, 'QUOTATION', ?, json_object('outcome', ?))
			""", action, String.valueOf(quotationId), outcome);
	}

	// 方法：依允許的查詢欄位建立固定 SQL 條件片段。
	private SqlFilter sqlFilter(QuotationFilter filter) {
		StringBuilder where = new StringBuilder("WHERE 1 = 1\n");
		List<Object> parameters = new ArrayList<>();
		appendLike(where, parameters, "q.quotation_no", filter.quotationNumber());
		appendLike(where, parameters, "q.company_name", filter.company());
		appendLike(where, parameters, "q.work_name", filter.work());
		if (filter.dateFrom() != null) {
			where.append("  AND q.quotation_date >= ?\n");
			parameters.add(filter.dateFrom());
		}
		if (filter.dateTo() != null) {
			where.append("  AND q.quotation_date <= ?\n");
			parameters.add(filter.dateTo());
		}
		if (filter.status() != null) {
			where.append("  AND q.status = ?\n");
			parameters.add(filter.status());
		}

		return new SqlFilter(where.toString(), List.copyOf(parameters));
	}

	// 方法：依草稿搜尋文字與狀態建立固定 SQL 條件片段。
	private DraftSqlFilter draftSqlFilter(DraftFilter filter) {
		StringBuilder where = new StringBuilder("WHERE 1 = 1\n");
		List<Object> parameters = new ArrayList<>();
		if (filter.search() != null) {
			String pattern = "%" + escapeLike(filter.search()) + "%";
			where.append("  AND (d.quotation_name LIKE ? ESCAPE '\\'\n")
				.append("    OR d.company_name LIKE ? ESCAPE '\\'\n")
				.append("    OR d.work_name LIKE ? ESCAPE '\\')\n");
			parameters.add(pattern);
			parameters.add(pattern);
			parameters.add(pattern);
		}
		if (filter.status() != null) {
			where.append("  AND d.status = ?\n");
			parameters.add(filter.status());
		}
		return new DraftSqlFilter(where.toString(), List.copyOf(parameters));
	}

	// 方法：以 LIKE 參數加入不區分欄位結構的文字搜尋條件。
	private void appendLike(StringBuilder where, List<Object> parameters, String column, String value) {
		if (value == null) return;

		where.append("  AND ").append(column).append(" LIKE ? ESCAPE '\\'\n");
		parameters.add("%" + escapeLike(value) + "%");
	}

	// 方法：跳脫 LIKE 萬用字元，避免使用者輸入改變搜尋範圍。
	private String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	// 方法：將正式報價清單列轉成穩定資料型別。
	private QuotationSummary quotationSummary(ResultSet result, int row) throws SQLException {
		return new QuotationSummary(
			result.getLong("id"),
			result.getString("quotation_no"),
			result.getString("quotation_name"),
			result.getString("company_name"),
			result.getString("work_name"),
			result.getString("quotation_date"),
			result.getString("scheme_code"),
			result.getString("currency"),
			decimal(result, "total_amount"),
			result.getString("status"),
			result.getString("xlsx_status"),
			result.getString("pdf_status"),
			result.getString("pdf_error"),
			result.getString("line_status"),
			result.getInt("line_attempt_count"),
			result.getString("line_error"),
			result.getString("created_at")
		);
	}

	// 方法：將正式報價抬頭轉成穩定資料型別。
	private QuotationHeader quotationHeader(ResultSet result, int row) throws SQLException {
		return new QuotationHeader(
			result.getLong("id"),
			result.getString("quotation_no"),
			result.getString("quotation_name"),
			result.getString("company_name"),
			result.getString("work_name"),
			result.getString("sales_representative"),
			result.getString("quotation_date"),
			result.getString("valid_until"),
			result.getString("scheme_code"),
			result.getString("currency"),
			decimal(result, "subtotal"),
			decimal(result, "tax_rate"),
			decimal(result, "tax_amount"),
			decimal(result, "total_amount"),
			result.getString("status"),
			result.getString("created_at")
		);
	}

	// 方法：將客戶可見報價列轉成穩定資料型別。
	private QuotationLine quotationLine(ResultSet result, int row) throws SQLException {
		return new QuotationLine(
			result.getInt("line_number"),
			result.getString("line_kind"),
			result.getString("item_code_snapshot"),
			result.getString("item_name_snapshot"),
			result.getString("specification_snapshot"),
			decimalOrNull(result, "quantity"),
			result.getString("unit_snapshot"),
			decimal(result, "unit_price_snapshot"),
			decimalOrNull(result, "line_amount"),
			result.getString("remark_snapshot")
		);
	}

	// 方法：將檔案狀態轉成穩定資料型別。
	private FileState fileState(ResultSet result, int row) throws SQLException {
		long fileSize = result.getLong("file_size");
		return new FileState(
			result.getString("file_kind"),
			result.getString("status"),
			result.getString("error_message"),
			result.wasNull() ? null : fileSize
		);
	}

	// 方法：將最新交付狀態轉成穩定資料型別。
	private DeliveryState deliveryState(ResultSet result, int row) throws SQLException {
		return new DeliveryState(
			result.getString("status"),
			result.getInt("attempt_count"),
			result.getString("error_message")
		);
	}

	// 方法：將草稿清單列轉成不含擁有者與路徑的穩定資料型別。
	private DraftSummary draftSummary(ResultSet result, int row) throws SQLException {
		return new DraftSummary(
			result.getLong("id"),
			result.getString("quotation_name"),
			result.getString("company_name"),
			result.getString("work_name"),
			result.getString("scheme_code"),
			result.getString("status"),
			result.getInt("revision"),
			result.getInt("has_selected_image") == 1,
			result.getString("updated_at")
		);
	}

	// 方法：將草稿抬頭列轉成內部資料型別。
	private DraftHeader draftHeader(ResultSet result, int row) throws SQLException {
		return new DraftHeader(
			result.getLong("id"),
			result.getString("quotation_name"),
			result.getString("company_name"),
			result.getString("work_name"),
			result.getString("sales_representative"),
			result.getString("contact_name"),
			result.getString("customer_phone"),
			result.getString("customer_email"),
			result.getString("project_location"),
			result.getString("additional_header"),
			result.getString("scheme_code"),
			result.getString("source_type"),
			result.getString("source_id"),
			result.getString("requester_id"),
			result.getString("status"),
			result.getInt("revision"),
			result.getInt("image_declined") == 1,
			result.getString("created_at"),
			result.getString("updated_at")
		);
	}

	// 方法：將草稿品項列轉成完整快照型別。
	private DraftItem draftItem(ResultSet result, int row) throws SQLException {
		return new DraftItem(
			result.getLong("id"),
			result.getString("item_kind"),
			result.getString("item_code_snapshot"),
			result.getString("item_name_snapshot"),
			result.getString("specification_snapshot"),
			decimalOrNull(result, "quantity"),
			result.getString("unit_snapshot"),
			decimalOrNull(result, "unit_price_snapshot"),
			result.getString("remark_snapshot"),
			result.getString("source_text"),
			decimalOrNull(result, "confidence"),
			result.getInt("is_removed") == 1,
			result.getInt("display_order")
		);
	}

	// 方法：將草稿欄位證據轉成不含訊息識別碼的檢視型別。
	private DraftFieldEvidence draftFieldEvidence(ResultSet result, int row) throws SQLException {
		return new DraftFieldEvidence(
			result.getString("field_key"),
			result.getString("field_value"),
			result.getString("source_text"),
			decimalOrNull(result, "confidence"),
			result.getString("confirmation_status")
		);
	}

	// 方法：將管理圖片定位轉成僅供服務層解析的內部型別。
	private AdminImageRecord adminImageRecord(ResultSet result, int row) throws SQLException {
		return new AdminImageRecord(
			result.getString("locator"),
			result.getString("content_type"),
			result.getLong("file_size")
		);
	}

	// 方法：將稽核資料列轉成欄位白名單型別。
	private AuditRecord auditRecord(ResultSet result, int row) throws SQLException {
		return new AuditRecord(
			result.getString("action"),
			result.getString("outcome"),
			result.getString("created_at")
		);
	}

	// 方法：將 SQLite NUMERIC 欄位轉成精確十進位數。
	private BigDecimal decimal(ResultSet result, String column) throws SQLException {
		String value = result.getString(column);
		if (value == null) throw new QuotationAdminException("DATA_INTEGRITY_ERROR", "正式報價金額不完整");

		return new BigDecimal(value);
	}

	// 方法：將可空 SQLite NUMERIC 欄位轉成精確十進位數。
	private BigDecimal decimalOrNull(ResultSet result, String column) throws SQLException {
		String value = result.getString(column);
		return value == null ? null : new BigDecimal(value);
	}

	public record QuotationFilter(
		String quotationNumber,
		String company,
		String work,
		String dateFrom,
		String dateTo,
		String status,
		int page,
		int pageSize
	) {}

	public record QuotationPage(List<QuotationSummary> data, long totalItems) {}

	public record DraftFilter(String search, String status, int page, int pageSize) {}

	public record DraftPage(List<DraftSummary> data, long totalItems) {}

	public record DraftSummary(
		long id,
		String quotationName,
		String companyName,
		String workName,
		String schemeCode,
		String status,
		int revision,
		boolean hasSelectedImage,
		String updatedAt
	) {}

	public record DraftHeader(
		long id,
		String quotationName,
		String companyName,
		String workName,
		String salesRepresentative,
		String contactName,
		String customerPhone,
		String customerEmail,
		String projectLocation,
		String additionalHeader,
		String schemeCode,
		String sourceType,
		String sourceId,
		String requesterId,
		String status,
		int revision,
		boolean imageDeclined,
		String createdAt,
		String updatedAt
	) {}

	public record DraftItem(
		long id,
		String itemKind,
		String itemCode,
		String itemName,
		String specification,
		BigDecimal quantity,
		String unit,
		BigDecimal unitPrice,
		String remark,
		String sourceText,
		BigDecimal confidence,
		boolean removed,
		int displayOrder
	) {}

	public record DraftFieldEvidence(
		String fieldKey,
		String value,
		String sourceText,
		BigDecimal confidence,
		String confirmationStatus
	) {}

	public record QuotationSummary(
		long id,
		String quotationNumber,
		String quotationName,
		String companyName,
		String workName,
		String quotationDate,
		String schemeCode,
		String currency,
		BigDecimal totalAmount,
		String status,
		String xlsxStatus,
		String pdfStatus,
		String pdfError,
		String lineStatus,
		int lineAttemptCount,
		String lineError,
		String createdAt
	) {}

	public record QuotationHeader(
		long id,
		String quotationNumber,
		String quotationName,
		String companyName,
		String workName,
		String salesRepresentative,
		String quotationDate,
		String validUntil,
		String schemeCode,
		String currency,
		BigDecimal subtotal,
		BigDecimal taxRate,
		BigDecimal taxAmount,
		BigDecimal totalAmount,
		String status,
		String createdAt
	) {}

	public record QuotationLine(
		int lineNumber,
		String lineKind,
		String itemCode,
		String itemName,
		String specification,
		BigDecimal quantity,
		String unit,
		BigDecimal unitPrice,
		BigDecimal lineAmount,
		String remark
	) {}

	public record FileState(String fileKind, String status, String errorMessage, Long fileSize) {}

	public record DeliveryState(String status, int attemptCount, String errorMessage) {}

	public record AdminFileRecord(String relativePath, String contentType, long fileSize) {}

	public record AdminImageRecord(String locator, String contentType, long fileSize) {}

	public record AuditRecord(String action, String outcome, String createdAt) {}

	private record SqlFilter(String whereClause, List<Object> parameters) {}

	private record DraftSqlFilter(String whereClause, List<Object> parameters) {}
}
