package dev.miudog.linebotcommercial.service.quotation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只由正式不可變快照重建背景產檔命令，不依賴記憶體草稿或 AI 回應內容。
 */
@Repository
public class QuotationGenerationSnapshotRepository {

	private final JdbcTemplate jdbc;

	// 方法：建立正式報價快照讀取層。
	public QuotationGenerationSnapshotRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 方法：以正式報價、明細及持久化工作重建完整 Excel／PDF／LINE 命令。
	public QuotationConfirmedGenerationCommand load(long quotationId) {
		HeaderSnapshot snapshot = requireHeader(quotationId);
		List<QuotationCalculationResult.QuotationLine> internalLines = lines(quotationId, snapshot.schemeCode());
		List<QuotationCalculationResult.QuotationLine> customerLines = internalLines.stream()
			.filter(QuotationCalculationResult.QuotationLine::customerVisible)
			.toList();
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			snapshot.schemeCode(),
			internalLines,
			customerLines,
			snapshot.subtotal(),
			snapshot.taxAmount(),
			snapshot.totalAmount(),
			"SUMMARY_ONLY".equals(snapshot.calculationVisibility())
				? QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY
				: QuotationCalculationResult.CustomerPresentation.DETAIL
		);
		QuotationConfirmationResult confirmation = new QuotationConfirmationResult(
			snapshot.quotationId(),
			snapshot.quotationNumber(),
			snapshot.quotationDate(),
			snapshot.validUntil(),
			snapshot.sequenceNumber(),
			folderName(snapshot.sequenceDate(), snapshot.sequenceNumber()),
			snapshot.quotationName()
		);
		QuotationWorkbookService.Header header = new QuotationWorkbookService.Header(
			snapshot.quotationNumber(),
			snapshot.quotationDate().toString(),
			blankIfNull(snapshot.customerName()),
			phoneFax(snapshot.customerPhone(), snapshot.customerFax()),
			blankIfNull(snapshot.customerEmail()),
			blankIfNull(snapshot.contactName()),
			blankIfNull(snapshot.projectLocation()),
			blankIfNull(snapshot.salesRepresentative()),
			snapshot.validUntil().toString()
		);
		return new QuotationConfirmedGenerationCommand(
			confirmation,
			calculation,
			header,
			snapshot.destinationId(),
			blankIfNull(snapshot.additionalHeader())
		);
	}

	// 方法：讀取完整正式抬頭、金額、方案及背景交付目的地。
	private HeaderSnapshot requireHeader(long quotationId) {
		// 資料庫 API：查詢僅使用確認交易保存的 quotation 與 generation job 快照。
		List<HeaderSnapshot> rows = jdbc.query("""
			SELECT q.id, q.quotation_no, q.quotation_name, q.sequence_date,
				q.sequence_number, q.quotation_date, q.valid_until,
				q.customer_name, q.customer_phone, q.customer_fax,
				q.customer_email, q.contact_name, q.project_location,
				q.sales_representative, q.additional_header,
				q.subtotal, q.tax_amount, q.total_amount,
				s.code AS scheme_code, s.calculation_visibility, j.destination_id
			FROM quotation q
			JOIN quotation_scheme s ON s.id = q.scheme_id
			JOIN quotation_generation_job j ON j.quotation_id = q.id
			WHERE q.id = ?
			""", this::header, quotationId);
		if (rows.size() != 1) {
			throw new QuotationGenerationException(
				"GENERATION_SNAPSHOT_NOT_FOUND",
				"找不到可重建的正式報價背景工作快照",
				null
			);
		}
		return rows.getFirst();
	}

	// 方法：依正式行號重建程式已計算的內部與客戶列。
	private List<QuotationCalculationResult.QuotationLine> lines(long quotationId, String schemeCode) {
		// 資料庫 API：明細只由 quotation_line 快照重建，不重新查詢可能已變更的品項主檔。
		return jdbc.query("""
			SELECT line_number, line_kind, visibility, item_code_snapshot,
				item_name_snapshot, specification_snapshot, quantity, unit_snapshot,
				unit_price_snapshot, line_amount, remark_snapshot,
				COALESCE(json_extract(calculation_detail_json, '$.mode'), 'DIRECT') AS calculation_mode
			FROM quotation_line
			WHERE quotation_id = ?
			ORDER BY line_number
			""", (result, row) -> line(result, schemeCode), quotationId);
	}

	// 方法：將抬頭查詢列轉成不可變資料。
	private HeaderSnapshot header(ResultSet result, int row) throws SQLException {
		return new HeaderSnapshot(
			result.getLong("id"),
			required(result.getString("quotation_no"), "正式報價單號"),
			required(result.getString("quotation_name"), "正式報價檔名"),
			required(result.getString("sequence_date"), "報價流水日期"),
			result.getInt("sequence_number"),
			LocalDate.parse(required(result.getString("quotation_date"), "報價日期")),
			LocalDate.parse(required(result.getString("valid_until"), "報價有效期限")),
			result.getString("customer_name"),
			result.getString("customer_phone"),
			result.getString("customer_fax"),
			result.getString("customer_email"),
			result.getString("contact_name"),
			result.getString("project_location"),
			result.getString("sales_representative"),
			result.getString("additional_header"),
			decimal(result, "subtotal"),
			decimal(result, "tax_amount"),
			decimal(result, "total_amount"),
			required(result.getString("scheme_code"), "報價方案"),
			required(result.getString("calculation_visibility"), "對客呈現模式"),
			required(result.getString("destination_id"), "LINE 交付目的地")
		);
	}

	// 方法：將單筆正式明細轉回產檔所需的確定性計價列。
	private QuotationCalculationResult.QuotationLine line(ResultSet result, String schemeCode)
		throws SQLException {
		String lineKind = result.getString("line_kind");
		return new QuotationCalculationResult.QuotationLine(
			result.getString("item_code_snapshot"),
			required(result.getString("item_name_snapshot"), "正式品項名稱"),
			blankIfNull(result.getString("specification_snapshot")),
			required(result.getString("unit_snapshot"), "正式品項單位"),
			decimal(result, "unit_price_snapshot"),
			decimalOrNull(result, "quantity"),
			decimalOrNull(result, "line_amount"),
			blankIfNull(result.getString("remark_snapshot")),
			result.getInt("line_number"),
			required(result.getString("calculation_mode"), "正式計價模式"),
			origin(lineKind, schemeCode),
			"CUSTOMER".equals(result.getString("visibility"))
		);
	}

	// 方法：由正式列種類及方案還原固定、臨時或動態來源。
	private QuotationCalculationResult.LineOrigin origin(String lineKind, String schemeCode) {
		if ("STANDARD".equals(lineKind)) return QuotationCalculationResult.LineOrigin.STANDARD;

		return List.of("CNS", "GENERAL").contains(schemeCode)
			? QuotationCalculationResult.LineOrigin.TEMPORARY
			: QuotationCalculationResult.LineOrigin.DYNAMIC;
	}

	// 方法：依正式日期及數字重建不含公司個資的日期流水號資料夾名稱。
	private String folderName(String sequenceDate, int sequenceNumber) {
		return sequenceDate.replace("-", "") + "-" + String.format("%02d", sequenceNumber);
	}

	// 方法：將電話及傳真以原報表既有格式組合。
	private String phoneFax(String phone, String fax) {
		String safePhone = blankIfNull(phone);
		String safeFax = blankIfNull(fax);
		if (safeFax.isBlank()) return safePhone;

		return safePhone.isBlank() ? safeFax : safePhone + " / " + safeFax;
	}

	// 方法：讀取精確金額，不允許正式快照缺值。
	private BigDecimal decimal(ResultSet result, String column) throws SQLException {
		String value = result.getString(column);
		if (value == null) {
			throw new QuotationGenerationException(
				"INCOMPLETE_GENERATION_SNAPSHOT",
				"正式報價金額快照不完整",
				null
			);
		}
		return new BigDecimal(value);
	}

	// 方法：保留未使用固定品項的空白數量與複價。
	private BigDecimal decimalOrNull(ResultSet result, String column) throws SQLException {
		String value = result.getString(column);
		return value == null ? null : new BigDecimal(value);
	}

	// 方法：驗證不可變快照中的必要文字。
	private String required(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new QuotationGenerationException(
				"INCOMPLETE_GENERATION_SNAPSHOT",
				label + "不可留空",
				null
			);
		}
		return value;
	}

	// 方法：正式報表允許未提供的可選抬頭保留空字串。
	private String blankIfNull(String value) {
		return value == null ? "" : value;
	}

	private record HeaderSnapshot(
		long quotationId,
		String quotationNumber,
		String quotationName,
		String sequenceDate,
		int sequenceNumber,
		LocalDate quotationDate,
		LocalDate validUntil,
		String customerName,
		String customerPhone,
		String customerFax,
		String customerEmail,
		String contactName,
		String projectLocation,
		String salesRepresentative,
		String additionalHeader,
		BigDecimal subtotal,
		BigDecimal taxAmount,
		BigDecimal totalAmount,
		String schemeCode,
		String calculationVisibility,
		String destinationId
	) {}
}
