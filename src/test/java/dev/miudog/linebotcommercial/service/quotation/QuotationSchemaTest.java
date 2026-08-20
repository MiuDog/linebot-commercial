package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(
	properties =
	{"app.storage.root=${java.io.tmpdir}/assets-manager-quotation-schema-test",
		"spring.datasource.url=jdbc:sqlite::memory:"}
)
class QuotationSchemaTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	// 驗證完整報價流程需要的資料表會在啟動時建立。
	@Test
	void initializesConfirmedQuotationWorkflowTables() {
		// 查詢 SQLite 系統目錄，確認正式流程的持久化邊界完整。
		List<String> tables =
			jdbcTemplate.queryForList("SELECT name FROM sqlite_master WHERE type = 'table'", String.class);

		assertThat(tables).contains(
			"quotation_draft_field",
			"quotation_draft_image",
			"quotation_event_receipt",
			"quotation_daily_sequence",
			"quotation_asset",
			"quotation_file",
			"quotation_download_token",
			"quotation_reply_outbox",
			"quotation_delivery_attempt",
			"quotation_generation_job"
		);
	}

	// 驗證固定品項可以保留空白數量，臨時品項則保存確認時的完整快照。
	@Test
	@Transactional
	void supportsBlankStandardQuantityAndDynamicDraftItems() {
		String suffix = UUID.randomUUID().toString();
		// 建立符合新版狀態機的草稿。
		jdbcTemplate.update(
			"""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id,
				company_name, work_name, status
			)
			VALUES (?, 'user', ?, 'requester', '正定公司', '測試工程', 'COLLECTING_ITEMS')
			""",
			"draft-" + suffix,
			"source-" + suffix
		);
		// 讀取剛建立的草稿識別碼。
		Long draftId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			"draft-" + suffix
		);
		// 讀取一筆正式品項作為空白數量列。
		Long itemId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_item WHERE code = 'EXTERNAL_SCAFFOLD'",
			Long.class
		);
		// 新增未被使用但仍需顯示的正式品項。
		jdbcTemplate.update(
			"""
			INSERT INTO quotation_draft_item (
				draft_id, item_kind, item_id, item_code_snapshot,
				item_name_snapshot, unit_snapshot, unit_price_snapshot,
				quantity, display_order
			)
			VALUES (?, 'STANDARD', ?, 'EXTERNAL_SCAFFOLD', '外部鷹架', 'm2', 180, NULL, 1)
			""",
			draftId,
			itemId
		);
		// 新增不寫回正式品項主檔的臨時品項。
		jdbcTemplate.update(
			"""
			INSERT INTO quotation_draft_item (
				draft_id, item_kind, item_id, client_item_id, item_name_snapshot,
				specification_snapshot, unit_snapshot, unit_price_snapshot,
				quantity, remark_snapshot, display_order
			)
			VALUES (?, 'CUSTOM', NULL, ?, '臨時護欄', '高 120cm', '式', 5000, 2, '現場施作', 2)
			""",
			draftId,
			"custom-" + suffix
		);

		// 查詢兩筆草稿列，確認空白數量與臨時快照均完整保存。
		List<Map<String, Object>> items = jdbcTemplate.queryForList(
			"""
			SELECT item_kind, item_id, item_name_snapshot, quantity
			FROM quotation_draft_item
			WHERE draft_id = ?
			ORDER BY display_order
			""",
			draftId
		);

		assertThat(items).hasSize(2);
		assertThat(items.get(0).get("quantity")).isNull();
		assertThat(items.get(1).get("item_id")).isNull();
		assertThat(items.get(1).get("item_name_snapshot")).isEqualTo("臨時護欄");
	}

	// 驗證每日流水號、檔案狀態與安全下載資訊都有資料庫約束。
	@Test
	@Transactional
	void persistsDailySequenceFilesAndSecureDeliveries() {
		// 建立當日流水號游標。
		jdbcTemplate.update(
			"INSERT INTO quotation_daily_sequence (sequence_date, last_sequence) VALUES ('2026-08-11', 1)"
		);

		assertThatThrownBy(
			() -> {
				// 同一日期不可建立第二個流水號游標。
				jdbcTemplate.update(
					"INSERT INTO quotation_daily_sequence (sequence_date, last_sequence) VALUES ('2026-08-11', 2)"
				);
			}
		).isInstanceOf(DataAccessException.class);

		// 查詢關鍵欄位，確認檔案與交付資料可以支援失敗重試。
		List<String> quotationColumns = jdbcTemplate.queryForList(
			"SELECT name FROM pragma_table_info('quotation')",
			String.class
		);
		assertThat(quotationColumns).contains(
			"draft_id",
			"sequence_date",
			"sequence_number",
			"company_name",
			"work_name",
			"quotation_date",
			"valid_until"
		);

		// 查詢明細欄位的可空約束，未使用的固定品項不得被迫填入假數量。
		Map<String, Object> quantityColumn = jdbcTemplate.queryForMap(
			"""
			SELECT name, "notnull" AS required
			FROM pragma_table_info('quotation_line')
			WHERE name = 'quantity'
			"""
		);
		assertThat(((Number) quantityColumn.get("required")).intValue()).isZero();
	}

	@Test
	void initializesQuotationTablesWithoutRemovingAssetTables() {
		List<String> tables =
		jdbcTemplate.queryForList("SELECT name FROM sqlite_master WHERE type = 'table'", String.class);

		assertThat(tables).contains(
			"asset",
			"quotation_scheme",
			"quotation_item",
			"quotation_scheme_item",
			"quotation_rule",
			"quotation_template",
			"quotation_draft",
			"quotation_draft_message",
			"quotation_draft_item",
			"line_rich_menu_action",
			"admin_audit_log",
			"quotation_request",
			"quotation_request_image",
			"quotation",
			"quotation_line"
		);
	}

	@Test
	void seedsTheFivePricingSchemesAndTemplateCoordinates() {
		List<Map<String, Object>> allSchemes = jdbcTemplate.queryForList("""
			SELECT code, name, calculation_visibility
			FROM quotation_scheme
			ORDER BY code
			""");
		List<Map<String, Object>> schemes = jdbcTemplate.queryForList("""
				SELECT s.code, s.calculation_visibility, t.workbook_path, t.sheet_name,
                       t.detail_first_row, t.detail_last_row, t.total_cell
                FROM quotation_scheme s
                JOIN quotation_template t ON t.scheme_id = s.id
                WHERE t.is_active = 1
                ORDER BY s.code
                """);

		assertThat(allSchemes).hasSize(5);
		assertThat(allSchemes).extracting(row -> row.get("code"))
			.containsExactly("BLANK", "CNS", "GENERAL", "MARINE", "SALES");
		assertThat(schemes).hasSize(5);
		assertThat(schemes).extracting(row -> row.get("workbook_path"))
			.containsExactlyInAnyOrder(
				"outputs/excel-templates/quotation-template-BLANK.xlsx",
				"outputs/excel-templates/quotation-template-CNS.xlsx",
				"outputs/excel-templates/quotation-template-GENERAL.xlsx",
				"outputs/excel-templates/quotation-template-MARINE.xlsx",
				"outputs/excel-templates/quotation-template-SALES.xlsx"
			);
		assertThat(schemes).anySatisfy(row -> {
				assertThat(row.get("code")).isEqualTo("CNS");
				assertThat(row.get("calculation_visibility")).isEqualTo("DETAIL");
				assertThat(row.get("sheet_name")).isEqualTo("CNS");
				assertThat(((Number) row.get("detail_first_row")).intValue()).isEqualTo(11);
				assertThat(((Number) row.get("detail_last_row")).intValue()).isEqualTo(31);
				assertThat(row.get("total_cell")).isEqualTo("G35");
			});
		assertThat(schemes).anySatisfy(row -> {
				assertThat(row.get("code")).isEqualTo("GENERAL");
				assertThat(row.get("sheet_name")).isEqualTo("一般架");
				assertThat(row.get("total_cell")).isEqualTo("G34");
			});
		assertThat(schemes).anySatisfy(row -> {
				assertThat(row.get("code")).isEqualTo("MARINE");
				assertThat(row.get("calculation_visibility")).isEqualTo("SUMMARY_ONLY");
				assertThat(row.get("sheet_name")).isEqualTo("船用");
				assertThat(row.get("total_cell")).isEqualTo("G27");
		});
		assertThat(schemes).anySatisfy(row -> {
			assertThat(row.get("code")).isEqualTo("BLANK");
			assertThat(row.get("sheet_name")).isEqualTo("空白");
			assertThat(row.get("total_cell")).isEqualTo("G32");
		});
		assertThat(schemes).anySatisfy(row -> {
			assertThat(row.get("code")).isEqualTo("SALES");
			assertThat(row.get("sheet_name")).isEqualTo("銷售報價單(報價單號前會多一個S)");
			assertThat(row.get("total_cell")).isEqualTo("G32");
		});
	}

	@Test
	void importsTheExcelItemMasterWithoutOverwritingFutureEdits() {
		Integer itemCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM quotation_item", Integer.class);
		Integer cnsCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			WHERE s.code = 'CNS'
			""", Integer.class);
		Integer generalCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			WHERE s.code = 'GENERAL'
			""", Integer.class);
		Map<String, Object> externalScaffold = jdbcTemplate.queryForMap("""
			SELECT si.specification, si.unit, si.unit_price, si.remark
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			WHERE s.code = 'GENERAL' AND i.code = 'EXTERNAL_SCAFFOLD'
			""");

		assertThat(itemCount).isEqualTo(21);
		assertThat(cnsCount).isEqualTo(21);
		assertThat(generalCount).isEqualTo(20);
		assertThat(externalScaffold.get("specification")).isEqualTo("(一般料)");
		assertThat(externalScaffold.get("unit")).isEqualTo("m2");
		assertThat(((Number) externalScaffold.get("unit_price")).intValue()).isEqualTo(180);
		assertThat(externalScaffold.get("remark")).isEqualTo("(實做實算)");
	}

	// 方法：逐欄比對來源 Excel 萃取結果與 SQLite 的 41 筆 CNS／一般架固定主檔。
	@Test
	void matchesEveryFixedMasterFieldExtractedFromTheSourceWorkbook() throws Exception {
		// 外部 API：讀取由正式來源活頁簿產生的可稽核萃取清單。
		JsonNode analysis = new ObjectMapper().readTree(
			Files.readString(Path.of("outputs/excel-templates/template-analysis.json"))
		);
		List<MasterRow> expected = expectedFixedMasterRows(analysis);

		// 資料庫 API：依方案及顯示順序讀取所有固定主檔欄位。
		List<MasterRow> actual = jdbcTemplate.query(
			"""
			SELECT s.code AS scheme_code, i.code AS item_code, i.name AS item_name,
				si.specification, si.unit, si.unit_price, si.remark, si.display_order
			FROM quotation_scheme_item si
			JOIN quotation_scheme s ON s.id = si.scheme_id
			JOIN quotation_item i ON i.id = si.item_id
			WHERE s.code IN ('CNS', 'GENERAL')
			ORDER BY s.code, si.display_order
			""",
			(result, rowNumber) -> new MasterRow(
				result.getString("scheme_code"),
				result.getString("item_code"),
				result.getString("item_name"),
				result.getString("specification"),
				result.getString("unit"),
				result.getBigDecimal("unit_price"),
				result.getString("remark"),
				result.getInt("display_order")
			)
		);

		assertThat(expected).hasSize(41);
		assertThat(actual).containsExactlyElementsOf(expected);
	}

	@Test
	void seedsTheRecommendedLineRichMenuActions() {
		List<String> actionData = jdbcTemplate.queryForList("""
			SELECT action_data
			FROM line_rich_menu_action
			WHERE is_active = 1
			ORDER BY display_order
			""", String.class);

		assertThat(actionData).containsExactly(
			"建立報價",
			"我的草稿",
			"選擇報價類型",
			"上傳圖片",
			"使用說明",
			"取消操作"
		);
	}

	@Test
	void enablesForeignKeyEnforcementForQuotationHistory() {
		Integer foreignKeysEnabled = jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class);

		assertThat(foreignKeysEnabled).isEqualTo(1);
	}

	@Test
	void quotationSnapshotRequiresANameForItsOutputDirectory() {
		Map<String, Object> quotationNameColumn = jdbcTemplate.queryForMap("""
                SELECT name, "notnull" AS required
                FROM pragma_table_info('quotation')
                WHERE name = 'quotation_name'
                """);

		assertThat(quotationNameColumn.get("name")).isEqualTo("quotation_name");
		assertThat(((Number) quotationNameColumn.get("required")).intValue()).isEqualTo(1);
	}

	@Test
	@Transactional
	void selectedImageMustBelongToTheSameQuotationRequest() {
		String suffix = UUID.randomUUID().toString();
		long firstRequestId = insertRequest("command-a-" + suffix);
		long secondRequestId = insertRequest("command-b-" + suffix);
		jdbcTemplate.update("""
                INSERT INTO quotation_request_image (request_id, message_id, is_selected)
                VALUES (?, ?, 1)
                """, secondRequestId, "image-" + suffix);
		Long secondRequestImageId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_request_image WHERE request_id = ?",
			Long.class,
			secondRequestId
		);

		Map<String, Object> schemeAndTemplate = jdbcTemplate.queryForMap("""
                SELECT s.id AS scheme_id, t.id AS template_id
                FROM quotation_scheme s
                JOIN quotation_template t ON t.scheme_id = s.id
                WHERE s.code = 'CNS' AND t.is_active = 1
                """);

		assertThatThrownBy(
			()
			-> jdbcTemplate.update(
			"""
                INSERT INTO quotation (
                    request_id, quotation_name, scheme_id, template_id, selected_request_image_id
                )
                VALUES (?, '測試案件', ?, ?, ?)
                """,
			firstRequestId,
			schemeAndTemplate.get("scheme_id"),
			schemeAndTemplate.get("template_id"),
			secondRequestImageId
		)
		)
			.isInstanceOf(DataAccessException.class);
	}

	private long insertRequest(String commandMessageId) {
		jdbcTemplate.update("""
                INSERT INTO quotation_request (
                    source_type, source_id, command_message_id, raw_instruction
                )
                VALUES ('group', 'test-group', ?, '#報價')
                """, commandMessageId);
		return jdbcTemplate
			.queryForObject("SELECT id FROM quotation_request WHERE command_message_id = ?", Long.class, commandMessageId);
	}

	// 方法：將 Excel 萃取報告中的 CNS／一般架品項轉成可與資料庫逐欄比較的資料列。
	private List<MasterRow> expectedFixedMasterRows(JsonNode analysis) {
		List<MasterRow> rows = new ArrayList<>();
		for (JsonNode scheme : analysis.path("schemes")) {
			String schemeCode = scheme.path("schemeCode").asString();
			if (!List.of("CNS", "GENERAL").contains(schemeCode)) continue;

			for (JsonNode item : scheme.path("items")) {
				rows.add(new MasterRow(
					schemeCode,
					item.path("itemCode").asString(),
					item.path("name").asString(),
					item.path("specification").asString(),
					item.path("unit").asString(),
					item.path("unitPrice").decimalValue(),
					item.path("remark").asString(),
					item.path("displayOrder").asInt()
				));
			}
		}
		return List.copyOf(rows);
	}

	private record MasterRow(
		String schemeCode,
		String itemCode,
		String itemName,
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		int displayOrder
	) {}
}
