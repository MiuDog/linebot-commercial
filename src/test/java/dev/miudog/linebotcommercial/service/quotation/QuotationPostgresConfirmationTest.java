package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.companyasset.CompanyAssetService;
import dev.miudog.linebotcommercial.repository.JdbcQuotationDeliveryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用獨立 PostgreSQL schema 驗證正式確認 SQL；不啟動背景工作或發送 LINE。
 */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class QuotationPostgresConfirmationTest {

	// 方法：覆蓋五種格式、每日遞增、重複確認與失敗回滾，使用正式 Flyway 結構。
	@Test
	void confirmsAllSchemesAndRollsBackFailedSnapshots() {
		String schema = "confirmation_test_" + UUID.randomUUID().toString().replace("-", "");
		var admin = new DriverManagerDataSource(System.getenv("TEST_POSTGRES_URL"), "postgres", "");
		var root = new JdbcTemplate(admin);
		root.execute("CREATE SCHEMA " + schema);
		try {
			String url = System.getenv("TEST_POSTGRES_URL");
			var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "postgres", "");
			Flyway.configure().dataSource(source).schemas(schema).locations("classpath:db/migration").load().migrate();
			var jdbc = new JdbcTemplate(source);
			jdbc.update("""
				INSERT INTO company_asset_set (company_id, asset_set_version, schema_version, status, manifest_hash, created_by)
				VALUES ('test', '1', '1', 'ACTIVE', 'test-hash', 'test')
				""");
			long assetId = jdbc.queryForObject("SELECT id FROM company_asset_set", Long.class);
			var assets = mock(CompanyAssetService.class);
			when(assets.activeSetId()).thenReturn(assetId);
			var service = new QuotationConfirmationService(
				jdbc,
				new DataSourceTransactionManager(source),
				new QuotationGenerationJobRepository(jdbc),
				new QuotationBusinessRules(new BigDecimal("0.05"), 15),
				assets,
				false
			);
			var snapshots = new QuotationGenerationSnapshotRepository(jdbc);
			var deliveries = new JdbcQuotationDeliveryRepository(jdbc);
			int sequence = 0;
			for (String scheme : List.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES")) {
				jdbc.update("""
					INSERT INTO quotation_template (template_key, scheme_id, sheet_name, detail_first_row, detail_last_row,
						column_mapping_json, subtotal_cell, pre_tax_cell, tax_cell, total_cell)
					SELECT ?, id, 'test', 10, 20, '{}', 'H21', 'H22', 'H23', 'H24' FROM quotation_scheme WHERE code = ?
					""", "test-" + scheme, scheme);
				long draftId = insertDraft(jdbc, scheme);
				var command = command(draftId, scheme, "test-item");
				var result = service.confirm(command);
				var snapshot = snapshots.load(result.quotationId());
				assertThat(result.sequenceNumber()).isEqualTo(++sequence);
				assertThat(snapshot.calculation().internalLines().getFirst().calculationMode()).isEqualTo("DIRECT");
				assertThat(service.confirm(command).quotationId()).isEqualTo(result.quotationId());
				assertThat(jdbc.queryForObject("SELECT total_amount FROM quotation WHERE id = ?", BigDecimal.class, result.quotationId())).isEqualByComparingTo("210");
				assertThat(jdbc.queryForObject("SELECT asset_set_id FROM quotation WHERE id = ?", Long.class, result.quotationId())).isEqualTo(assetId);
				assertThat(jdbc.queryForObject("SELECT count(*) FROM quotation_line WHERE quotation_id = ?", Integer.class, result.quotationId())).isEqualTo(1);
				assertThat(jdbc.queryForObject("SELECT status FROM quotation_draft WHERE id = ?", String.class, draftId)).isEqualTo("CONFIRMED");
				jdbc.update("UPDATE quotation SET status = 'READY' WHERE id = ?", result.quotationId());
				jdbc.update("""
					INSERT INTO quotation_file (quotation_id, file_kind, content_type, status)
					VALUES (?, 'PDF', 'application/pdf', 'READY')
					""", result.quotationId());
				assertThat(deliveries.findSnapshot(result.quotationId()).createdAt()).isNotNull();
			}
			long failedDraft = insertDraft(jdbc, "CNS");
			assertThatThrownBy(() -> service.confirm(command(failedDraft, "CNS", null))).isInstanceOf(org.springframework.dao.DataAccessException.class);
			assertThat(jdbc.queryForObject("SELECT last_sequence FROM quotation_daily_sequence", Integer.class)).isEqualTo(5);
			assertThat(jdbc.queryForObject("SELECT status FROM quotation_draft WHERE id = ?", String.class, failedDraft)).isEqualTo("AWAITING_CONFIRMATION");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM quotation_event_receipt WHERE draft_id = ?", Integer.class, failedDraft)).isZero();
			assertThat(service.confirm(command(failedDraft, "CNS", "repaired")).sequenceNumber()).isEqualTo(6);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM quotation_generation_job", Integer.class)).isEqualTo(6);
		}
		finally {
			// 外部呼叫：只刪除此測試建立、由 UUID 組成的獨立 schema。
			root.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}

	// 方法：建立不涉及真實使用者的待確認草稿。
	private long insertDraft(JdbcTemplate jdbc, String scheme) {
		String key = UUID.randomUUID().toString();
		long id = jdbc.queryForObject("""
			INSERT INTO quotation_draft (draft_key, source_type, source_id, requester_id, company_name, work_name,
				scheme_id, status, revision, confirmation_revision)
			SELECT ?, 'user', ?, 'test-user', 'test-company', 'test-work', id, 'AWAITING_CONFIRMATION', 2, 2
			FROM quotation_scheme WHERE code = ? RETURNING id
			""", Long.class, key, key, scheme);
		jdbc.update("INSERT INTO quotation_draft_field (draft_id, field_key, field_value) VALUES (?, 'salesRepresentative', 'test')", id);
		return id;
	}

	// 方法：使用固定計算結果專注驗證確認交易，空品名觸發資料庫限制以測試回滾。
	private QuotationConfirmationCommand command(long id, String scheme, String itemName) {
		String event = "test-event-" + id;
		var draft = new QuotationDraftSnapshot(id, 3, QuotationDraftStatus.CONFIRMED, scheme, Map.of(), List.of(), List.of(), null, false, true, true, event);
		var line = new QuotationCalculationResult.QuotationLine(
			"test-item", itemName, "", "式", new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("200"), "", 1, "DIRECT",
			QuotationCalculationResult.LineOrigin.STANDARD, true
		);
		var calculation = new QuotationCalculationResult(scheme, List.of(line), List.of(), new BigDecimal("200"), new BigDecimal("10"), new BigDecimal("210"), QuotationCalculationResult.CustomerPresentation.DETAIL);
		return new QuotationConfirmationCommand(new QuotationConfirmationIntent(draft, event, null), calculation);
	}
}
