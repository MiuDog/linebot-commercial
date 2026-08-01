package dev.myudog.assetsmanagerlinebot.service.quotation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

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
			"quotation_request",
			"quotation_request_image",
			"quotation",
			"quotation_line"
		);
	}

	@Test
	void seedsTheThreePricingSchemesAndTheirTemplateCoordinates() {
		List<Map<String, Object>> schemes = jdbcTemplate.queryForList("""
                SELECT s.code, s.calculation_visibility, t.sheet_name,
                       t.detail_first_row, t.detail_last_row, t.total_cell
                FROM quotation_scheme s
                JOIN quotation_template t ON t.scheme_id = s.id
                WHERE t.is_active = 1
                ORDER BY s.code
                """);

		assertThat(schemes).hasSize(3);
		assertThat(schemes).anySatisfy(row -> {
				assertThat(row.get("code")).isEqualTo("CNS");
				assertThat(row.get("calculation_visibility")).isEqualTo("DETAIL");
				assertThat(row.get("sheet_name")).isEqualTo("CNS");
				assertThat(((Number) row.get("detail_first_row")).intValue()).isEqualTo(11);
				assertThat(((Number) row.get("detail_last_row")).intValue()).isEqualTo(28);
				assertThat(row.get("total_cell")).isEqualTo("G32");
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
}
