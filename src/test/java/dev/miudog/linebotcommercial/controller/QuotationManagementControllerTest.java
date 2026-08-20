package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryResult;
import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryService;
import dev.miudog.linebotcommercial.service.quotation.QuotationDeliveryStatus;
import dev.miudog.linebotcommercial.service.quotation.QuotationPdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-quotation-management-assets",
		"app.quotation.root-path=${java.io.tmpdir}/assets-manager-quotation-management-test",
		"app.public-base-url=https://quotation.example.test",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationManagementControllerTest {

	private static final long QUOTATION_ID = 701;
	private static final long DRAFT_ID = 801;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	QuotationPdfService pdfService;

	@MockitoBean
	QuotationDeliveryService deliveryService;

	@BeforeEach
	void setUp() throws Exception {
		Path workbook = workbookPath();
		Files.createDirectories(workbook.getParent());
		Files.writeString(workbook, "xlsx-content", StandardCharsets.UTF_8);
		Files.createDirectories(pendingImagePath().getParent());
		Files.writeString(pendingImagePath(), "pending-image", StandardCharsets.UTF_8);
		Files.writeString(formalImagePath(), "formal-image", StandardCharsets.UTF_8);

		// 外部 API：透過參數化 SQL 建立可查詢的正式報價快照。
		jdbc.update("""
			INSERT INTO quotation (
				id, revision, quotation_no, quotation_name, sequence_date, sequence_number,
				company_name, work_name, quotation_date, valid_until, scheme_id, template_id,
				currency, subtotal, tax_rate, tax_amount, total_amount, status, output_path
			)
			SELECT ?, 1, '20260811-01', '正定公司-中壢案', '2026-08-11', 1,
				'正定公司', '中壢案', '2026-08-11', '2026-09-10', s.id, t.id,
				'TWD', 100000, 0.05, 5000, 105000, 'PDF_FAILED', ?
			FROM quotation_scheme s
			JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE s.code = 'CNS'
			""", QUOTATION_ID, Path.of("報價單", "20260811-01").toString());
		jdbc.update(
			"UPDATE quotation SET sales_representative = '陳業務' WHERE id = ?",
			QUOTATION_ID
		);
		jdbc.update("""
			INSERT INTO quotation_line (
				quotation_id, line_number, line_kind, visibility, item_code_snapshot,
				item_name_snapshot, specification_snapshot, quantity, unit_snapshot,
				unit_price_snapshot, line_amount, remark_snapshot
			)
			VALUES (?, 1, 'STANDARD', 'CUSTOMER', 'EXTERNAL_SCAFFOLD',
				'外部鷹架', '(CNS)', 2, 'm2', 50000, 100000, '(實做實算)')
			""", QUOTATION_ID);
		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, content_type, content_hash,
				file_size, status, error_message
			)
			VALUES (?, 'XLSX', ?,
				'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
				'xlsx-hash', 12, 'READY', NULL)
			""", QUOTATION_ID, relativeWorkbookPath());
		jdbc.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, content_type, status, error_message
			)
			VALUES (?, 'PDF', ?, 'application/pdf', 'FAILED', 'Microsoft Excel 匯出失敗')
			""", QUOTATION_ID, Path.of("報價單", "20260811-01", "quote.pdf").toString());
		jdbc.update("""
			INSERT INTO quotation_delivery_attempt (
				quotation_id, destination_type, destination_id, delivery_kind,
				status, error_message, completed_at
			)
			VALUES (?, 'LINE_USER', 'U-sensitive-destination', 'FINAL',
				'FAILED', 'LineMessagingException:LINE_PUSH_FAILED', CURRENT_TIMESTAMP)
			""", QUOTATION_ID);
		jdbc.update("""
			INSERT INTO quotation_generation_job (
				quotation_id, status, destination_id, correlation_id, attempt_count,
				next_attempt_at, created_at, updated_at, completed_at
			)
			VALUES (?, 'DONE', 'U-sensitive-destination', 'correlation-sensitive', 1,
				CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", QUOTATION_ID);
		jdbc.update("""
			INSERT INTO quotation_draft (
				id, draft_key, source_type, source_id, requester_id, quotation_name,
				company_name, work_name, contact_name, customer_phone, customer_email,
				project_location, scheme_id, status, revision
			)
			SELECT ?, 'admin-draft-801', 'user', 'U-draft-owner', 'U-draft-owner',
				'管理草稿', '測試公司', '外牆工程', '王小姐', '02-12345678',
				'contact@example.test', '台北市', id, 'READY_FOR_PREVIEW', 3
			FROM quotation_scheme WHERE code = 'CNS'
			""", DRAFT_ID);
		jdbc.update("""
			INSERT INTO quotation_draft_field (
				draft_id, field_key, field_value, source_text, confidence, confirmation_status
			)
			VALUES (?, 'additionalHeader', '統編 12345678', '統編 12345678', 0.99, 'CONFIRMED')
			""", DRAFT_ID);
		jdbc.update("""
			INSERT INTO quotation_draft_field (
				draft_id, field_key, field_value, source_text, confidence, confirmation_status
			)
			VALUES (?, 'salesRepresentative', '陳業務', '承辦陳業務', 0.99, 'CONFIRMED')
			""", DRAFT_ID);
		jdbc.update("""
			INSERT INTO quotation_draft_item (
				draft_id, item_kind, item_id, item_code_snapshot, item_name_snapshot,
				specification_snapshot, quantity, unit_snapshot, unit_price_snapshot,
				remark_snapshot, source_text, confidence, display_order
			)
			SELECT ?, 'STANDARD', i.id, i.code, i.name, '(CNS)', 2, 'm2', 350,
				'(實做實算)', '外部鷹架 2 平方米', 0.98, 1
			FROM quotation_item i WHERE i.code = 'EXTERNAL_SCAFFOLD'
			""", DRAFT_ID);
		jdbc.update("""
			INSERT INTO pending_image (
				message_id, image_set_id, image_index, image_total, source_type,
				source_id, uploader_id, staging_path, content_type, file_size, received_at
			)
			VALUES ('draft-selected-image', 'draft-set', 1, 1, 'user',
				'U-draft-owner', 'U-draft-owner', '.pending/admin-selected.jpg',
				'image/jpeg', ?, CURRENT_TIMESTAMP)
			""", Files.size(pendingImagePath()));
		jdbc.update("""
			INSERT INTO quotation_draft_image (
				draft_id, message_id, candidate_order, distinctiveness_score,
				quality_score, selection_reason, is_selected
			)
			VALUES (?, 'draft-selected-image', 0, 0.9, 0.8, '視角完整', 1)
			""", DRAFT_ID);
		jdbc.update("""
			INSERT INTO asset (
				message_id, share_token, source_type, source_id, uploader_id,
				file_path, content_type, file_size, created_at
			)
			VALUES ('formal-selected-image', 'formal-share-token', 'user',
				'U-draft-owner', 'U-draft-owner', ?, 'image/jpeg', ?, CURRENT_TIMESTAMP)
			""", relativeFormalImagePath(), Files.size(formalImagePath()));
		jdbc.update("""
			INSERT INTO quotation_asset (
				quotation_id, asset_id, candidate_order, distinctiveness_score,
				quality_score, selection_reason, is_selected
			)
			SELECT ?, id, 0, 0.9, 0.8, '視角完整', 1
			FROM asset WHERE message_id = 'formal-selected-image'
			""", QUOTATION_ID);
		jdbc.update("""
			INSERT INTO admin_audit_log (action, entity_type, entity_id, summary_json)
			VALUES ('QUOTATION_CONFIRMED', 'QUOTATION', ?, json_object('outcome', 'SUCCEEDED'))
			""", String.valueOf(QUOTATION_ID));
	}

	@Test
	void listsDraftsWithSearchAndStatusWithoutExposingOwnersOrPaths() throws Exception {
		mockMvc.perform(
			get("/api/admin/quotation-drafts")
				.param("search", "測試公司")
				.param("status", "READY_FOR_PREVIEW")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value(DRAFT_ID))
			.andExpect(jsonPath("$.data[0].companyName").value("測試公司"))
			.andExpect(jsonPath("$.data[0].sourceId").doesNotExist())
			.andExpect(jsonPath("$.data[0].stagingPath").doesNotExist())
			.andExpect(jsonPath("$.pagination.totalItems").value(1));
	}

	@Test
	void returnsCompleteDraftSnapshotWithProgramCalculatedAmountsAndSelectedImage() throws Exception {
		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationName").value("管理草稿"))
			.andExpect(jsonPath("$.companyName").value("測試公司"))
			.andExpect(jsonPath("$.workName").value("外牆工程"))
			.andExpect(jsonPath("$.contactName").value("王小姐"))
			.andExpect(jsonPath("$.customerPhone").value("02-12345678"))
			.andExpect(jsonPath("$.customerEmail").value("contact@example.test"))
			.andExpect(jsonPath("$.projectLocation").value("台北市"))
			.andExpect(jsonPath("$.additionalHeader").value("統編 12345678"))
			.andExpect(jsonPath("$.salesRepresentative").value("陳業務"))
			.andExpect(jsonPath("$.missingFields.length()").value(0))
			.andExpect(jsonPath("$.subtotal").value(700))
			.andExpect(jsonPath("$.taxAmount").value(35))
			.andExpect(jsonPath("$.totalAmount").value(735))
			.andExpect(jsonPath("$.items[0].specification").value("(CNS)"))
			.andExpect(jsonPath("$.items[0].unitPrice").value(350))
			.andExpect(jsonPath("$.selectedImageUrl").value(
				"/api/admin/quotation-drafts/801/selected-image"
			))
			.andExpect(jsonPath("$.sourceId").doesNotExist())
			.andExpect(jsonPath("$.stagingPath").doesNotExist());
	}

	@Test
	void returnsNullDraftAmountsAndExplicitMissingFieldsWhenBaseInfoIsIncomplete() throws Exception {
		jdbc.update("UPDATE quotation_draft SET company_name = NULL WHERE id = ?", DRAFT_ID);

		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.missingFields[0]").value("公司名稱"))
			.andExpect(jsonPath("$.subtotal").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.taxAmount").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.totalAmount").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void requiresAnImageDecisionOnlyForMarineAndBlankDrafts() throws Exception {
		jdbc.update("DELETE FROM quotation_draft_image WHERE draft_id = ?", DRAFT_ID);
		jdbc.update("""
			UPDATE quotation_draft
			SET scheme_id = (SELECT id FROM quotation_scheme WHERE code = 'MARINE'),
				image_declined = 0
			WHERE id = ?
			""", DRAFT_ID);

		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.missingFields").value(
				org.hamcrest.Matchers.hasItem("工程圖片或明確拒絕")
			))
			.andExpect(jsonPath("$.subtotal").value(org.hamcrest.Matchers.nullValue()));

		jdbc.update("UPDATE quotation_draft SET image_declined = 1 WHERE id = ?", DRAFT_ID);
		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.missingFields").value(
				org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("工程圖片或明確拒絕"))
			));

		jdbc.update("UPDATE quotation_draft SET image_declined = 0 WHERE id = ?", DRAFT_ID);
		jdbc.update("""
			UPDATE quotation_draft SET scheme_id = (
				SELECT id FROM quotation_scheme WHERE code = 'BLANK'
			) WHERE id = ?
			""", DRAFT_ID);
		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.missingFields").value(
				org.hamcrest.Matchers.hasItem("工程圖片或明確拒絕")
			));

		for (String schemeCode : new String[] { "CNS", "GENERAL", "SALES" }) {
			jdbc.update("""
				UPDATE quotation_draft SET scheme_id = (
					SELECT id FROM quotation_scheme WHERE code = ?
				) WHERE id = ?
				""", schemeCode, DRAFT_ID);
			mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}", DRAFT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.missingFields").value(
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("工程圖片或明確拒絕"))
				));
		}
	}

	@Test
	void servesOnlyDatabaseOwnedSelectedDraftAndFormalImages() throws Exception {
		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}/selected-image", DRAFT_ID))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("image/jpeg"))
			.andExpect(content().bytes("pending-image".getBytes(StandardCharsets.UTF_8)));

		mockMvc.perform(get("/api/admin/quotations/{quotationId}/selected-image", QUOTATION_ID))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("image/jpeg"))
			.andExpect(content().bytes("formal-image".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void rejectsSelectedImagePathTraversal() throws Exception {
		jdbc.update(
			"UPDATE pending_image SET staging_path = '../outside.jpg' WHERE message_id = 'draft-selected-image'"
		);
		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}/selected-image", DRAFT_ID))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_IMAGE_PATH"));
	}

	@Test
	void rejectsSelectedPendingImageOwnedByAnotherSource() throws Exception {
		jdbc.update(
			"UPDATE pending_image SET source_id = 'U-other-owner' WHERE message_id = 'draft-selected-image'"
		);

		mockMvc.perform(get("/api/admin/quotation-drafts/{draftId}/selected-image", DRAFT_ID))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void rejectsFormalSelectedImageSymbolicLinks() throws Exception {

		Path symbolicLink = formalImagePath().resolveSibling("linked-image.jpg");
		try {
			Files.deleteIfExists(symbolicLink);
			Files.createSymbolicLink(symbolicLink, formalImagePath().getFileName());
		}
		catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
			org.junit.jupiter.api.Assumptions.abort("目前檔案系統不允許建立符號連結");
		}
		jdbc.update(
			"UPDATE asset SET file_path = ? WHERE message_id = 'formal-selected-image'",
			Path.of("報價單", "20260811-01", "linked-image.jpg").toString()
		);
		mockMvc.perform(get("/api/admin/quotations/{quotationId}/selected-image", QUOTATION_ID))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_IMAGE_PATH"));
	}

	@Test
	void listsFormalQuotationsWithFiltersWithoutExposingPathsOrDestinations() throws Exception {
		mockMvc.perform(
			get("/api/admin/quotations")
				.param("quotationNumber", "20260811")
				.param("company", "正定")
				.param("work", "中壢")
				.param("dateFrom", "2026-08-01")
				.param("dateTo", "2026-08-31")
				.param("status", "PDF_FAILED")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value(QUOTATION_ID))
			.andExpect(jsonPath("$.data[0].quotationNumber").value("20260811-01"))
			.andExpect(jsonPath("$.data[0].pdfStatus").value("FAILED"))
			.andExpect(jsonPath("$.data[0].lineStatus").value("FAILED"))
			.andExpect(jsonPath("$.data[0].lineAttemptCount").value(1))
			.andExpect(jsonPath("$.data[0].relativePath").doesNotExist())
			.andExpect(jsonPath("$.data[0].destinationId").doesNotExist())
			.andExpect(jsonPath("$.data[0].downloadToken").doesNotExist())
			.andExpect(jsonPath("$.pagination.page").value(1));
	}

	@Test
	void returnsCustomerVisibleSnapshotLinesAndFileActions() throws Exception {
		mockMvc.perform(get("/api/admin/quotations/{quotationId}", QUOTATION_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationNumber").value("20260811-01"))
			.andExpect(jsonPath("$.schemeCode").value("CNS"))
			.andExpect(jsonPath("$.salesRepresentative").value("陳業務"))
			.andExpect(jsonPath("$.lines.length()").value(1))
			.andExpect(jsonPath("$.lines[0].itemName").value("外部鷹架"))
			.andExpect(jsonPath("$.files.xlsx.downloadUrl").value(
				"/api/admin/quotations/701/files/XLSX"
			))
			.andExpect(jsonPath("$.files.pdf.canRetry").value(true))
			.andExpect(jsonPath("$.delivery.canRetry").value(false))
			.andExpect(jsonPath("$.delivery.destinationId").doesNotExist());
	}

	@Test
	void downloadsOnlyTheReadyDatabaseBackedWorkbook() throws Exception {
		mockMvc.perform(get("/api/admin/quotations/{quotationId}/files/XLSX", QUOTATION_ID))
			.andExpect(status().isOk())
			.andExpect(header().string(
				"Content-Disposition",
				org.hamcrest.Matchers.containsString("quote.xlsx")
			))
			.andExpect(content().bytes("xlsx-content".getBytes(StandardCharsets.UTF_8)));

		mockMvc.perform(get("/api/admin/quotations/{quotationId}/files/PDF", QUOTATION_ID))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("FILE_NOT_READY"));
	}

	@Test
	void retriesPdfAndLineOnlyAgainstThePersistedQuotationState() throws Exception {
		when(pdfService.retry(QUOTATION_ID)).thenReturn(
			new QuotationPdfService.PdfExportResult(
				QUOTATION_ID,
				"20260811-01",
				workbookPath(),
				workbookPath().resolveSibling("quote.pdf"),
				"pdf-hash",
				100
			)
		);
		when(deliveryService.deliverFinal(QUOTATION_ID, "U-sensitive-destination")).thenReturn(
			new QuotationDeliveryResult(
				QUOTATION_ID,
				52,
				2,
				QuotationDeliveryStatus.SENT,
				false,
				"provider-message",
				null
			)
		);

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/pdf-retries", QUOTATION_ID)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationId").value(QUOTATION_ID))
			.andExpect(jsonPath("$.quotationNumber").value("20260811-01"));
		verify(pdfService).retry(QUOTATION_ID);
		jdbc.update("UPDATE quotation SET status = 'READY' WHERE id = ?", QUOTATION_ID);
		jdbc.update("""
			UPDATE quotation_file
			SET status = 'READY', error_message = NULL
			WHERE quotation_id = ? AND file_kind = 'PDF'
			""", QUOTATION_ID);

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/line-retries", QUOTATION_ID)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SENT"))
			.andExpect(jsonPath("$.providerMessageId").doesNotExist());
		verify(deliveryService).deliverFinal(eq(QUOTATION_ID), eq("U-sensitive-destination"));

		Integer auditCount = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM admin_audit_log
			WHERE entity_type = 'QUOTATION' AND entity_id = ?
				AND action IN ('QUOTATION_PDF_RETRY', 'QUOTATION_LINE_RETRY')
			""", Integer.class, String.valueOf(QUOTATION_ID));
		org.assertj.core.api.Assertions.assertThat(auditCount).isEqualTo(2);
	}

	@Test
	void refusesLineRetryWhenTheLatestDeliveryAlreadySucceeded() throws Exception {
		jdbc.update("UPDATE quotation SET status = 'READY' WHERE id = ?", QUOTATION_ID);
		jdbc.update("""
			INSERT INTO quotation_delivery_attempt (
				quotation_id, destination_type, destination_id, delivery_kind,
				status, provider_message_id, completed_at
			)
			VALUES (?, 'LINE_USER', 'U-sensitive-destination', 'FINAL',
				'SENT', 'provider-message', CURRENT_TIMESTAMP)
			""", QUOTATION_ID);

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/line-retries", QUOTATION_ID)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("LINE_RETRY_NOT_ALLOWED"));
		verifyNoInteractions(deliveryService);
	}

	@Test
	void revokesPdfLinksWithoutReturningTheirSecrets() throws Exception {
		jdbc.update("""
			INSERT INTO quotation_download_token (
				quotation_id, file_id, purpose, token_hash, expires_at
			)
			SELECT ?, id, 'PDF', 'secret-hash', '2099-01-01T00:00:00Z'
			FROM quotation_file
			WHERE quotation_id = ? AND file_kind = 'PDF'
			""", QUOTATION_ID, QUOTATION_ID);

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/download-links/revocation", QUOTATION_ID)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationId").value(QUOTATION_ID))
			.andExpect(jsonPath("$.revoked").value(true))
			.andExpect(content().string(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.containsString("secret-hash")
			)));

		String revokedAt = jdbc.queryForObject(
			"SELECT revoked_at FROM quotation_download_token WHERE token_hash = 'secret-hash'",
			String.class
		);
		org.assertj.core.api.Assertions.assertThat(revokedAt).isNotBlank();
	}

	@Test
	void regeneratesACopyableShortLivedHttpsPdfUrlWithoutSeparateRawToken() throws Exception {
		Files.writeString(pdfPath(), "%PDF-1.4\n%%EOF", StandardCharsets.US_ASCII);
		jdbc.update("UPDATE quotation SET status = 'READY' WHERE id = ?", QUOTATION_ID);
		jdbc.update("""
			UPDATE quotation_file
			SET status = 'READY', error_message = NULL, file_size = ?, content_hash = 'pdf-hash'
			WHERE quotation_id = ? AND file_kind = 'PDF'
			""", Files.size(pdfPath()), QUOTATION_ID);

		mockMvc.perform(post("/api/admin/quotations/{quotationId}/download-links", QUOTATION_ID)
			.header("X-Local-Admin-Request", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quotationId").value(QUOTATION_ID))
			.andExpect(jsonPath("$.regenerated").value(true))
			.andExpect(jsonPath("$.expiresAt").isNotEmpty())
			.andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith(
				"https://quotation.example.test/quotation-downloads/"
			)))
			.andExpect(jsonPath("$.token").doesNotExist());

		Integer activeLinks = jdbc.queryForObject("""
			SELECT COUNT(*)
			FROM quotation_download_token
			WHERE quotation_id = ? AND purpose = 'PDF' AND revoked_at IS NULL
			""", Integer.class, QUOTATION_ID);
		org.assertj.core.api.Assertions.assertThat(activeLinks).isEqualTo(1);
		String auditSummary = jdbc.queryForObject("""
			SELECT summary_json FROM admin_audit_log
			WHERE entity_type = 'QUOTATION' AND entity_id = ?
				AND action = 'QUOTATION_PDF_LINK_REGENERATE'
			ORDER BY id DESC LIMIT 1
			""", String.class, String.valueOf(QUOTATION_ID));
		org.assertj.core.api.Assertions.assertThat(auditSummary)
			.doesNotContain("quotation-downloads", "token");
	}

	@Test
	void returnsSelectedImageAndSanitizedAuditHistoryInFormalDetail() throws Exception {
		mockMvc.perform(get("/api/admin/quotations/{quotationId}", QUOTATION_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectedImageUrl").value(
				"/api/admin/quotations/701/selected-image"
			))
			.andExpect(jsonPath("$.auditRecords[*].action").value(
				org.hamcrest.Matchers.hasItems(
					"QUOTATION_CONFIRMED",
					"QUOTATION_GENERATION",
					"QUOTATION_DELIVERY_FINAL"
				)
			))
			.andExpect(jsonPath("$.auditRecords[*].outcome").value(
				org.hamcrest.Matchers.hasItems("SUCCEEDED", "DONE", "FAILED")
			))
			.andExpect(jsonPath("$.auditRecords[0].summaryJson").doesNotExist())
			.andExpect(jsonPath("$.auditRecords[0].entityId").doesNotExist())
			.andExpect(jsonPath("$.auditRecords[0].destinationId").doesNotExist())
			.andExpect(jsonPath("$.auditRecords[0].errorMessage").doesNotExist())
			.andExpect(jsonPath("$.assetPath").doesNotExist())
			.andExpect(jsonPath("$.shareToken").doesNotExist());
	}

	@Test
	void servesTheFormalQuotationManagementControlsFromTheLocalAdminPage() throws Exception {
		mockMvc.perform(get("/admin/index.html").header("Host", "localhost:8088"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("正式報價管理")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-retry-pdf")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-retry-line")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-regenerate-link")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-revoke-links")));
		mockMvc.perform(get("/admin/index.html").header("Host", "localhost:8088"))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("草稿管理")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-draft-rows")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-selected-image")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-audit-records")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("quotation-copy-link")));
		mockMvc.perform(get("/admin/admin.js").header("Host", "localhost:8088"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("報價名稱待補")))
			.andExpect(content().string(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.containsString("尚未命名")
			)))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("已選圖片")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("已明確拒絕")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("尚待圖片決定")));
	}

	@Test
	void rejectsInvalidPagingAndUnknownFileKinds() throws Exception {
		mockMvc.perform(get("/api/admin/quotations").param("pageSize", "1000"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/admin/quotations/{quotationId}/files/../../schema.sql", QUOTATION_ID))
			.andExpect(status().isNotFound());
	}

	private String relativeWorkbookPath() {
		return Path.of("報價單", "20260811-01", "quote.xlsx").toString();
	}

	private Path workbookPath() {
		return Path.of(
			System.getProperty("java.io.tmpdir"),
			"assets-manager-quotation-management-test",
			relativeWorkbookPath()
		);
	}

	private Path pdfPath() {
		return workbookPath().resolveSibling("quote.pdf");
	}

	private String relativeFormalImagePath() {
		return Path.of("報價單", "20260811-01", "image-01.jpg").toString();
	}

	private Path formalImagePath() {
		return workbookPath().resolveSibling("image-01.jpg");
	}

	private Path pendingImagePath() {
		return Path.of(
			System.getProperty("java.io.tmpdir"),
			"assets-manager-quotation-management-assets",
			".pending",
			"admin-selected.jpg"
		);
	}
}
