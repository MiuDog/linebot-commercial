package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationGenerationJobWorker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-xlsx-retry-assets",
		"app.quotation.root-path=${java.io.tmpdir}/assets-manager-xlsx-retry-output",
		"app.public-base-url=https://quotation.example.test",
		"app.quotation.generation-worker-enabled=false",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationXlsxRetryControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	QuotationGenerationJobWorker worker;

	@Test
	void requeuesFailedWorkbookWithTheSameQuotationNumberAndSequence() throws Exception {
		long quotationId = insertFailedQuotation();

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/xlsx-retries", quotationId)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationId").value(quotationId))
			.andExpect(jsonPath("$.quotationNumber").value("2026081101"))
			.andExpect(jsonPath("$.status").value("PENDING"));

		verify(worker).wake();
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
			"SELECT sequence_number FROM quotation WHERE id = ?",
			Integer.class,
			quotationId
		)).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
			"SELECT status FROM quotation_generation_job WHERE quotation_id = ?",
			String.class,
			quotationId
		)).isEqualTo("PENDING");
	}

	private long insertFailedQuotation() {
		String draftKey = "draft-xlsx-retry-" + UUID.randomUUID();
		jdbc.update("""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, company_name,
				work_name, quotation_name, scheme_id, status, revision
			)
			SELECT ?, 'user', 'U-retry-owner', 'U-retry-owner', '範例公司',
				'重試工程', '範例公司-重試工程', id, 'CONFIRMED', 1
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
				scheme_id, template_id, subtotal, tax_amount, total_amount, status
			)
			SELECT ?, 1, '2026081101', '範例公司-重試工程 20260811-01', '2026-08-11',
				1, '範例公司', '重試工程', '2026-08-11', '2026-08-26',
				s.id, t.id, 100, 5, 105, 'FAILED'
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
			INSERT INTO quotation_file (
				quotation_id, file_kind, content_type, status, error_message
			)
			VALUES (?, 'XLSX',
				'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
				'FAILED', 'WORKBOOK_GENERATION_FAILED')
			""", quotationId);
		return quotationId;
	}
}
