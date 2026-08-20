package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationWorkbookServiceTest {

	@TempDir
	Path root;

	// Windows 的 TEMP 可能是 8.3 短檔名（例如 RUNNER~1），服務會把路徑解析成
	// 真實長路徑後回傳；先在這裡正規化，讓預期值與服務回傳值使用同一種表示法。
	@BeforeEach
	void resolveRealRoot() throws Exception {
		root = root.toRealPath();
	}

	@Test
	void writesDirectItemsAndFormulaTotalsIntoTheConfiguredQuotationDirectory() throws Exception {
		QuotationWorkbookService service = service();
		QuotationWorkbookService.GenerationResult result = service.generate(
			header("Q-2026-001"),
			quotation(
				"CNS",
				item("EXTERNAL_SCAFFOLD", "外部鷹架", "(CNS)", "m2", "220", "2.5", "550"),
				item("DUST_NET", "防塵網", "9針", "m2", "40", "3", "120")
			)
		);

		assertThat(result.path()).isRegularFile();
		assertThat(result.path().getParent()).isEqualTo(root.resolve("報價單").resolve("中區外牆工程"));
		assertThat(result.subtotal()).isEqualByComparingTo("670");
		assertThat(result.tax()).isEqualByComparingTo("33.5");
		assertThat(result.total()).isEqualByComparingTo("703.5");

		try (WorkbookReader workbook = new WorkbookReader(result.path())) {
			assertThat(workbook.text("C4")).isEqualTo("範例客戶");
			assertThat(workbook.text("G4")).isEqualTo("Q-2026-001");
			assertThat(workbook.text("G6")).isEqualTo("業務員");
			assertThat(workbook.text("G7")).isEqualTo("zd0975305717@gmail.com");
			assertThat(workbook.text("G8")).isEqualTo("04-26380655  正定傳真:04-26380605");
			assertThat(workbook.text("B11")).isEqualTo("外部鷹架");
			assertThat(workbook.number("D11")).isEqualByComparingTo("2.5");
			assertThat(workbook.formula("G11")).isEqualTo("IFERROR(D11*F11,0)");
			assertThat(workbook.number("G11")).isEqualByComparingTo("550");
			assertThat(workbook.text("B12")).isEqualTo("防塵網");
			assertThat(workbook.text("B13")).isEqualTo("以下空白");
			assertThat(workbook.text("B14")).isEmpty();
			assertThat(workbook.formula("G33")).isEqualTo("SUM(G11:G31)");
			assertThat(workbook.number("G33")).isEqualByComparingTo("670");
			assertThat(workbook.formula("G34")).isEqualTo("G33*5%");
			assertThat(workbook.number("G35")).isEqualByComparingTo("703.5");
		}

		assertEmbeddedMediaUnchanged(
			Path.of("outputs/excel-templates/quotation-template-CNS.xlsx"),
			result.path()
		);
	}

	@Test
	void writesSummaryOnlyAmountsWithoutExposingCalculationFormulas() throws Exception {
		QuotationWorkbookService service = service();
		QuotationWorkbookService.GenerationResult result = service.generate(
			header("M-2026-001"),
			quotation(
				"MARINE",
				item("MARINE_PACKAGE", "系統架搭設/拆除", "", "式", "102600", "1", "102600")
			)
		);

		try (WorkbookReader workbook = new WorkbookReader(result.path())) {
			assertThat(workbook.text("B11")).isEqualTo("系統架搭設/拆除");
			assertThat(workbook.formula("G11")).isNull();
			assertThat(workbook.number("G11")).isEqualByComparingTo("102600");
			assertThat(workbook.formula("G25")).isNull();
			assertThat(workbook.number("G27")).isEqualByComparingTo("107730");
		}
	}

	// 方法：正式船用輸出只揭露一筆彙總結果，且明細與合計均不含內部計算公式。
	@Test
	void writesConfirmedMarineAsOneFormulaFreeSummary() throws Exception {
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			"MARINE",
			List.of(
				calculationLine("INTERNAL_FRAME", "內部架材", "", "式", "67600", "1", "67600", 1),
				calculationLine("INTERNAL_CERTIFICATE", "內部簽證", "", "式", "35000", "1", "35000", 2)
			),
			List.of(),
			new BigDecimal("102600"),
			new BigDecimal("5130"),
			new BigDecimal("107730"),
			QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY
		);

		QuotationWorkbookService.GenerationResult result = service().generateConfirmed(
			header("2026081104"),
			confirmation(14L, 4, "船塢工程"),
			calculation
		);

		try (WorkbookReader workbook = new WorkbookReader(result.path())) {
			assertThat(workbook.text("B11")).isEqualTo("系統架搭設/拆除");
			assertThat(workbook.number("D11")).isEqualByComparingTo("1");
			assertThat(workbook.number("F11")).isEqualByComparingTo("102600");
			assertThat(workbook.number("G11")).isEqualByComparingTo("102600");
			assertThat(workbook.formula("G11")).isNull();
			assertThat(workbook.text("B12")).isEqualTo("以下空白");
			assertThat(workbook.number("G25")).isEqualByComparingTo("102600");
			assertThat(workbook.number("G27")).isEqualByComparingTo("107730");
			assertThat(workbook.formula("G25")).isNull();
			assertThat(workbook.formula("G27")).isNull();
		}
	}

	// 方法：五種正式輸出都完整保留來源範本既有的 Logo、蓋章及固定圖片封裝。
	@Test
	void preservesOriginalTemplateMediaForEveryConfirmedScheme() throws Exception {
		List<String> schemes = List.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
		for (int index = 0; index < schemes.size(); index++) {
			String schemeCode = schemes.get(index);
			QuotationCalculationResult calculation = singleLineCalculation(schemeCode);
			QuotationWorkbookService.GenerationResult result = service().generateConfirmed(
				header("20260811" + String.format("%02d", index + 10)),
				confirmation(100L + index, index + 10, schemeCode + "媒體驗證"),
				calculation
			);

			assertEmbeddedMediaUnchanged(
				Path.of("outputs/excel-templates/quotation-template-" + schemeCode + ".xlsx"),
				result.path()
			);
		}
	}

	@Test
	void writesConfirmedFixedRowsWithBlankQuantityAndExactAllocatedFileName() throws Exception {
		QuotationWorkbookService service = service();
		QuotationConfirmationResult confirmation = new QuotationConfirmationResult(
			12L,
			"2026081103",
			java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26),
			3,
			"20260811-03",
			"正定工程-台中港案 20260811-03"
		);
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			"GENERAL",
			List.of(),
			List.of(
				calculationLine("FRAME", "外部鷹架", "(一般料)", "m2", "180", null, null, 1),
				calculationLine("BRACE", "交叉拉桿", "(一般料)", "m2", "20", "3", "60", 2)
			),
			new BigDecimal("60.00"),
			new BigDecimal("3.00"),
			new BigDecimal("63.00"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);

		QuotationWorkbookService.GenerationResult result = service.generateConfirmed(
			header("2026081103"),
			confirmation,
			calculation
		);

		assertThat(result.path()).isEqualTo(
			root.resolve("報價單/20260811-03/正定工程-台中港案 20260811-03.xlsx")
				.toAbsolutePath()
				.normalize()
		);
		try (WorkbookReader workbook = new WorkbookReader(result.path())) {
			assertThat(workbook.text("B11")).isEqualTo("外部鷹架");
			assertThat(workbook.text("D11")).isEmpty();
			assertThat(workbook.text("G11")).isEmpty();
			assertThat(workbook.formula("G12")).isEqualTo("IFERROR(D12*F12,0)");
			assertThat(workbook.number("G32")).isEqualByComparingTo("60");
			assertThat(workbook.number("G34")).isEqualByComparingTo("63");
		}
	}

	@Test
	void embedsOnlyTheSelectedImageBelowAllItemsWithoutChangingTemplateMedia() throws Exception {
		Path selectedImage = root.resolve("selected.png");
		BufferedImage sourceImage = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
		ImageIO.write(sourceImage, "png", selectedImage.toFile());
		QuotationConfirmationResult confirmation = new QuotationConfirmationResult(
			13L,
			"2026081104",
			java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26),
			4,
			"20260811-04",
			"正定工程-圖片案 20260811-04"
		);
		QuotationCalculationResult calculation = new QuotationCalculationResult(
			"BLANK",
			List.of(),
			List.of(
				calculationLine(null, "搭設工程", "", "式", "50000", "1", "50000", 1),
				calculationLine(null, "技師簽證", "", "式", "35000", "1", "35000", 2)
			),
			new BigDecimal("85000.00"),
			new BigDecimal("4250.00"),
			new BigDecimal("89250.00"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);

		QuotationWorkbookService.GenerationResult result = service().generateConfirmed(
			header("2026081104"),
			confirmation,
			calculation,
			selectedImage
		);

		try (ZipFile workbook = new ZipFile(result.path().toFile())) {
			ZipEntry embedded = workbook.getEntry("xl/media/quotation-selected.png");
			assertThat(embedded).isNotNull();
			assertThat(workbook.getInputStream(embedded).readAllBytes()).isNotEmpty();
			String drawing = new String(
				workbook.getInputStream(workbook.getEntry("xl/drawings/drawing1.xml")).readAllBytes(),
				StandardCharsets.UTF_8
			);
			assertThat(drawing).contains("報價工程圖片", "<xdr:row>12</xdr:row>");
		}

		assertEmbeddedMediaUnchanged(
			Path.of("outputs/excel-templates/quotation-template-BLANK.xlsx"),
			result.path()
		);
		assertThat(Files.readAllBytes(selectedImage)).isNotEmpty();
	}

	@Test
	void splitsDynamicItemsAcrossWorksheetsAndKeepsGrandTotalsOnTheLastPage() throws Exception {
		QuotationConfirmationResult confirmation = confirmation(14L, 5, "分頁案");
		List<QuotationCalculationResult.QuotationLine> lines = new ArrayList<>();
		for (int index = 1; index <= 20; index++) {
			lines.add(
				calculationLine(
					null,
					"動態品項" + index,
					"規格" + index,
					"式",
					"100",
					"1",
					"100",
					index
				)
			);
		}
		QuotationCalculationResult calculation = calculation("BLANK", lines, "2000", "100", "2100");

		QuotationWorkbookService.GenerationResult result = service().generateConfirmed(
			header(confirmation.quotationNumber()),
			confirmation,
			calculation
		);

		try (
			ZipFile zipFile = new ZipFile(result.path().toFile());
			WorkbookReader firstPage = new WorkbookReader(result.path(), "xl/worksheets/sheet1.xml");
			WorkbookReader secondPage = new WorkbookReader(result.path(), "xl/worksheets/sheet2.xml")
		) {
			assertThat(zipFile.getEntry("xl/worksheets/sheet2.xml")).isNotNull();
			assertThat(firstPage.text("B11")).isEqualTo("動態品項1");
			assertThat(firstPage.text("B28")).isEqualTo("動態品項18");
			assertThat(firstPage.text("G32")).isEmpty();
			assertThat(secondPage.text("B11")).isEqualTo("動態品項19");
			assertThat(secondPage.text("B12")).isEqualTo("動態品項20");
			assertThat(secondPage.formula("G30"))
				.isEqualTo("SUM('空白'!G11:G28,'空白 (2)'!G11:G28)");
			assertThat(secondPage.number("G30")).isEqualByComparingTo("2000");
		}
	}

	@Test
	void movesSelectedImageToANewWorksheetWhenTheLastItemPageIsFull() throws Exception {
		Path selectedImage = root.resolve("full-page-selected.png");
		ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), "png", selectedImage.toFile());
		QuotationConfirmationResult confirmation = confirmation(15L, 6, "圖片分頁案");
		List<QuotationCalculationResult.QuotationLine> lines = new ArrayList<>();
		for (int index = 1; index <= 18; index++) {
			lines.add(calculationLine(null, "品項" + index, "", "式", "100", "1", "100", index));
		}

		QuotationWorkbookService.GenerationResult result = service().generateConfirmed(
			header(confirmation.quotationNumber()),
			confirmation,
			calculation("BLANK", lines, "1800", "90", "1890"),
			selectedImage
		);

		try (ZipFile workbook = new ZipFile(result.path().toFile())) {
			assertThat(workbook.getEntry("xl/worksheets/sheet2.xml")).isNotNull();
			String firstDrawing = entryText(workbook, "xl/drawings/drawing1.xml");
			String secondDrawing = entryText(workbook, "xl/drawings/drawing2.xml");
			assertThat(firstDrawing).doesNotContain("報價工程圖片");
			assertThat(secondDrawing).contains("報價工程圖片", "<xdr:row>10</xdr:row>");
		}
	}

	@Test
	void prefixesSalesQuotationNumbersWithoutDuplicatingThePrefix() throws Exception {
		QuotationWorkbookService service = service();
		QuotationWorkbookService.GenerationResult plainResult = service.generate(
			header("2026-001"),
			quotation("SALES", item("SALE", "銷售品項", "", "式", "100", "1", "100"))
		);
		QuotationWorkbookService.GenerationResult prefixedResult = service.generate(
			header("S2026-002"),
			quotation("SALES", item("SALE", "銷售品項", "", "式", "100", "1", "100"))
		);

		try (
			WorkbookReader plain = new WorkbookReader(plainResult.path());
			WorkbookReader prefixed = new WorkbookReader(prefixedResult.path())
		) {
			assertThat(plain.text("G4")).isEqualTo("S2026-001");
			assertThat(prefixed.text("G4")).isEqualTo("S2026-002");
		}
	}

	@Test
	void createsANewFileInsteadOfOverwritingAnExistingQuotation() {
		QuotationWorkbookService service = service();
		QuotationRequestValidationService.ValidatedQuotationRequest quotation = quotation(
			"BLANK",
			item("ITEM", "品項", "", "式", "100", "1", "100")
		);

		QuotationWorkbookService.GenerationResult first = service.generate(header("B-3"), quotation);
		QuotationWorkbookService.GenerationResult second = service.generate(header("B-3"), quotation);

		assertThat(first.path()).isNotEqualTo(second.path());
		assertThat(first.path()).isRegularFile();
		assertThat(second.path()).isRegularFile();
	}

	@Test
	void rejectsMoreItemsThanTheTemplateCanDisplay() {
		QuotationWorkbookService service = service();
		List<QuotationRequestValidationService.ResolvedItem> items = new ArrayList<>();
		for (int index = 1; index <= 19; index++) {
			items.add(item("ITEM_" + index, "品項" + index, "", "式", "1", "1", "1"));
		}

		assertThatThrownBy(() -> service.generate(header("B-1"), quotation("BLANK", items)))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("18");
	}

	@Test
	void rejectsUnresolvedNonDirectAmountsInsteadOfWritingZero() {
		QuotationWorkbookService service = service();
		QuotationRequestValidationService.ResolvedItem unresolved = new QuotationRequestValidationService.ResolvedItem(
			"DERIVED_ITEM",
			"衍生品項",
			"",
			BigDecimal.ONE,
			"式",
			BigDecimal.TEN,
			null,
			"",
			1,
			"DERIVED",
			true,
			"衍生品項 1 式",
			BigDecimal.ONE
		);

		assertThatThrownBy(() -> service.generate(header("B-2"), quotation("BLANK", unresolved)))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("尚未完成計價");
	}

	private QuotationWorkbookService service() {
		return new QuotationWorkbookService(
			new ObjectMapper(),
			new QuotationOutputDirectoryService(root.toString()),
			new DefaultResourceLoader()
		);
	}

	private QuotationConfirmationResult confirmation(long id, int sequence, String workName) {
		String folder = "20260811-" + String.format("%02d", sequence);
		return new QuotationConfirmationResult(
			id,
			"20260811" + String.format("%02d", sequence),
			java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26),
			sequence,
			folder,
			"正定工程-" + workName + " " + folder
		);
	}

	private QuotationCalculationResult calculation(
		String schemeCode,
		List<QuotationCalculationResult.QuotationLine> lines,
		String subtotal,
		String tax,
		String total
	) {
		return new QuotationCalculationResult(
			schemeCode,
			List.copyOf(lines),
			List.copyOf(lines),
			new BigDecimal(subtotal),
			new BigDecimal(tax),
			new BigDecimal(total),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	// 方法：建立可供五格式正式輸出共用的一筆計價結果，船用改採摘要呈現。
	private QuotationCalculationResult singleLineCalculation(String schemeCode) {
		QuotationCalculationResult.QuotationLine line = calculationLine(
			"TEST_ITEM",
			"測試品項",
			"測試規格",
			"式",
			"100",
			"1",
			"100",
			1
		);
		boolean summaryOnly = "MARINE".equals(schemeCode);
		return new QuotationCalculationResult(
			schemeCode,
			List.of(line),
			summaryOnly ? List.of() : List.of(line),
			new BigDecimal("100"),
			new BigDecimal("5"),
			new BigDecimal("105"),
			summaryOnly
				? QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY
				: QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	private QuotationWorkbookService.Header header(String quoteNo) {
		return new QuotationWorkbookService.Header(
			quoteNo,
			"2026-08-11",
			"範例客戶",
			"04-12345678",
			"customer@example.com",
			"王先生 0912345678",
			"臺中市",
			"業務員",
			"2026-09-10"
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest quotation(
		String schemeCode,
		QuotationRequestValidationService.ResolvedItem... items
	) {
		return quotation(schemeCode, List.of(items));
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest quotation(
		String schemeCode,
		List<QuotationRequestValidationService.ResolvedItem> items
	) {
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			"1.0",
			"中區外牆工程",
			schemeCode,
			BigDecimal.ONE,
			List.copyOf(items),
			null,
			List.of(),
			List.of(),
			List.of()
		);
	}

	private QuotationRequestValidationService.ResolvedItem item(
		String itemCode,
		String itemName,
		String specification,
		String unit,
		String unitPrice,
		String quantity,
		String lineAmount
	) {
		return new QuotationRequestValidationService.ResolvedItem(
			itemCode,
			itemName,
			specification,
			new BigDecimal(quantity),
			unit,
			new BigDecimal(unitPrice),
			new BigDecimal(lineAmount),
			"(實做實算)",
			1,
			"DIRECT",
			true,
			itemName,
			BigDecimal.ONE
		);
	}

	private QuotationCalculationResult.QuotationLine calculationLine(
		String itemCode,
		String itemName,
		String specification,
		String unit,
		String unitPrice,
		String quantity,
		String lineAmount,
		int displayOrder
	) {
		return new QuotationCalculationResult.QuotationLine(
			itemCode,
			itemName,
			specification,
			unit,
			new BigDecimal(unitPrice),
			quantity == null ? null : new BigDecimal(quantity),
			lineAmount == null ? null : new BigDecimal(lineAmount),
			"(實做實算)",
			displayOrder,
			"DIRECT",
			QuotationCalculationResult.LineOrigin.STANDARD,
			true
		);
	}

	private void assertEmbeddedMediaUnchanged(Path template, Path generated) throws Exception {
		try (ZipFile expected = new ZipFile(template.toFile()); ZipFile actual = new ZipFile(generated.toFile())) {
			for (ZipEntry entry : expected.stream().filter(candidate -> candidate.getName().startsWith("xl/media/")).toList()) {
				ZipEntry actualEntry = actual.getEntry(entry.getName());
				assertThat(actualEntry).isNotNull();
				assertThat(actual.getInputStream(actualEntry).readAllBytes())
					.isEqualTo(expected.getInputStream(entry).readAllBytes());
			}
		}
	}

	private String entryText(ZipFile workbook, String entryName) throws Exception {
		return new String(
			workbook.getInputStream(workbook.getEntry(entryName)).readAllBytes(),
			StandardCharsets.UTF_8
		);
	}

	private static class WorkbookReader implements AutoCloseable {

		private final ZipFile zipFile;
		private final Document sheet;
		private final List<String> sharedStrings;

		WorkbookReader(Path path) throws Exception {
			this(path, "xl/worksheets/sheet1.xml");
		}

		WorkbookReader(Path path, String sheetEntry) throws Exception {
			zipFile = new ZipFile(path.toFile());
			sheet = document(zipFile.getInputStream(zipFile.getEntry(sheetEntry)));
			sharedStrings = readSharedStrings(zipFile);
		}

		String text(String address) {
			Element cell = cell(address);
			if (cell == null) return "";

			if ("inlineStr".equals(cell.getAttribute("t"))) return descendantText(cell, "t");

			String value = descendantText(cell, "v");
			if ("s".equals(cell.getAttribute("t")) && !value.isBlank()) return sharedStrings.get(Integer.parseInt(value));

			return value;
		}

		BigDecimal number(String address) {
			return new BigDecimal(descendantText(cell(address), "v"));
		}

		String formula(String address) {
			Element cell = cell(address);
			NodeList formulas = cell.getElementsByTagNameNS("*", "f");
			return formulas.getLength() == 0 ? null : formulas.item(0).getTextContent();
		}

		private Element cell(String address) {
			NodeList cells = sheet.getElementsByTagNameNS("*", "c");
			for (int index = 0; index < cells.getLength(); index++) {
				Element cell = (Element) cells.item(index);
				if (address.equals(cell.getAttribute("r"))) return cell;
			}
			return null;
		}

		@Override
		public void close() throws Exception {
			zipFile.close();
		}

		private static List<String> readSharedStrings(ZipFile zipFile) throws Exception {
			ZipEntry entry = zipFile.getEntry("xl/sharedStrings.xml");
			if (entry == null) return List.of();

			Document document = document(zipFile.getInputStream(entry));
			NodeList items = document.getElementsByTagNameNS("*", "si");
			List<String> values = new ArrayList<>();
			for (int index = 0; index < items.getLength(); index++) {
				values.add(items.item(index).getTextContent());
			}
			return List.copyOf(values);
		}

		private static Document document(InputStream input) throws Exception {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			return factory.newDocumentBuilder().parse(input);
		}

		private static String descendantText(Element element, String localName) {
			NodeList descendants = element.getElementsByTagNameNS("*", localName);
			return descendants.getLength() == 0 ? "" : descendants.item(0).getTextContent();
		}
	}
}
