package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 在單一 SQLite 交易中配置每日流水號並保存不可變報價快照。
 */
@Service
public class QuotationConfirmationService {

	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final QuotationGenerationJobRepository generationJobs;

	// 方法：建立正式確認交易服務。
	public QuotationConfirmationService(
		JdbcTemplate jdbc,
		PlatformTransactionManager transactionManager,
		QuotationGenerationJobRepository generationJobs
	) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.generationJobs = generationJobs;
	}

	// 方法：驗證確認意圖，並以冪等交易配置流水號與建立快照。
	public QuotationConfirmationResult confirm(QuotationConfirmationCommand command) {
		validateCommand(command);
		// 外部呼叫：由 Spring 交易管理器在同一 SQLite 交易中完成配號與快照。
		QuotationConfirmationResult result = transactions.execute(
			status -> confirmInTransaction(command)
		);
		if (result == null) throw error("正式確認交易沒有回傳結果");

		return result;
	}

	// 方法：執行已驗證確認意圖的資料庫交易。
	private QuotationConfirmationResult confirmInTransaction(QuotationConfirmationCommand command) {
		QuotationDraftSnapshot draft = command.confirmationIntent().draft();
		QuotationConfirmationResult existing = findByDraftId(draft.draftId());
		if (existing != null) {
			generationJobs.enqueueIfAbsent(existing.quotationId(), MDC.get("requestId"));
			return existing;
		}

		DraftRecord draftRecord = requireConfirmableDraft(
			draft.draftId(),
			draft.revision(),
			command.calculation().schemeCode()
		);
		String eventId = required(command.confirmationIntent().confirmationEventId(), "確認事件識別");
		claimConfirmationEvent(eventId, draft.draftId());

		LocalDate quotationDate = LocalDate.now(BUSINESS_ZONE);
		int sequence = allocateSequence(quotationDate);
		String compactDate = quotationDate.format(COMPACT_DATE);
		String folderName = compactDate + "-" + String.format("%02d", sequence);
		String quotationNumber = "SALES".equals(draftRecord.schemeCode())
			? "S" + compactDate + String.format("%02d", sequence)
			: compactDate + String.format("%02d", sequence);
		String fileBaseName = draftRecord.companyName() + "-" + draftRecord.workName() + " " + folderName;
		LocalDate validUntil = quotationDate.plusDays(15);

		long quotationId = insertQuotation(
			draftRecord,
			command.calculation(),
			quotationNumber,
			quotationDate,
			validUntil,
			sequence,
			fileBaseName
		);
		insertLines(quotationId, command.calculation().internalLines());
		generationJobs.enqueueIfAbsent(quotationId, MDC.get("requestId"));
		markDraftAndEventConfirmed(draft.draftId(), draft.revision(), eventId);

		return new QuotationConfirmationResult(
			quotationId,
			quotationNumber,
			quotationDate,
			validUntil,
			sequence,
			folderName,
			fileBaseName
		);
	}

	// 方法：確認命令只包含已確認草稿與同方案的可信程式計價結果。
	private void validateCommand(QuotationConfirmationCommand command) {
		if (command == null) throw error("正式確認命令不可為 null");

		if (command.confirmationIntent() == null || command.confirmationIntent().draft() == null) {
			throw error("正式確認意圖不可為 null");
		}
		if (command.calculation() == null) throw error("程式計價結果不可為 null");

		QuotationDraftSnapshot draft = command.confirmationIntent().draft();
		if (draft.status() != QuotationDraftStatus.CONFIRMED) throw error("草稿尚未完成使用者確認");

		if (!required(draft.schemeCode(), "草稿報價方案").equals(command.calculation().schemeCode())) {
			throw error("草稿與計價結果的報價方案不一致");
		}
	}

	// 方法：讀取已確認草稿及目前啟用中的方案範本。
	private DraftRecord requireConfirmableDraft(long draftId, int confirmedRevision, String schemeCode) {
		// 外部呼叫：從資料庫讀取正式確認所需的可信抬頭與範本識別。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT d.id, d.company_name, d.work_name, d.contact_name,
				d.customer_phone, d.customer_email, d.project_location,
				d.status, d.revision,
				(SELECT field_value FROM quotation_draft_field
				 WHERE draft_id = d.id AND field_key = 'fax') AS customer_fax,
				(SELECT field_value FROM quotation_draft_field
				 WHERE draft_id = d.id AND field_key = 'additionalHeader') AS additional_header,
				(SELECT field_value FROM quotation_draft_field
				 WHERE draft_id = d.id AND field_key = 'salesRepresentative') AS sales_representative,
				s.code AS scheme_code, s.id AS scheme_id, t.id AS template_id
			FROM quotation_draft d
			JOIN quotation_scheme s ON s.id = d.scheme_id
			JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE d.id = ?
			""", draftId);
		if (rows.size() != 1) throw error("找不到可使用的確認草稿或啟用範本");

		Map<String, Object> row = rows.getFirst();
		if (!"AWAITING_CONFIRMATION".equals(row.get("status"))) throw error("資料庫草稿不在可確認狀態");

		int persistedRevision = ((Number) row.get("revision")).intValue();
		if (confirmedRevision != persistedRevision + 1) throw error("草稿修訂版本已變更，請重新預覽確認");

		String companyName = required((String) row.get("company_name"), "公司名稱");
		String workName = required((String) row.get("work_name"), "工作名稱");
		String salesRepresentative = required((String) row.get("sales_representative"), "業務承辦");
		String trustedSchemeCode = (String) row.get("scheme_code");
		if (!schemeCode.equals(trustedSchemeCode)) throw error("草稿方案已變更，請重新預覽");

		return new DraftRecord(
			draftId,
			((Number) row.get("scheme_id")).longValue(),
			((Number) row.get("template_id")).longValue(),
			trustedSchemeCode,
			companyName,
			workName,
			(String) row.get("customer_phone"),
			(String) row.get("customer_fax"),
			(String) row.get("customer_email"),
			(String) row.get("contact_name"),
			(String) row.get("project_location"),
			salesRepresentative,
			(String) row.get("additional_header")
		);
	}

	// 方法：宣告確認事件的唯一處理權，避免 postback 重送或跨草稿重用。
	private void claimConfirmationEvent(String eventId, long draftId) {
		// 外部呼叫：以事件主鍵建立冪等處理紀錄。
		int inserted = jdbc.update("""
			INSERT INTO quotation_event_receipt (
				event_id, draft_id, event_type, result_status
			)
			VALUES (?, ?, 'POSTBACK', 'RECEIVED')
			ON CONFLICT (event_id) DO NOTHING
			""", eventId, draftId);
		if (inserted != 1) throw error("確認事件已被處理");
	}

	// 方法：以 YYYY-MM-DD 為唯一鍵安全遞增當日流水號，第 100 張起自然擴展位數。
	private int allocateSequence(LocalDate quotationDate) {
		// 外部呼叫：建立或原子遞增當日流水號游標。
		int changed = jdbc.update("""
			INSERT INTO quotation_daily_sequence (sequence_date, last_sequence, updated_at)
			VALUES (?, 1, CURRENT_TIMESTAMP)
			ON CONFLICT (sequence_date) DO UPDATE SET
				last_sequence = last_sequence + 1,
				updated_at = CURRENT_TIMESTAMP
			""", quotationDate.toString());
		if (changed != 1) throw error("無法配置當日報價流水號");

		// 外部呼叫：讀回交易內剛配置的流水號。
		Integer sequence = jdbc.queryForObject(
			"SELECT last_sequence FROM quotation_daily_sequence WHERE sequence_date = ?",
			Integer.class,
			quotationDate.toString()
		);
		if (sequence == null) throw error("無法取得當日報價流水號");

		return sequence;
	}

	// 方法：建立正式報價抬頭、日期、流水號及金額快照。
	private long insertQuotation(
		DraftRecord draft,
		QuotationCalculationResult calculation,
		String quotationNumber,
		LocalDate quotationDate,
		LocalDate validUntil,
		int sequence,
		String quotationName
	) {
		KeyHolder keys = new GeneratedKeyHolder();
		// 外部呼叫：新增不依賴後續主檔變更的正式報價快照。
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO quotation (
					draft_id, revision, quotation_no, quotation_name,
					sequence_date, sequence_number, company_name, work_name,
					quotation_date, valid_until, scheme_id, template_id,
					customer_name, customer_phone, customer_fax, customer_email,
					contact_name, project_location, sales_representative, additional_header,
					subtotal, tax_rate, tax_amount, total_amount, status
				)
				VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.05, ?, ?, 'CONFIRMED')
				""", Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, draft.draftId());
			statement.setString(2, quotationNumber);
			statement.setString(3, quotationName);
			statement.setString(4, quotationDate.toString());
			statement.setInt(5, sequence);
			statement.setString(6, draft.companyName());
			statement.setString(7, draft.workName());
			statement.setString(8, quotationDate.toString());
			statement.setString(9, validUntil.toString());
			statement.setLong(10, draft.schemeId());
			statement.setLong(11, draft.templateId());
			statement.setString(12, draft.companyName());
			statement.setString(13, draft.customerPhone());
			statement.setString(14, draft.customerFax());
			statement.setString(15, draft.customerEmail());
			statement.setString(16, draft.contactName());
			statement.setString(17, draft.projectLocation());
			statement.setString(18, draft.salesRepresentative());
			statement.setString(19, draft.additionalHeader());
			statement.setBigDecimal(20, calculation.subtotal());
			statement.setBigDecimal(21, calculation.tax());
			statement.setBigDecimal(22, calculation.total());
			return statement;

		}, keys);
		Number key = keys.getKey();
		if (key == null) throw error("建立正式報價後未取得主鍵");

		return key.longValue();
	}

	// 方法：依畫面順序保存每一列固定資料與程式計算結果。
	private void insertLines(
		long quotationId,
		List<QuotationCalculationResult.QuotationLine> lines
	) {
		for (int index = 0; index < lines.size(); index++) {
			QuotationCalculationResult.QuotationLine line = lines.get(index);
			String lineKind = line.origin() == QuotationCalculationResult.LineOrigin.STANDARD
				? "STANDARD"
				: "CUSTOM";
			String visibility = line.customerVisible() ? "CUSTOMER" : "INTERNAL";
			// 外部呼叫：寫入單一不可變報價明細快照，空白數量與複價保持 null。
			jdbc.update("""
				INSERT INTO quotation_line (
					quotation_id, line_number, line_kind, visibility,
					item_code_snapshot, item_name_snapshot, specification_snapshot,
					quantity, unit_snapshot, unit_price_snapshot, line_amount,
					remark_snapshot, calculation_detail_json
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{"mode":"DIRECT"}')
				""",
				quotationId,
				index + 1,
				lineKind,
				visibility,
				line.itemCode(),
				line.itemName(),
				line.specification(),
				line.quantity(),
				line.unit(),
				line.unitPrice(),
				line.lineAmount(),
				line.remark()
			);
		}
	}

	// 方法：完成草稿與確認事件狀態，供後續檔案產生流程接手。
	private void markDraftAndEventConfirmed(long draftId, int confirmedRevision, String eventId) {
		// 外部呼叫：保存草稿正式確認時間。
		int changed = jdbc.update("""
			UPDATE quotation_draft
			SET status = 'CONFIRMED', revision = ?,
				confirmed_at = COALESCE(confirmed_at, CURRENT_TIMESTAMP),
				updated_at = CURRENT_TIMESTAMP
			WHERE id = ? AND status = 'AWAITING_CONFIRMATION' AND revision = ?
			""", confirmedRevision, draftId, confirmedRevision - 1);
		if (changed != 1) throw error("草稿修訂版本已變更，請重新預覽確認");

		// 外部呼叫：標記確認事件已完整處理。
		jdbc.update("""
			UPDATE quotation_event_receipt
			SET result_status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP
			WHERE event_id = ?
			""", eventId);
	}

	// 方法：依草稿識別讀取已存在的冪等確認結果。
	private QuotationConfirmationResult findByDraftId(long draftId) {
		// 外部呼叫：查詢同一草稿是否已配置正式報價。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT id, quotation_no, quotation_date, valid_until,
				sequence_number, sequence_date, company_name, work_name
			FROM quotation
			WHERE draft_id = ?
			""", draftId);
		if (rows.isEmpty()) return null;

		if (rows.size() != 1) throw error("同一草稿存在多筆正式報價");

		Map<String, Object> row = rows.getFirst();
		String compactDate = ((String) row.get("sequence_date")).replace("-", "");
		int sequence = ((Number) row.get("sequence_number")).intValue();
		String folderName = compactDate + "-" + String.format("%02d", sequence);
		String fileBaseName = row.get("company_name") + "-" + row.get("work_name") + " " + folderName;
		return new QuotationConfirmationResult(
			((Number) row.get("id")).longValue(),
			(String) row.get("quotation_no"),
			LocalDate.parse((String) row.get("quotation_date")),
			LocalDate.parse((String) row.get("valid_until")),
			sequence,
			folderName,
			fileBaseName
		);
	}

	// 方法：驗證必要文字欄位並移除頭尾空白。
	private String required(String value, String label) {
		if (value == null || value.isBlank()) throw error(label + "不可留空");

		return value.trim();
	}

	// 方法：建立正式確認流程錯誤。
	private QuotationConfirmationException error(String message) {
		return new QuotationConfirmationException(message);
	}

	private record DraftRecord(
		long draftId,
		long schemeId,
		long templateId,
		String schemeCode,
		String companyName,
		String workName,
		String customerPhone,
		String customerFax,
		String customerEmail,
		String contactName,
		String projectLocation,
		String salesRepresentative,
		String additionalHeader
	) {}
}
