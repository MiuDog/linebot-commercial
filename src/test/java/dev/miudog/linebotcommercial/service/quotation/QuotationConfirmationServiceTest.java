package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-confirmation-test",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationConfirmationServiceTest {

	@Autowired
	QuotationConfirmationService service;

	@Autowired
	JdbcTemplate jdbcTemplate;

	// 驗證正式確認後才配置台北當日流水號、日期與不可變價格快照。
	@Test
	@Transactional
	void allocatesSequenceAndPersistsImmutableSnapshotAfterConfirmation() {
		long draftId = insertConfirmedDraft("GENERAL", "正定工程股份有限公司", "中壢案");
		QuotationConfirmationResult result = service.confirm(command(draftId, "GENERAL", "evt-" + UUID.randomUUID()));
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));

		assertThat(result.sequenceNumber()).isEqualTo(1);
		assertThat(result.folderName()).isEqualTo(today.toString().replace("-", "") + "-01");
		assertThat(result.quotationNumber()).isEqualTo(today.toString().replace("-", "") + "01");
		assertThat(result.fileBaseName()).isEqualTo("正定工程股份有限公司-中壢案 " + result.folderName());
		assertThat(result.quotationDate()).isEqualTo(today);
		assertThat(result.validUntil()).isEqualTo(today.plusDays(15));

		// 外部呼叫：讀取正式報價與明細快照以確認金額與空白數量列完整保存。
		Map<String, Object> quotation = jdbcTemplate.queryForMap(
			"""
			SELECT subtotal, tax_amount, total_amount, status, customer_name,
				customer_phone, customer_fax, customer_email, contact_name, project_location,
				additional_header, sales_representative
			FROM quotation WHERE id = ?
			""",
			result.quotationId()
		);
		List<Map<String, Object>> lines = jdbcTemplate.queryForList(
			"SELECT quantity, line_amount FROM quotation_line WHERE quotation_id = ? ORDER BY line_number",
			result.quotationId()
		);

		assertThat(((Number) quotation.get("subtotal")).doubleValue()).isEqualTo(180.00);
		assertThat(((Number) quotation.get("tax_amount")).doubleValue()).isEqualTo(9.00);
		assertThat(((Number) quotation.get("total_amount")).doubleValue()).isEqualTo(189.00);
		assertThat(quotation.get("status")).isEqualTo("CONFIRMED");
		assertThat(quotation.get("additional_header")).isEqualTo("Tax ID 12345678");
		assertThat(quotation.get("sales_representative")).isEqualTo("陳業務");
		assertThat(quotation)
			.containsEntry("customer_name", "正定工程股份有限公司")
			.containsEntry("customer_phone", "02-12345678")
			.containsEntry("customer_fax", "02-87654321")
			.containsEntry("customer_email", "quote@example.test")
			.containsEntry("contact_name", "王先生")
			.containsEntry("project_location", "台北市");
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0).get("quantity")).isNull();
		assertThat(lines.get(0).get("line_amount")).isNull();
	}

	// 驗證同一草稿或相同確認事件重送時不會重複消耗流水號。
	@Test
	@Transactional
	void confirmationIsIdempotentAndNextDraftGetsNextSequence() {
		String eventId = "evt-" + UUID.randomUUID();
		long firstDraftId = insertConfirmedDraft("GENERAL", "甲公司", "第一案");
		QuotationConfirmationCommand firstCommand = command(firstDraftId, "GENERAL", eventId);

		QuotationConfirmationResult first = service.confirm(firstCommand);
		QuotationConfirmationResult repeated = service.confirm(firstCommand);
		long secondDraftId = insertConfirmedDraft("GENERAL", "乙公司", "第二案");
		QuotationConfirmationResult second = service.confirm(
			command(secondDraftId, "GENERAL", "evt-" + UUID.randomUUID())
		);

		assertThat(repeated.quotationId()).isEqualTo(first.quotationId());
		assertThat(repeated.sequenceNumber()).isEqualTo(first.sequenceNumber());
		assertThat(second.sequenceNumber()).isEqualTo(2);
	}

	// 驗證缺少公司或工作名稱時在交易配號前拒絕，不會留下當日流水號。
	@Test
	@Transactional
	void missingRequiredHeadingDoesNotConsumeSequence() {
		long draftId = insertConfirmedDraft("GENERAL", "", "未命名案件");

		assertThatThrownBy(
			() -> service.confirm(command(draftId, "GENERAL", "evt-" + UUID.randomUUID()))
		)
			.isInstanceOf(QuotationConfirmationException.class)
			.hasMessageContaining("公司名稱");

		// 外部呼叫：確認驗證失敗沒有建立每日流水號游標。
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quotation_daily_sequence", Integer.class);
		assertThat(count).isZero();
	}

	// 驗證銷售格式沿用同一日流水號，但正式報價單號加上 S 前綴。
	@Test
	@Transactional
	void salesQuotationUsesSNumberPrefix() {
		long draftId = insertConfirmedDraft("SALES", "丙公司", "材料銷售");
		QuotationConfirmationResult result = service.confirm(
			command(draftId, "SALES", "evt-" + UUID.randomUUID())
		);

		assertThat(result.quotationNumber()).startsWith("S");
		assertThat(result.folderName()).doesNotStartWith("S");
	}

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void failedSnapshotInsertRollsBackTerminalStateEventAndSequenceAndAllowsRetry() {
		long draftId = insertConfirmedDraft("GENERAL", "正定工程", "重試工程");
		String eventId = "evt-rollback-" + UUID.randomUUID();
		QuotationConfirmationCommand invalid = commandWithInvalidLine(draftId, eventId);

		assertThatThrownBy(() -> service.confirm(invalid)).isInstanceOf(RuntimeException.class);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM quotation_draft WHERE id = ?",
			String.class,
			draftId
		)).isEqualTo("AWAITING_CONFIRMATION");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quotation_daily_sequence", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM quotation_event_receipt WHERE event_id = ?",
			Integer.class,
			eventId
		)).isZero();

		QuotationConfirmationResult retried = service.confirm(command(draftId, "GENERAL", eventId));
		assertThat(retried.sequenceNumber()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"SELECT status FROM quotation_draft WHERE id = ?",
			String.class,
			draftId
		)).isEqualTo("CONFIRMED");
	}

	// 建立已通過預覽確認的資料庫草稿。
	private long insertConfirmedDraft(String schemeCode, String companyName, String workName) {
		String draftKey = "draft-" + UUID.randomUUID();
		// 外部呼叫：新增確認狀態草稿供交易配號測試使用。
		jdbcTemplate.update(
			"""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name,
				work_name, quotation_name, contact_name, customer_phone,
				customer_email, project_location, scheme_id, status, revision, confirmation_revision
			)
			SELECT ?, 'user', ?, 'U001', ?, ?, ?, '王先生', '02-12345678',
				'quote@example.test', '台北市', id, 'AWAITING_CONFIRMATION', 2, 2
			FROM quotation_scheme
			WHERE code = ?
			""",
			draftKey,
			"U-" + UUID.randomUUID(),
			companyName,
			workName,
			companyName + "-" + workName,
			schemeCode
		);
		// 外部呼叫：讀回測試草稿主鍵。
		long draftId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			draftKey
		);
		jdbcTemplate.update("""
			INSERT INTO quotation_draft_field (draft_id, field_key, field_value)
			VALUES
				(?, 'fax', '02-87654321'),
				(?, 'additionalHeader', 'Tax ID 12345678'),
				(?, 'salesRepresentative', '陳業務')
			""", draftId, draftId, draftId);
		return draftId;
	}

	private QuotationConfirmationCommand commandWithInvalidLine(long draftId, String eventId) {
		QuotationConfirmationCommand valid = command(draftId, "GENERAL", eventId);
		QuotationCalculationResult invalidCalculation = new QuotationCalculationResult(
			"GENERAL",
			List.of(line("BROKEN", null, BigDecimal.ONE, BigDecimal.ONE, 1)),
			List.of(),
			BigDecimal.ONE,
			BigDecimal.ZERO,
			BigDecimal.ONE,
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
		return new QuotationConfirmationCommand(valid.confirmationIntent(), invalidCalculation);
	}

	// 建立含空白固定列與已計價固定列的確認命令。
	private QuotationConfirmationCommand command(long draftId, String schemeCode, String eventId) {
		QuotationDraftSnapshot draft = new QuotationDraftSnapshot(
			draftId,
			3,
			QuotationDraftStatus.CONFIRMED,
			schemeCode,
			Map.of("companyName", "由資料庫讀取", "workName", "由資料庫讀取"),
			List.of(),
			List.of(),
			null,
			false,
			true,
			true,
			eventId
		);
		QuotationConfirmationIntent intent = new QuotationConfirmationIntent(draft, eventId, null);
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			schemeCode,
			List.of(
				line("EXTERNAL_SCAFFOLD", "外部鷹架", null, null, 1),
				line("CROSS_BRACE", "交叉拉桿", new BigDecimal("1"), new BigDecimal("180.00"), 2)
			),
			List.of(),
			new BigDecimal("180.00"),
			new BigDecimal("9.00"),
			new BigDecimal("189.00"),
			"MARINE".equals(schemeCode)
				? QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY
				: QuotationCalculationResult.CustomerPresentation.DETAIL
		);
		return new QuotationConfirmationCommand(intent, calculation);
	}

	// 建立測試用固定品項快照列。
	private QuotationCalculationResult.QuotationLine line(
		String itemCode,
		String itemName,
		BigDecimal quantity,
		BigDecimal lineAmount,
		int displayOrder
	) {
		return new QuotationCalculationResult.QuotationLine(
			itemCode,
			itemName,
			"",
			"m2",
			new BigDecimal("180.00"),
			quantity,
			lineAmount,
			"(實做實算)",
			displayOrder,
			"DIRECT",
			QuotationCalculationResult.LineOrigin.STANDARD,
			true
		);
	}
}
