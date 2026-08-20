package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-confirmation-concurrency-test",
		"spring.datasource.url=jdbc:sqlite:file:quotation-confirmation-concurrency?mode=memory&cache=shared"
	}
)
class QuotationConfirmationConcurrencyTest {

	@Autowired
	QuotationConfirmationService service;

	@Autowired
	JdbcTemplate jdbc;

	// 驗證第 100 張起的兩個並行確認仍會配置不同三位流水號。
	@Test
	void concurrentConfirmationsReceiveDifferentDailySequences() throws Exception {
		String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).toString();
		jdbc.update("""
			INSERT INTO quotation_daily_sequence (sequence_date, last_sequence)
			VALUES (?, 99)
			ON CONFLICT (sequence_date) DO UPDATE SET last_sequence = 99
			""", today);
		long firstDraftId = insertConfirmedDraft("並行甲案");
		long secondDraftId = insertConfirmedDraft("並行乙案");

		CompletableFuture<QuotationConfirmationResult> first = CompletableFuture.supplyAsync(
			() -> service.confirm(command(firstDraftId, "event-" + UUID.randomUUID()))
		);
		CompletableFuture<QuotationConfirmationResult> second = CompletableFuture.supplyAsync(
			() -> service.confirm(command(secondDraftId, "event-" + UUID.randomUUID()))
		);

		QuotationConfirmationResult firstResult = first.get(10, TimeUnit.SECONDS);
		QuotationConfirmationResult secondResult = second.get(10, TimeUnit.SECONDS);

		assertThat(List.of(firstResult.sequenceNumber(), secondResult.sequenceNumber()))
			.containsExactlyInAnyOrder(100, 101);
		assertThat(firstResult.folderName()).isNotEqualTo(secondResult.folderName());
		assertThat(List.of(firstResult.folderName(), secondResult.folderName()))
			.containsExactlyInAnyOrder(today.replace("-", "") + "-100", today.replace("-", "") + "-101");
		assertThat(
			jdbc.queryForObject(
				"SELECT COUNT(*) FROM quotation WHERE id IN (?, ?)",
				Integer.class,
				firstResult.quotationId(),
				secondResult.quotationId()
			)
		).isEqualTo(2);
	}

	@Test
	void concurrentConfirmationOfSameDraftCreatesOnlyOneFormalQuotation() throws Exception {
		long draftId = insertConfirmedDraft("同一案件");
		QuotationConfirmationCommand firstCommand = command(draftId, "same-event-a-" + UUID.randomUUID());
		QuotationConfirmationCommand secondCommand = command(draftId, "same-event-b-" + UUID.randomUUID());

		CompletableFuture<QuotationConfirmationResult> first = CompletableFuture.supplyAsync(
			() -> service.confirm(firstCommand)
		);
		CompletableFuture<QuotationConfirmationResult> second = CompletableFuture.supplyAsync(
			() -> service.confirm(secondCommand)
		);
		QuotationConfirmationResult firstResult = first.get(10, TimeUnit.SECONDS);
		QuotationConfirmationResult secondResult = second.get(10, TimeUnit.SECONDS);

		assertThat(secondResult.quotationId()).isEqualTo(firstResult.quotationId());
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation WHERE draft_id = ?",
			Integer.class,
			draftId
		)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_generation_job WHERE quotation_id = ?",
			Integer.class,
			firstResult.quotationId()
		)).isEqualTo(1);
	}

	// 方法：建立已確認且具啟用範本的測試草稿。
	private long insertConfirmedDraft(String workName) {
		String draftKey = "parallel-" + UUID.randomUUID();

		// 資料庫 API：建立等待確認的測試草稿。
		jdbc.update(
			"""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name,
				work_name, quotation_name, scheme_id, status, revision, confirmation_revision
			)
			SELECT ?, 'user', ?, 'U001', '正定工程', ?, ?, id, 'AWAITING_CONFIRMATION', 1, 1
			FROM quotation_scheme WHERE code = 'GENERAL'
			""",
			draftKey,
			"U-" + UUID.randomUUID(),
			workName,
			"正定工程-" + workName
		);
		// 資料庫 API：讀取新建草稿並補上每張報價必要的業務承辦。
		Long draftId = jdbc.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			draftKey
		);

		// 資料庫 API：補上每張報價必要的業務承辦。
		jdbc.update(
			"INSERT INTO quotation_draft_field (draft_id, field_key, field_value) VALUES (?, 'salesRepresentative', '陳業務')",
			draftId
		);
		return draftId;
	}

	// 建立最小但完整的確定性計價確認命令。
	private QuotationConfirmationCommand command(long draftId, String eventId) {
		QuotationDraftSnapshot draft = new QuotationDraftSnapshot(
			draftId,
			2,
			QuotationDraftStatus.CONFIRMED,
			"GENERAL",
			Map.of("companyName", "正定工程", "workName", "並行案件"),
			List.of(),
			List.of(),
			null,
			false,
			true,
			true,
			eventId
		);
		QuotationCalculationResult.QuotationLine line = new QuotationCalculationResult.QuotationLine(
			"FRAME",
			"外部鷹架",
			"",
			"m2",
			new BigDecimal("180.00"),
			BigDecimal.ONE,
			new BigDecimal("180.00"),
			"",
			1,
			"DIRECT",
			QuotationCalculationResult.LineOrigin.STANDARD,
			true
		);
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			"GENERAL",
			List.of(line),
			List.of(line),
			new BigDecimal("180.00"),
			new BigDecimal("9.00"),
			new BigDecimal("189.00"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
		return new QuotationConfirmationCommand(
			new QuotationConfirmationIntent(draft, eventId, null),
			calculation
		);
	}
}
