package dev.miudog.linebotcommercial.repository;

import dev.miudog.linebotcommercial.service.quotation.QuotationCalculationResult;
import dev.miudog.linebotcommercial.service.quotation.QuotationConfirmedGenerationCommand;
import dev.miudog.linebotcommercial.service.quotation.QuotationGenerationJobRepository;
import dev.miudog.linebotcommercial.service.quotation.QuotationGenerationSnapshotRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-generation-snapshot-test",
		"app.quotation.generation-worker-enabled=false",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationGenerationSnapshotRepositoryTest {

	@Autowired
	QuotationGenerationSnapshotRepository repository;

	@Autowired
	QuotationGenerationJobRepository jobs;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void rebuildsCompleteCommandOnlyFromImmutableQuotationSnapshots() {
		long quotationId = insertQuotation();
		jobs.enqueueIfAbsent(quotationId, "request-snapshot");
		jdbc.update("""
			UPDATE quotation_draft
			SET company_name = '遭修改公司', customer_phone = '0000',
				customer_email = 'changed@example.test', project_location = '遭修改地點'
			WHERE id = (SELECT draft_id FROM quotation WHERE id = ?)
			""", quotationId);

		QuotationConfirmedGenerationCommand command = repository.load(quotationId);

		assertThat(command.confirmation().quotationId()).isEqualTo(quotationId);
		assertThat(command.confirmation().quotationNumber()).isEqualTo("2026081101");
		assertThat(command.confirmation().folderName()).isEqualTo("20260811-01");
		assertThat(command.confirmation().fileBaseName()).isEqualTo("正定公司-完整快照 20260811-01");
		assertThat(command.header().customerName()).isEqualTo("正式客戶");
		assertThat(command.header().phoneFax()).isEqualTo("02-1111 / 02-2222");
		assertThat(command.header().customerEmail()).isEqualTo("formal@example.test");
		assertThat(command.header().contact()).isEqualTo("王先生");
		assertThat(command.header().projectSite()).isEqualTo("中壢案場");
		assertThat(command.header().salesRepresentative()).isEqualTo("陳業務");
		assertThat(command.destinationId()).isEqualTo("U-snapshot-owner");
		assertThat(command.additionalHeader()).isEqualTo("Tax ID 12345678");
		assertThat(command.calculation().schemeCode()).isEqualTo("GENERAL");
		assertThat(command.calculation().internalLines()).hasSize(2);
		assertThat(command.calculation().customerLines()).hasSize(1);
		assertThat(command.calculation().internalLines().get(0).origin())
			.isEqualTo(QuotationCalculationResult.LineOrigin.STANDARD);
		assertThat(command.calculation().internalLines().get(1).origin())
			.isEqualTo(QuotationCalculationResult.LineOrigin.TEMPORARY);
		assertThat(command.calculation().subtotal()).isEqualByComparingTo("200");
		assertThat(command.calculation().tax()).isEqualByComparingTo("10");
		assertThat(command.calculation().total()).isEqualByComparingTo("210");
	}

	private long insertQuotation() {
		String draftKey = "draft-snapshot-" + UUID.randomUUID();
		jdbc.update("""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name,
				work_name, quotation_name, customer_phone, customer_email,
				project_location, scheme_id, status, revision
			)
			SELECT ?, 'user', 'U-snapshot-owner', 'U-snapshot-owner', '草稿公司',
				'草稿工程', '草稿公司-草稿工程', '02-draft', 'draft@example.test',
				'草稿地點', id, 'CONFIRMED', 1
			FROM quotation_scheme
			WHERE code = 'GENERAL'
			""", draftKey);
		Long draftId = jdbc.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			draftKey
		);
		jdbc.update("""
			INSERT INTO quotation (
				draft_id, revision, quotation_no, quotation_name, sequence_date,
				sequence_number, company_name, work_name, quotation_date, valid_until,
				scheme_id, template_id, customer_name, customer_phone, customer_fax,
				customer_email, contact_name, project_location, sales_representative,
				additional_header,
				subtotal, tax_amount, total_amount, status
			)
			SELECT ?, 1, '2026081101', '正定公司-完整快照 20260811-01', '2026-08-11',
				1, '正定公司', '完整快照', '2026-08-11', '2026-08-26',
				s.id, t.id, '正式客戶', '02-1111', '02-2222',
				'formal@example.test', '王先生', '中壢案場', '陳業務',
				'Tax ID 12345678', 200, 10, 210, 'CONFIRMED'
			FROM quotation_scheme s
			JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE s.code = 'GENERAL'
			""", draftId);
		Long quotationId = jdbc.queryForObject(
			"SELECT id FROM quotation WHERE draft_id = ?",
			Long.class,
			draftId
		);
		jdbc.update("""
			INSERT INTO quotation_line (
				quotation_id, line_number, line_kind, visibility, item_code_snapshot,
				item_name_snapshot, specification_snapshot, quantity, unit_snapshot,
				unit_price_snapshot, line_amount, remark_snapshot, calculation_detail_json
			)
			VALUES
				(?, 1, 'STANDARD', 'CUSTOMER', 'EXTERNAL_SCAFFOLD', '外部鷹架',
					'(一般料)', 1, 'm2', 100, 100, '(實做實算)', '{"mode":"DIRECT"}'),
				(?, 2, 'CUSTOM', 'INTERNAL', NULL, '臨時品項',
					'特殊規格', 1, '式', 100, 100, '內部列', '{"mode":"DIRECT"}')
			""", quotationId, quotationId);
		return quotationId;
	}
}
