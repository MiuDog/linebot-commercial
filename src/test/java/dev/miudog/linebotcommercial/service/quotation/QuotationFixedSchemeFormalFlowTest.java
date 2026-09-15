package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-fixed-formal-flow-test",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationFixedSchemeFormalFlowTest {

	@Autowired
	QuotationCalculationService calculationService;

	@Autowired
	QuotationConfirmationService confirmationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@TempDir
	Path outputRoot;

	// 方法：以真實 SQLite 主檔完成 CNS／一般架的計價、確認快照與正式跨頁 Excel 產出。
	@ParameterizedTest
	@ValueSource(strings = {"CNS", "GENERAL"})
	@Transactional
	void retainsAllFixedRowsExceptExplicitRemovalAndAppendsTwoTemporaryRows(String schemeCode) throws Exception {
		int fixedCatalogSize = "CNS".equals(schemeCode) ? 21 : 20;
		QuotationCalculationResult calculation = calculationService.calculate(
			new QuotationCalculationRequest(
				schemeCode,
				List.of(new QuotationCalculationRequest.StandardItemIntent(
					"EXTERNAL_SCAFFOLD",
					new BigDecimal("2"),
					new BigDecimal("999999")
				)),
				List.of(
					temporaryItem("臨時護欄", "高 120cm", "式", "5000", "1", "現場施作"),
					temporaryItem("臨時運費", "單趟", "式", "800", "1", "依現場實算")
				),
				Set.of("CROSS_BRACE")
			)
		);

		assertThat(calculation.internalLines()).hasSize(fixedCatalogSize - 1 + 2);
		assertThat(calculation.internalLines()).noneMatch(line -> "CROSS_BRACE".equals(line.itemCode()));
		assertThat(calculation.internalLines())
			.filteredOn(line -> line.origin() == QuotationCalculationResult.LineOrigin.STANDARD)
			.filteredOn(line -> line.quantity() == null)
			.hasSize(fixedCatalogSize - 2)
			.allSatisfy(line -> assertThat(line.lineAmount()).isNull());
		assertThat(calculation.internalLines())
			.filteredOn(line -> line.origin() == QuotationCalculationResult.LineOrigin.TEMPORARY)
			.hasSize(2);

		long draftId = insertAwaitingConfirmationDraft(schemeCode);
		String eventId = "formal-evidence-" + UUID.randomUUID();
		QuotationConfirmationResult confirmation = confirmationService.confirm(
			confirmationCommand(draftId, schemeCode, eventId, calculation)
		);

		// 資料庫 API：驗證正式不可變快照保留全部鎖價列、空白數量及兩筆臨時品項。
		Map<String, Object> persisted = jdbcTemplate.queryForMap(
			"""
			SELECT COUNT(*) AS line_count,
				SUM(CASE WHEN quantity IS NULL AND line_amount IS NULL THEN 1 ELSE 0 END) AS blank_count,
				SUM(CASE WHEN line_kind = 'CUSTOM' THEN 1 ELSE 0 END) AS custom_count,
				SUM(CASE WHEN item_code_snapshot = 'CROSS_BRACE' THEN 1 ELSE 0 END) AS removed_count
			FROM quotation_line
			WHERE quotation_id = ?
			""",
			confirmation.quotationId()
		);
		assertThat(number(persisted, "line_count")).isEqualTo(fixedCatalogSize - 1 + 2);
		assertThat(number(persisted, "blank_count")).isEqualTo(fixedCatalogSize - 2);
		assertThat(number(persisted, "custom_count")).isEqualTo(2);
		assertThat(number(persisted, "removed_count")).isZero();

		QuotationWorkbookService workbookService = new QuotationWorkbookService(
			new ObjectMapper(),
			new QuotationOutputDirectoryService(outputRoot.toString()),
			new DefaultResourceLoader()
		);
		QuotationWorkbookService.GenerationResult workbook = workbookService.generateConfirmed(
			header(confirmation),
			confirmation,
			calculation
		);

		try (WorkbookView view = new WorkbookView(workbook.path())) {
			assertThat(view.sheetCount()).isEqualTo(2);
			assertThat(view.itemNames(schemeCode)).hasSize(fixedCatalogSize - 1 + 2)
				.doesNotContain("交叉拉桿")
				.contains("臨時護欄", "臨時運費");
			assertThat(view.text(1, "D11")).isEqualTo("2");
			assertThat(view.formula(1, "G11")).isEqualTo("IFERROR(D11*F11,0)");
			assertThat(view.text(1, "D12")).isEmpty();
			assertThat(view.text(1, "G12")).isEmpty();
		}
	}

	// 方法：建立已由使用者確認且不會寫回固定主檔的臨時品項。
	private QuotationCalculationRequest.CustomItem temporaryItem(
		String itemName,
		String specification,
		String unit,
		String unitPrice,
		String quantity,
		String remark
	) {
		return new QuotationCalculationRequest.CustomItem(
			itemName,
			specification,
			unit,
			new BigDecimal(unitPrice),
			new BigDecimal(quantity),
			remark,
			true,
			new BigDecimal("999999")
		);
	}

	// 方法：建立資料庫端等待正式確認的草稿，供確認交易核對狀態與修訂版。
	private long insertAwaitingConfirmationDraft(String schemeCode) {
		String draftKey = "formal-evidence-" + UUID.randomUUID();

		// 資料庫 API：以真實方案與啟用範本建立可確認草稿。
		jdbcTemplate.update(
			"""
			INSERT INTO quotation_draft (
				draft_key, source_type, source_id, requester_id, quotation_name,
				company_name, work_name, contact_name, customer_phone,
				customer_email, project_location, scheme_id, status,
				confirmation_revision, revision
			)
			SELECT ?, 'user', ?, 'U001', '範例工程-正式整合測試',
				'範例工程', ?, '王先生', '04-12345678',
				'quote@example.test', '台中市', id, 'AWAITING_CONFIRMATION', 2, 2
			FROM quotation_scheme
			WHERE code = ?
			""",
			draftKey,
			"U-" + UUID.randomUUID(),
			schemeCode + "正式整合測試",
			schemeCode
		);

		// 資料庫 API：讀回同一交易內建立的草稿主鍵。
		Long draftId = jdbcTemplate.queryForObject(
			"SELECT id FROM quotation_draft WHERE draft_key = ?",
			Long.class,
			draftKey
		);

		// 資料庫 API：加入每張報價由使用者輸入的必要業務承辦欄位。
		jdbcTemplate.update(
			"INSERT INTO quotation_draft_field (draft_id, field_key, field_value) VALUES (?, 'salesRepresentative', '陳業務')",
			draftId
		);
		return draftId;
	}

	// 方法：建立與資料庫草稿下一修訂版一致的明確確認命令。
	private QuotationConfirmationCommand confirmationCommand(
		long draftId,
		String schemeCode,
		String eventId,
		QuotationCalculationResult calculation
	) {
		QuotationDraftSnapshot draft = new QuotationDraftSnapshot(
			draftId,
			3,
			QuotationDraftStatus.CONFIRMED,
			schemeCode,
			Map.of("companyName", "範例工程", "workName", schemeCode + "正式整合測試"),
			List.of(),
			List.of(),
			null,
			false,
			true,
			true,
			eventId
		);
		return new QuotationConfirmationCommand(
			new QuotationConfirmationIntent(draft, eventId, null),
			calculation
		);
	}

	// 方法：建立正式 Excel 所需抬頭，日期與單號完全沿用確認交易結果。
	private QuotationWorkbookService.Header header(QuotationConfirmationResult confirmation) {
		return new QuotationWorkbookService.Header(
			confirmation.quotationNumber(),
			confirmation.quotationDate().toString(),
			"範例工程",
			"04-12345678",
			"quote@example.test",
			"王先生",
			"台中市",
			"",
			confirmation.validUntil().toString()
		);
	}

	// 方法：將 SQLite 聚合欄位轉成可穩定斷言的整數。
	private int number(Map<String, Object> row, String key) {
		return ((Number) row.get(key)).intValue();
	}

	private static class WorkbookView implements AutoCloseable {

		private final ZipFile workbook;
		private final List<String> sharedStrings;
		private final List<Document> sheets;

		// 方法：開啟正式 XLSX 並載入所有工作表與共用字串。
		WorkbookView(Path path) throws Exception {
			workbook = new ZipFile(path.toFile());
			sharedStrings = readSharedStrings(workbook);
			sheets = new ArrayList<>();
			for (int index = 1; ; index++) {
				ZipEntry entry = workbook.getEntry("xl/worksheets/sheet" + index + ".xml");
				if (entry == null) break;

				// 外部 API：解析 OOXML 工作表以驗證正式輸出內容。
				try (InputStream input = workbook.getInputStream(entry)) {
					sheets.add(document(input));
				}
			}
		}

		// 方法：回傳正式活頁簿的工作表數量。
		int sheetCount() {
			return sheets.size();
		}

		// 方法：跨頁讀取所有正式品項名稱，排除第一個未使用列提示。
		List<String> itemNames(String schemeCode) {
			int lastRow = "CNS".equals(schemeCode) ? 31 : 30;
			List<String> names = new ArrayList<>();
			for (int sheet = 1; sheet <= sheets.size(); sheet++) {
				for (int row = 11; row <= lastRow; row++) {
					String lineNumber = text(sheet, "A" + row);
					if (lineNumber.isBlank()) continue;

					names.add(text(sheet, "B" + row));
				}
			}
			return List.copyOf(names);
		}

		// 方法：讀取指定工作表及儲存格顯示值。
		String text(int sheetNumber, String address) {
			Element cell = cell(sheetNumber, address);
			if (cell == null) return "";

			if ("inlineStr".equals(cell.getAttribute("t"))) return descendantText(cell, "t");

			String value = descendantText(cell, "v");
			if ("s".equals(cell.getAttribute("t")) && !value.isBlank()) return sharedStrings.get(Integer.parseInt(value));

			return value;
		}

		// 方法：讀取指定工作表及儲存格公式。
		String formula(int sheetNumber, String address) {
			Element cell = cell(sheetNumber, address);
			if (cell == null) return null;

			NodeList formulas = cell.getElementsByTagNameNS("*", "f");
			return formulas.getLength() == 0 ? null : formulas.item(0).getTextContent();
		}

		// 方法：依位址尋找單一 OOXML 儲存格。
		private Element cell(int sheetNumber, String address) {
			NodeList cells = sheets.get(sheetNumber - 1).getElementsByTagNameNS("*", "c");
			for (int index = 0; index < cells.getLength(); index++) {
				Element cell = (Element) cells.item(index);
				if (address.equals(cell.getAttribute("r"))) return cell;
			}
			return null;
		}

		// 方法：關閉 XLSX 封裝檔案。
		@Override
		public void close() throws Exception {
			workbook.close();
		}

		// 方法：讀取 OOXML 共用字串表；不存在時使用空清單。
		private static List<String> readSharedStrings(ZipFile workbook) throws Exception {
			ZipEntry entry = workbook.getEntry("xl/sharedStrings.xml");
			if (entry == null) return List.of();

			// 外部 API：解析活頁簿共用字串，供儲存格索引還原文字。
			try (InputStream input = workbook.getInputStream(entry)) {
				Document document = document(input);
				NodeList items = document.getElementsByTagNameNS("*", "si");
				List<String> values = new ArrayList<>();
				for (int index = 0; index < items.getLength(); index++) {
					values.add(descendantText((Element) items.item(index), "t"));
				}
				return List.copyOf(values);
			}
		}

		// 方法：以關閉外部實體功能的解析器安全讀取 OOXML。
		private static Document document(InputStream input) throws Exception {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

			// 外部 API：解析只來自本次測試產生之 XLSX 的 XML 內容。
			return factory.newDocumentBuilder().parse(input);
		}

		// 方法：串接指定元素下所有同名子節點文字。
		private static String descendantText(Element element, String localName) {
			NodeList descendants = element.getElementsByTagNameNS("*", localName);
			StringBuilder value = new StringBuilder();
			for (int index = 0; index < descendants.getLength(); index++) {
				value.append(descendants.item(index).getTextContent());
			}
			return value.toString();
		}
	}
}
