package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationDownloadLink;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-download-db",
		"spring.datasource.url=jdbc:sqlite::memory:",
		"app.quotation.root-path=${java.io.tmpdir}/assets-manager-download-output",
		"app.public-base-url=https://quotation.example.test"
	}
)
class QuotationDownloadControllerTest {

	private static final Path OUTPUT_ROOT = Path.of(
		System.getProperty("java.io.tmpdir"),
		"assets-manager-download-output"
	);

	@Autowired
	QuotationDownloadService service;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MockMvc mockMvc;

	// 建立每個測試使用的報價單根目錄。
	@BeforeEach
	void createOutputRoot() throws Exception {
		// 外部呼叫：建立安全下載測試所需的報價根目錄。
		Files.createDirectories(OUTPUT_ROOT.resolve("報價單"));
	}

	// 驗證 PDF 權杖只保存雜湊，下載回應禁止快取並使用正式檔名。
	@Test
	@Transactional
	void issuesHashedTokenAndDownloadsReadyPdf() throws Exception {
		long quotationId = insertReadyPdf("20260811-01", "正定公司-中壢案 20260811-01.pdf", "%PDF-test");
		QuotationDownloadLink link = service.issuePdfLink(quotationId, Duration.ofDays(7));

		assertThat(link.url()).startsWith("https://quotation.example.test/quotation-downloads/");
		String rawToken = link.url().substring(link.url().lastIndexOf('/') + 1);
		// 外部呼叫：確認資料庫未保存可直接使用的下載權杖。
		String storedHash = jdbcTemplate.queryForObject(
			"SELECT token_hash FROM quotation_download_token WHERE quotation_id = ?",
			String.class,
			quotationId
		);
		assertThat(storedHash).isNotEqualTo(rawToken).hasSize(64);

		mockMvc.perform(get("/quotation-downloads/{token}", rawToken))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/pdf"))
			.andExpect(content().bytes("%PDF-test".getBytes(StandardCharsets.UTF_8)))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(
				header().string(
					"Content-Disposition",
					org.hamcrest.Matchers.containsString("attachment")
				)
			);
	}

	// 驗證過期、撤銷或被修改的下載權杖都只回傳 404。
	@Test
	@Transactional
	void rejectsExpiredRevokedAndTamperedTokens() throws Exception {
		long quotationId = insertReadyPdf("20260811-02", "乙公司-第二案 20260811-02.pdf", "%PDF-two");
		QuotationDownloadLink link = service.issuePdfLink(quotationId, Duration.ofDays(7));
		String token = link.url().substring(link.url().lastIndexOf('/') + 1);

		// 外部呼叫：模擬權杖到期。
		jdbcTemplate.update(
			"UPDATE quotation_download_token SET expires_at = '2000-01-01T00:00:00Z' WHERE quotation_id = ?",
			quotationId
		);
		mockMvc.perform(get("/quotation-downloads/{token}", token)).andExpect(status().isNotFound());
		mockMvc.perform(get("/quotation-downloads/{token}", token + "x")).andExpect(status().isNotFound());
	}

	// 驗證遭資料庫竄改而跳脫報價根目錄的檔案路徑不可被讀取。
	@Test
	@Transactional
	void rejectsDatabasePathTraversal() throws Exception {
		long quotationId = insertReadyPdf("20260811-03", "丙公司-第三案 20260811-03.pdf", "%PDF-three");
		QuotationDownloadLink link = service.issuePdfLink(quotationId, Duration.ofDays(7));
		String token = link.url().substring(link.url().lastIndexOf('/') + 1);
		// 外部呼叫：模擬資料庫檔案路徑遭竄改。
		jdbcTemplate.update(
			"UPDATE quotation_file SET relative_path = '../../outside.pdf' WHERE quotation_id = ?",
			quotationId
		);

		mockMvc.perform(get("/quotation-downloads/{token}", token)).andExpect(status().isNotFound());
	}

	@Test
	@Transactional
	void rejectsAPdfSymlinkThatResolvesOutsideTheQuotationFolder() throws Exception {
		long quotationId = insertReadyPdf("20260811-04", "丁公司-第四案 20260811-04.pdf", "%PDF-four");
		Path stored = OUTPUT_ROOT.resolve("報價單/20260811-04/丁公司-第四案 20260811-04.pdf");
		Path outside = OUTPUT_ROOT.resolve("outside.pdf");
		Files.writeString(outside, "%PDF-outside", StandardCharsets.UTF_8);
		Files.delete(stored);
		try {
			Files.createSymbolicLink(stored, outside);
		}
		catch (Exception exception) {
			Assumptions.abort("目前執行環境不允許建立 symbolic link");
		}

		assertThatThrownBy(() -> service.issuePdfLink(quotationId, Duration.ofDays(7)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("不存在");
	}

	// 建立已完成 PDF 的正式報價與實體測試檔案。
	private long insertReadyPdf(String folderName, String fileName, String content) throws Exception {
		String draftKey = "download-" + UUID.randomUUID();
		// 外部呼叫：建立已確認草稿與正式報價資料。
		jdbcTemplate.update("""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, company_name, work_name, scheme_id, status
			)
			SELECT ?, 'user', ?, '測試公司', '測試工程', id, 'CONFIRMED'
			FROM quotation_scheme WHERE code = 'GENERAL'
			""", draftKey, "U-" + UUID.randomUUID());
		Long draftId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			draftKey
		);
		jdbcTemplate.update("""
			INSERT INTO quotation (
				draft_id, quotation_no, quotation_name, sequence_date, sequence_number,
				company_name, work_name, quotation_date, valid_until, scheme_id, template_id, status
			)
			SELECT ?, ?, ?, '2026-08-11', ?, '測試公司', '測試工程', '2026-08-11',
				'2026-08-26', s.id, t.id, 'READY'
			FROM quotation_scheme s
			JOIN quotation_template t ON t.scheme_id = s.id AND t.is_active = 1
			WHERE s.code = 'GENERAL'
			""",
			draftId,
			"Q-" + UUID.randomUUID(),
			fileName.substring(0, fileName.length() - 4),
			Integer.parseInt(folderName.substring(folderName.length() - 2))
		);
		Long quotationId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation WHERE draft_id = ?",
			Long.class,
			draftId
		);
		Path directory = OUTPUT_ROOT.resolve("報價單").resolve(folderName);
		// 外部呼叫：建立並寫入測試 PDF 檔案。
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8);
		jdbcTemplate.update("""
			INSERT INTO quotation_file (
				quotation_id, file_kind, relative_path, content_type, file_size, status
			)
			VALUES (?, 'PDF', ?, 'application/pdf', ?, 'READY')
			""",
			quotationId,
			"報價單/" + folderName + "/" + fileName,
			content.getBytes(StandardCharsets.UTF_8).length
		);
		return quotationId;
	}
}
