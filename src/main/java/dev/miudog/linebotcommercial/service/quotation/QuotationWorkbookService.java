package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 將已驗證的報價資料寫入原始 XLSX 範本，保留範本內的樣式、圖片、蓋章與固定聯絡資料。
 */
@Service
public class QuotationWorkbookService {

	private static final String SPREADSHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
	private static final String RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/relationships";
	private static final String CONTENT_TYPE_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/content-types";
	private static final String XML_NAMESPACE = XMLConstants.XML_NS_URI;
	private static final String TEMPLATE_DEFINITIONS = "classpath:quotation/template-definitions.json";
	private static final String SHEET_ENTRY = "xl/worksheets/sheet1.xml";
	private static final String SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml";
	private static final String WORKBOOK_ENTRY = "xl/workbook.xml";
	private static final String WORKBOOK_RELATIONSHIPS_ENTRY = "xl/_rels/workbook.xml.rels";
	private static final String CONTENT_TYPES_ENTRY = "[Content_Types].xml";
	private static final String CALCULATION_CHAIN_ENTRY = "xl/calcChain.xml";
	private static final String DRAWING_ENTRY = "xl/drawings/drawing1.xml";
	private static final String DRAWING_RELATIONSHIPS_ENTRY = "xl/drawings/_rels/drawing1.xml.rels";
	private static final String SHEET_RELATIONSHIPS_ENTRY = "xl/worksheets/_rels/sheet1.xml.rels";
	private static final String SELECTED_IMAGE_ENTRY = "xl/media/quotation-selected.png";
	private static final String DRAWING_NAMESPACE = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
	private static final String DRAWING_MAIN_NAMESPACE = "http://schemas.openxmlformats.org/drawingml/2006/main";
	private static final String OFFICE_RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
	private static final long EMU_PER_PIXEL = 9525L;
	private static final int APPROXIMATE_ROW_HEIGHT_PIXELS = 22;
	private static final int MAXIMUM_IMAGE_WIDTH_PIXELS = 850;
	private static final int MAXIMUM_HEADER_LENGTH = 500;
	private static final int MAXIMUM_OUTPUT_COLLISIONS = 1000;

	private final ObjectMapper objectMapper;
	private final QuotationOutputDirectoryService outputDirectoryService;
	private final ResourceLoader resourceLoader;
	private final Map<String, TemplateDefinition> templates;

	// 方法：載入五種範本定義並建立報價 Excel 產出服務。
	public QuotationWorkbookService(
		ObjectMapper objectMapper,
		QuotationOutputDirectoryService outputDirectoryService,
		ResourceLoader resourceLoader
	) {
		this.objectMapper = objectMapper;
		this.outputDirectoryService = outputDirectoryService;
		this.resourceLoader = resourceLoader;
		this.templates = loadTemplateDefinitions();
	}

	//#region 產出流程

	// 方法：回報是否已設定報價單輸出根目錄。
	public boolean isConfigured() {
		return outputDirectoryService.isConfigured();
	}

	// 方法：供同套件範本驗證測試建立 XLSX；正式產出只能使用 generateConfirmed。
	GenerationResult generate(
		Header header,
		QuotationRequestValidationService.ValidatedQuotationRequest quotation
	) {
		if (quotation == null) throw validation("報價資料不可留空");

		TemplateDefinition template = templates.get(normalizeSchemeCode(quotation.schemeCode()));
		if (template == null) throw validation("找不到報價格式範本：" + quotation.schemeCode());

		Header safeHeader = normalizeHeader(header);
		List<QuotationRequestValidationService.ResolvedItem> visibleItems = quotation.items().stream()
			.filter(QuotationRequestValidationService.ResolvedItem::isCustomerVisible)
			.sorted(Comparator.comparingInt(QuotationRequestValidationService.ResolvedItem::displayOrder))
			.toList();
		validateItems(template, visibleItems);

		BigDecimal subtotal = visibleItems.stream()
			.map(QuotationRequestValidationService.ResolvedItem::lineAmount)
			.filter(amount -> amount != null)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal tax = subtotal.multiply(template.taxRate());
		BigDecimal total = subtotal.add(tax);

		Path temporaryFile = null;
		try {
			Path directory = outputDirectoryService.createDirectory(quotation.quotationName());

			// 檔案系統：先在目標資料夾建立暫存檔，成功完成後才以不可覆寫方式改名。
			temporaryFile = Files.createTempFile(directory, ".quotation-", ".xlsx");
			try (InputStream templateInput = openTemplate(template)) {
				writeWorkbook(
					templateInput,
					temporaryFile,
					template,
					safeHeader,
					visibleItems,
					subtotal,
					tax,
					total
				);
			}
			Path output = moveToUniqueOutput(temporaryFile, directory, template.schemeCode());
			temporaryFile = null;
			return new GenerationResult(output, output.getFileName().toString(), template.schemeCode(), subtotal, tax, total);
		}
		catch (QuotationAdminException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new QuotationAdminException("WORKBOOK_GENERATION_ERROR", "建立 Excel 報價單失敗", exception);
		}
		finally {
			if (temporaryFile != null) {
				try {
					// 檔案系統：只清理由本次產出建立且尚未發布的暫存檔。
					Files.deleteIfExists(temporaryFile);
				}
				catch (IOException ignored) {
					// 暫存檔清理失敗不覆蓋原始產出例外。
				}
			}
		}
	}

	// 方法：依正式確認結果將可信計價列寫入已配置的日期流水號檔名。
	public GenerationResult generateConfirmed(
		Header header,
		QuotationConfirmationResult confirmation,
		QuotationCalculationResult calculation
	) {
		return generateConfirmed(header, confirmation, calculation, null);
	}

	// 方法：產出正式報價並在有足夠品項下方空間時嵌入唯一選中圖片。
	public GenerationResult generateConfirmed(
		Header header,
		QuotationConfirmationResult confirmation,
		QuotationCalculationResult calculation,
		Path selectedImagePath
	) {
		if (confirmation == null) throw validation("正式確認結果不可留空");

		if (calculation == null) throw validation("程式計價結果不可留空");

		TemplateDefinition template = templates.get(normalizeSchemeCode(calculation.schemeCode()));
		if (template == null) throw validation("找不到報價格式範本：" + calculation.schemeCode());

		Header safeHeader = normalizeHeader(header);
		List<QuotationRequestValidationService.ResolvedItem> items = confirmedItems(calculation);
		validateConfirmedItems(items);
		PaginationPlan pagination = paginationPlan(template, items, selectedImagePath);

		Path temporaryFile = null;
		try {
			Path output = outputDirectoryService.resolveFormalFile(
				confirmation.folderName(),
				confirmation.fileBaseName(),
				".xlsx"
			);
			temporaryFile = Files.createTempFile(output.getParent(), ".quotation-", ".xlsx");
			try (InputStream templateInput = openTemplate(template)) {
				writePaginatedWorkbook(
					templateInput,
					temporaryFile,
					template,
					safeHeader,
					pagination,
					calculation.subtotal(),
					calculation.tax(),
					calculation.total()
				);
			}
			publishFormalOutput(temporaryFile, output);
			temporaryFile = null;
			return new GenerationResult(
				output,
				output.getFileName().toString(),
				template.schemeCode(),
				calculation.subtotal(),
				calculation.tax(),
				calculation.total()
			);
		}
		catch (QuotationAdminException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new QuotationAdminException("WORKBOOK_GENERATION_ERROR", "建立正式 Excel 報價單失敗", exception);
		}
		finally {
			if (temporaryFile != null) {
				try {
					// 檔案系統：正式檔發布失敗時只移除本次建立的暫存檔。
					Files.deleteIfExists(temporaryFile);
				}
				catch (IOException ignored) {
					// 暫存檔清理失敗不覆蓋原始產出例外。
				}
			}
		}
	}

	// 方法：將確定性計價列轉成現有 OOXML 寫入模型，船用只建立一筆對客總價列。
	private List<QuotationRequestValidationService.ResolvedItem> confirmedItems(
		QuotationCalculationResult calculation
	) {
		if (calculation.customerPresentation()
			== QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY) {
			return List.of(
				new QuotationRequestValidationService.ResolvedItem(
					null,
					"系統架搭設/拆除",
					"",
					BigDecimal.ONE,
					"式",
					calculation.subtotal(),
					calculation.subtotal(),
					"",
					1,
					"SUMMARY",
					true,
					"",
					BigDecimal.ONE
				)
			);
		}

		return calculation.customerLines().stream()
			.sorted(Comparator.comparingInt(QuotationCalculationResult.QuotationLine::displayOrder))
			.map(this::resolvedItem)
			.toList();
	}

	// 方法：將單一計價快照列轉成不再重新解析主檔的報表列。
	private QuotationRequestValidationService.ResolvedItem resolvedItem(
		QuotationCalculationResult.QuotationLine line
	) {
		return new QuotationRequestValidationService.ResolvedItem(
			line.itemCode(),
			line.itemName(),
			line.specification(),
			line.quantity(),
			line.unit(),
			line.unitPrice(),
			line.lineAmount(),
			line.remark(),
			line.displayOrder(),
			line.calculationMode(),
			line.customerVisible(),
			"",
			BigDecimal.ONE
		);
	}

	// 方法：逐一複製 OOXML 封裝項目，只修改工作表、共用字串與重新計算設定。
	private void writeWorkbook(
		InputStream templateInput,
		Path output,
		TemplateDefinition template,
		Header header,
		List<QuotationRequestValidationService.ResolvedItem> items,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) throws Exception {
		writeWorkbook(templateInput, output, template, header, items, subtotal, tax, total, null);
	}

	// 方法：複製 OOXML 封裝並選擇性加入一張不影響既有圖片的工程圖片。
	private void writeWorkbook(
		InputStream templateInput,
		Path output,
		TemplateDefinition template,
		Header header,
		List<QuotationRequestValidationService.ResolvedItem> items,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total,
		SelectedImage selectedImage
	) throws Exception {
		Set<String> entryNames = new HashSet<>();

		// 檔案系統：串流複製 XLSX ZIP 封裝，避免解壓到可被路徑穿越影響的目錄。
		try (
			ZipInputStream input = new ZipInputStream(templateInput);
			ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(output))
		) {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				String entryName = entry.getName();
				if (!entryNames.add(entryName)) throw validation("Excel 範本包含重複封裝項目：" + entryName);

				byte[] source = input.readAllBytes();
				if (CALCULATION_CHAIN_ENTRY.equals(entryName)) continue;

				byte[] content = switch (entryName) {
					case SHEET_ENTRY -> updateSheet(source, template, header, items, subtotal, tax, total);
					case SHARED_STRINGS_ENTRY -> replaceSharedStringPlaceholders(source, header);
					case WORKBOOK_ENTRY -> enableAutomaticCalculation(source);
					case WORKBOOK_RELATIONSHIPS_ENTRY -> removeCalculationChainRelationship(source);
					case CONTENT_TYPES_ENTRY -> removeCalculationChainContentType(source);
					case DRAWING_ENTRY -> selectedImage == null
						? source
						: appendSelectedImageDrawing(source, selectedImage);
					case DRAWING_RELATIONSHIPS_ENTRY -> selectedImage == null
						? source
						: appendSelectedImageRelationship(source);
					default -> source;
				};
				ZipEntry outputEntry = new ZipEntry(entryName);
				outputEntry.setTime(entry.getTime());
				zipOutput.putNextEntry(outputEntry);
				zipOutput.write(content);
				zipOutput.closeEntry();
			}
			if (selectedImage != null) {
				if (!entryNames.add(SELECTED_IMAGE_ENTRY)) throw validation("Excel 範本已含保留的工程圖片檔名");

				ZipEntry selectedImageEntry = new ZipEntry(SELECTED_IMAGE_ENTRY);
				zipOutput.putNextEntry(selectedImageEntry);
				zipOutput.write(selectedImage.pngBytes());
				zipOutput.closeEntry();
			}
		}
	}

	// 方法：把正式報價拆成多張保留原列印設定的工作表，且只在最後一頁顯示總額。
	private void writePaginatedWorkbook(
		InputStream templateInput,
		Path output,
		TemplateDefinition template,
		Header header,
		PaginationPlan pagination,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) throws Exception {
		Map<String, PackagePart> sourceParts = readPackage(templateInput);
		byte[] sourceSheet = requiredPart(sourceParts, SHEET_ENTRY);
		byte[] sourceSheetRelationships = requiredPart(sourceParts, SHEET_RELATIONSHIPS_ENTRY);
		byte[] sourceDrawing = requiredPart(sourceParts, DRAWING_ENTRY);
		byte[] sourceDrawingRelationships = requiredPart(sourceParts, DRAWING_RELATIONSHIPS_ENTRY);
		int pageCount = pagination.pages().size();
		Set<String> pageSpecificEntries = Set.of(
			SHEET_ENTRY,
			SHEET_RELATIONSHIPS_ENTRY,
			DRAWING_ENTRY,
			DRAWING_RELATIONSHIPS_ENTRY,
			CALCULATION_CHAIN_ENTRY
		);

		// 檔案系統：以新 OOXML 封裝發布所有頁面，不解壓到使用者可控制的路徑。
		try (ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(output))) {
			for (Map.Entry<String, PackagePart> entry : sourceParts.entrySet()) {
				if (pageSpecificEntries.contains(entry.getKey())) continue;

				byte[] content = switch (entry.getKey()) {
					case SHARED_STRINGS_ENTRY -> replaceSharedStringPlaceholders(entry.getValue().content(), header);
					case WORKBOOK_ENTRY -> configureWorkbook(entry.getValue().content(), template, pageCount);
					case WORKBOOK_RELATIONSHIPS_ENTRY -> configureWorkbookRelationships(
						entry.getValue().content(),
						pageCount
					);
					case CONTENT_TYPES_ENTRY -> configureContentTypes(entry.getValue().content(), pageCount);
					default -> entry.getValue().content();
				};
				writePackagePart(zipOutput, entry.getKey(), content, entry.getValue().time());
			}

			for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
				boolean finalPage = pageIndex == pageCount - 1;
				byte[] sheet = updateSheet(
					sourceSheet,
					template,
					header,
					pagination.pages().get(pageIndex),
					subtotal,
					tax,
					total
				);
				if (!finalPage) sheet = clearPageTotals(sheet, template);

				if (finalPage && pageCount > 1) {
					sheet = writePaginatedGrandTotals(
						sheet,
						template,
						pageCount,
						subtotal,
						tax,
						total
					);
				}

				int pageNumber = pageIndex + 1;
				writePackagePart(zipOutput, sheetEntry(pageNumber), sheet, -1L);
				writePackagePart(
					zipOutput,
					sheetRelationshipsEntry(pageNumber),
					configureSheetRelationships(sourceSheetRelationships, pageNumber),
					-1L
				);

				boolean embedsSelectedImage = pageIndex == pagination.selectedImagePageIndex();
				byte[] drawing = embedsSelectedImage
					? appendSelectedImageDrawing(sourceDrawing, pagination.selectedImage())
					: sourceDrawing;
				drawing = uniquifyDrawing(drawing, pageNumber);
				byte[] drawingRelationships = embedsSelectedImage
					? appendSelectedImageRelationship(sourceDrawingRelationships)
					: sourceDrawingRelationships;
				writePackagePart(zipOutput, drawingEntry(pageNumber), drawing, -1L);
				writePackagePart(
					zipOutput,
					drawingRelationshipsEntry(pageNumber),
					drawingRelationships,
					-1L
				);
			}

			if (pagination.selectedImage() != null) {
				writePackagePart(
					zipOutput,
					SELECTED_IMAGE_ENTRY,
					pagination.selectedImage().pngBytes(),
					-1L
				);
			}
		}
	}

	// 方法：把範本 ZIP 封裝讀成有順序的記憶體部分，供多頁複製安全使用。
	private Map<String, PackagePart> readPackage(InputStream templateInput) throws IOException {
		Map<String, PackagePart> parts = new LinkedHashMap<>();
		try (ZipInputStream input = new ZipInputStream(templateInput)) {
			ZipEntry entry;
			while ((entry = input.getNextEntry()) != null) {
				if (parts.put(entry.getName(), new PackagePart(input.readAllBytes(), entry.getTime())) != null) {
					throw validation("Excel 範本包含重複封裝項目：" + entry.getName());
				}
			}
		}
		return parts;
	}

	// 方法：取得多頁產出必要的範本封裝部分。
	private byte[] requiredPart(Map<String, PackagePart> parts, String entryName) {
		PackagePart part = parts.get(entryName);
		if (part == null) throw validation("Excel 範本缺少必要封裝項目：" + entryName);

		return part.content();
	}

	// 方法：寫入單一 ZIP 部分並在可用時保留原範本時間戳。
	private void writePackagePart(
		ZipOutputStream output,
		String entryName,
		byte[] content,
		long time
	) throws IOException {
		ZipEntry entry = new ZipEntry(entryName);
		if (time >= 0L) entry.setTime(time);

		output.putNextEntry(entry);
		output.write(content);
		output.closeEntry();
	}

	//#endregion

	//#region 工作表填值

	// 方法：將表頭、可見品項、複價與合計寫入既有儲存格，且不變更任何儲存格樣式。
	private byte[] updateSheet(
		byte[] source,
		TemplateDefinition template,
		Header header,
		List<QuotationRequestValidationService.ResolvedItem> items,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) throws Exception {
		Document document = parseXml(source);
		writeText(document, template.header().customerName(), header.customerName());
		writeText(document, template.header().phoneFax(), header.phoneFax());
		writeText(document, template.header().customerEmail(), header.customerEmail());
		writeText(document, template.header().contact(), header.contact());
		writeText(document, template.header().projectSite(), header.projectSite());
		writeText(
			document,
			template.header().quoteNumber(),
			applyQuoteNumberPrefix(template.quoteNumberPrefix(), header.quoteNumber())
		);
		writeText(document, template.header().quoteDate(), header.quoteDate());
		writeText(document, template.header().salesRepresentative(), header.salesRepresentative());

		for (int row = template.detail().firstRow(); row <= template.detail().lastRow(); row++) {
			int itemIndex = row - template.detail().firstRow();
			if (itemIndex < items.size()) {
				writeItem(document, template, row, itemIndex + 1, items.get(itemIndex));
			}
			else {
				clearDetailRow(document, template, row, itemIndex == items.size());
			}
		}

		writeTotals(document, template, subtotal, tax, total);
		return serializeXml(document);
	}

	// 方法：寫入單一報價品項；直接計價保留公式，其餘模式只揭露既有最終金額。
	private void writeItem(
		Document document,
		TemplateDefinition template,
		int row,
		int lineNumber,
		QuotationRequestValidationService.ResolvedItem item
	) {
		DetailColumns columns = template.detail().columns();
		writeNumber(document, columns.lineNumber() + row, BigDecimal.valueOf(lineNumber));
		writeText(document, columns.itemName() + row, item.itemName());
		writeText(document, columns.specification() + row, item.specification());
		writeOptionalNumber(document, columns.quantity() + row, item.quantity());
		writeText(document, columns.unit() + row, item.unit());
		writeNumber(document, columns.unitPrice() + row, item.unitPrice());
		if (item.lineAmount() == null) {
			clearCell(document, columns.lineAmount() + row);
		}
		else if (template.summaryOnly() || !"DIRECT".equals(item.calculationMode())) {
			writeNumber(document, columns.lineAmount() + row, item.lineAmount());
		}
		else {
			String formula = "IFERROR(" + columns.quantity() + row + "*" + columns.unitPrice() + row + ",0)";
			writeFormula(document, columns.lineAmount() + row, formula, item.lineAmount());
		}
		writeText(document, columns.remark() + row, item.remark());
	}

	// 方法：清空未使用的明細列，並只在第一個空白列保留「以下空白」提示。
	private void clearDetailRow(Document document, TemplateDefinition template, int row, boolean firstUnusedRow) {
		DetailColumns columns = template.detail().columns();
		clearCell(document, columns.lineNumber() + row);
		writeText(document, columns.itemName() + row, firstUnusedRow ? "以下空白" : "");
		clearCell(document, columns.specification() + row);
		clearCell(document, columns.quantity() + row);
		clearCell(document, columns.unit() + row);
		clearCell(document, columns.unitPrice() + row);
		writeNumber(document, columns.lineAmount() + row, BigDecimal.ZERO);
		clearCell(document, columns.remark() + row);
	}

	// 方法：依範本顯示模式寫入未稅、稅額與含稅總額。
	private void writeTotals(
		Document document,
		TemplateDefinition template,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) {
		Totals totals = template.totals();
		String detailAmountRange = template.detail().columns().lineAmount()
			+ template.detail().firstRow()
			+ ":"
			+ template.detail().columns().lineAmount()
			+ template.detail().lastRow();
		if (template.summaryOnly()) {
			writeNumber(document, totals.subtotal(), subtotal);
			writeNumber(document, totals.preTax(), subtotal);
			writeNumber(document, totals.tax(), tax);
			writeNumber(document, totals.total(), total);
			return;
		}

		writeFormula(document, totals.subtotal(), "SUM(" + detailAmountRange + ")", subtotal);
		writeFormula(document, totals.preTax(), "SUM(" + detailAmountRange + ")", subtotal);
		writeFormula(
			document,
			totals.tax(),
			totals.preTax() + "*" + template.taxRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%",
			tax
		);
		writeFormula(document, totals.total(), totals.preTax() + "+" + totals.tax(), total);
	}

	//#endregion

	//#region OOXML 操作

	// 方法：將指定儲存格寫成安全的 inline string，避免使用者文字被 Excel 當成公式。
	private void writeText(Document document, String address, String value) {
		Element cell = requiredCell(document, address);
		clearCellContent(cell);
		cell.setAttribute("t", "inlineStr");

		Element inlineString = document.createElementNS(SPREADSHEET_NAMESPACE, "is");
		Element text = document.createElementNS(SPREADSHEET_NAMESPACE, "t");
		text.setAttributeNS(XML_NAMESPACE, "xml:space", "preserve");
		text.setTextContent(value == null ? "" : value);
		inlineString.appendChild(text);
		cell.appendChild(inlineString);
	}

	// 方法：將指定儲存格寫成數值並保留既有樣式索引。
	private void writeNumber(Document document, String address, BigDecimal value) {
		Element cell = requiredCell(document, address);
		clearCellContent(cell);
		cell.removeAttribute("t");
		Element number = document.createElementNS(SPREADSHEET_NAMESPACE, "v");
		number.setTextContent(decimal(value));
		cell.appendChild(number);
	}

	// 方法：數量或複價為 null 時保留儲存格樣式並輸出真正空白。
	private void writeOptionalNumber(Document document, String address, BigDecimal value) {
		if (value == null) {
			clearCell(document, address);
			return;
		}

		writeNumber(document, address, value);
	}

	// 方法：寫入可稽核公式及同步快取結果，讓未重新計算的檢視器也能顯示正確金額。
	private void writeFormula(Document document, String address, String formula, BigDecimal cachedValue) {
		Element cell = requiredCell(document, address);
		clearCellContent(cell);
		cell.removeAttribute("t");

		Element formulaElement = document.createElementNS(SPREADSHEET_NAMESPACE, "f");
		formulaElement.setTextContent(formula);
		cell.appendChild(formulaElement);
		Element valueElement = document.createElementNS(SPREADSHEET_NAMESPACE, "v");
		valueElement.setTextContent(decimal(cachedValue));
		cell.appendChild(valueElement);
	}

	// 方法：清空儲存格內容但保留位置、樣式與其他版面屬性。
	private void clearCell(Document document, String address) {
		Element cell = requiredCell(document, address);
		clearCellContent(cell);
		cell.removeAttribute("t");
	}

	// 方法：移除儲存格既有值、公式或 inline string 子節點。
	private void clearCellContent(Element cell) {
		List<Node> children = new ArrayList<>();
		for (Node child = cell.getFirstChild(); child != null; child = child.getNextSibling()) {
			children.add(child);
		}
		children.forEach(cell::removeChild);
	}

	// 方法：讀取選中原圖並建立等比例 PNG 嵌入副本，原始圖片本體不做任何修改。
	private SelectedImage prepareSelectedImage(
		Path selectedImagePath,
		TemplateDefinition template,
		int itemCount
	) {
		if (!Files.isRegularFile(selectedImagePath)) throw validation("找不到選中的工程圖片");

		int capacity = template.detail().lastRow() - template.detail().firstRow() + 1;
		int unusedRows = capacity - itemCount;
		if (unusedRows < 4) throw validation("品項下方空間不足，工程圖片必須移至新頁");

		try {
			// 圖片 API：讀取原圖尺寸並轉成 Excel 穩定支援的 PNG 嵌入副本。
			BufferedImage image = ImageIO.read(selectedImagePath.toFile());
			if (image == null) throw validation("選中的工程圖片格式無法辨識");

			int maximumHeight = unusedRows * APPROXIMATE_ROW_HEIGHT_PIXELS;
			double scale = Math.min(
				1.0,
				Math.min(
					MAXIMUM_IMAGE_WIDTH_PIXELS / (double) image.getWidth(),
					maximumHeight / (double) image.getHeight()
				)
			);
			int width = Math.max(1, (int) Math.floor(image.getWidth() * scale));
			int height = Math.max(1, (int) Math.floor(image.getHeight() * scale));
			ByteArrayOutputStream output = new ByteArrayOutputStream();

			// 圖片 API：只為 XLSX 封裝建立 PNG 位元組，完整原圖仍由資產庫保存。
			if (!ImageIO.write(image, "png", output)) throw validation("無法建立工程圖片嵌入副本");

			return new SelectedImage(
				output.toByteArray(),
				template.detail().firstRow() - 1 + itemCount,
				width,
				height
			);
		}
		catch (IOException exception) {
			throw new QuotationAdminException("IMAGE_EMBED_ERROR", "讀取選中的工程圖片失敗", exception);
		}
	}

	// 方法：依每份範本容量分割動態品項，圖片空間不足時追加純圖片末頁。
	private PaginationPlan paginationPlan(
		TemplateDefinition template,
		List<QuotationRequestValidationService.ResolvedItem> items,
		Path selectedImagePath
	) {
		int capacity = template.detail().lastRow() - template.detail().firstRow() + 1;
		List<List<QuotationRequestValidationService.ResolvedItem>> pages = new ArrayList<>();
		for (int start = 0; start < items.size(); start += capacity) {
			pages.add(List.copyOf(items.subList(start, Math.min(start + capacity, items.size()))));
		}
		if (pages.isEmpty()) pages.add(List.of());

		if (selectedImagePath == null) return new PaginationPlan(List.copyOf(pages), -1, null);

		int selectedPage = pages.size() - 1;
		int itemCountOnSelectedPage = pages.get(selectedPage).size();
		if (capacity - itemCountOnSelectedPage < 4) {
			pages.add(List.of());
			selectedPage = pages.size() - 1;
			itemCountOnSelectedPage = 0;
		}
		SelectedImage image = prepareSelectedImage(selectedImagePath, template, itemCountOnSelectedPage);
		return new PaginationPlan(List.copyOf(pages), selectedPage, image);
	}

	// 方法：確認正式計價列至少一筆且數量、複價與單價的 null 語意一致。
	private void validateConfirmedItems(
		List<QuotationRequestValidationService.ResolvedItem> items
	) {
		if (items.isEmpty()) throw validation("報價單至少需要一筆顯示品項");

		for (QuotationRequestValidationService.ResolvedItem item : items) {
			if (item.unitPrice() == null) throw validation("品項「" + item.itemName() + "」缺少單價");

			if (item.quantity() == null && item.lineAmount() != null) {
				throw validation("品項「" + item.itemName() + "」數量空白時複價也必須空白");
			}
			if (item.quantity() != null && item.lineAmount() == null) {
				throw validation("品項「" + item.itemName() + "」尚未完成計價");
			}
		}
	}

	// 方法：清空非末頁合計儲存格內容，保留底部版面、聯絡資訊與蓋章。
	private byte[] clearPageTotals(byte[] source, TemplateDefinition template) throws Exception {
		Document document = parseXml(source);
		clearCell(document, template.totals().subtotal());
		clearCell(document, template.totals().preTax());
		clearCell(document, template.totals().tax());
		clearCell(document, template.totals().total());
		return serializeXml(document);
	}

	// 方法：讓最後一頁合計公式匯總所有分頁，避免 Excel 重算後只剩末頁金額。
	private byte[] writePaginatedGrandTotals(
		byte[] source,
		TemplateDefinition template,
		int pageCount,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) throws Exception {
		Document document = parseXml(source);
		Totals totals = template.totals();
		String subtotalFormula = paginatedSubtotalFormula(template, pageCount);
		writeFormula(document, totals.subtotal(), subtotalFormula, subtotal);
		writeFormula(document, totals.preTax(), subtotalFormula, subtotal);
		writeFormula(
			document,
			totals.tax(),
			totals.preTax() + "*" + template.taxRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%",
			tax
		);
		writeFormula(document, totals.total(), totals.preTax() + "+" + totals.tax(), total);
		return serializeXml(document);
	}

	// 方法：產生包含每個分頁明細金額範圍的 Excel SUM 公式。
	private String paginatedSubtotalFormula(TemplateDefinition template, int pageCount) {
		StringBuilder formula = new StringBuilder("SUM(");
		String column = template.detail().columns().lineAmount();
		String range = column + template.detail().firstRow() + ":" + column + template.detail().lastRow();
		for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
			if (pageNumber > 1) formula.append(',');

			String sheetName = pageNumber == 1
				? template.sheetName()
				: pageSheetName(template.sheetName(), pageNumber);
			formula.append('\'')
				.append(sheetName.replace("'", "''"))
				.append("'!")
				.append(range);
		}
		return formula.append(')').toString();
	}

	// 方法：為多頁活頁簿加入工作表名稱、關係識別與各頁列印範圍。
	private byte[] configureWorkbook(
		byte[] source,
		TemplateDefinition template,
		int pageCount
	) throws Exception {
		Document document = parseXml(enableAutomaticCalculation(source));
		Element sheets = (Element) document.getElementsByTagNameNS(SPREADSHEET_NAMESPACE, "sheets").item(0);
		Element definedNames = (Element) document.getElementsByTagNameNS(
			SPREADSHEET_NAMESPACE,
			"definedNames"
		).item(0);
		String printRange = printRange(document);
		for (int pageNumber = 2; pageNumber <= pageCount; pageNumber++) {
			String sheetName = pageSheetName(template.sheetName(), pageNumber);
			Element sheet = document.createElementNS(SPREADSHEET_NAMESPACE, "sheet");
			sheet.setAttribute("name", sheetName);
			sheet.setAttribute("sheetId", Integer.toString(100 + pageNumber));
			sheet.setAttributeNS(OFFICE_RELATIONSHIP_NAMESPACE, "r:id", "rIdQuotationPage" + pageNumber);
			sheets.appendChild(sheet);

			Element printArea = document.createElementNS(SPREADSHEET_NAMESPACE, "definedName");
			printArea.setAttribute("name", "_xlnm.Print_Area");
			printArea.setAttribute("localSheetId", Integer.toString(pageNumber - 1));
			printArea.setTextContent("'" + sheetName.replace("'", "''") + "'!" + printRange);
			definedNames.appendChild(printArea);
		}
		return serializeXml(document);
	}

	// 方法：從原始列印範圍取得儲存格部分，供新頁套用相同版面。
	private String printRange(Document document) {
		NodeList names = document.getElementsByTagNameNS(SPREADSHEET_NAMESPACE, "definedName");
		for (int index = 0; index < names.getLength(); index++) {
			Element name = (Element) names.item(index);
			if (!"_xlnm.Print_Area".equals(name.getAttribute("name"))) continue;

			String value = name.getTextContent();
			int separator = value.indexOf('!');
			if (separator >= 0 && separator + 1 < value.length()) return value.substring(separator + 1);
		}
		return "$A$1:$H$60";
	}

	// 方法：移除舊計算鏈並為第 2 頁起加入新的工作表關係。
	private byte[] configureWorkbookRelationships(byte[] source, int pageCount) throws Exception {
		Document document = parseXml(removeCalculationChainRelationship(source));
		for (int pageNumber = 2; pageNumber <= pageCount; pageNumber++) {
			Element relationship = document.createElementNS(RELATIONSHIP_NAMESPACE, "Relationship");
			relationship.setAttribute("Id", "rIdQuotationPage" + pageNumber);
			relationship.setAttribute(
				"Type",
				"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
			);
			relationship.setAttribute("Target", "worksheets/sheet" + pageNumber + ".xml");
			document.getDocumentElement().appendChild(relationship);
		}
		return serializeXml(document);
	}

	// 方法：為新工作表與 drawing 部分加入 OOXML 內容型態宣告。
	private byte[] configureContentTypes(byte[] source, int pageCount) throws Exception {
		Document document = parseXml(removeCalculationChainContentType(source));
		for (int pageNumber = 2; pageNumber <= pageCount; pageNumber++) {
			appendContentType(
				document,
				"/xl/worksheets/sheet" + pageNumber + ".xml",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
			);
			appendContentType(
				document,
				"/xl/drawings/drawing" + pageNumber + ".xml",
				"application/vnd.openxmlformats-officedocument.drawing+xml"
			);
		}
		return serializeXml(document);
	}

	// 方法：附加一筆 OOXML Override 內容型態。
	private void appendContentType(Document document, String partName, String contentType) {
		Element override = document.createElementNS(CONTENT_TYPE_NAMESPACE, "Override");
		override.setAttribute("PartName", partName);
		override.setAttribute("ContentType", contentType);
		document.getDocumentElement().appendChild(override);
	}

	// 方法：把複製工作表的 drawing 關係改指向同頁 drawing 編號。
	private byte[] configureSheetRelationships(byte[] source, int pageNumber) throws Exception {
		if (pageNumber == 1) return source;

		Document document = parseXml(source);
		NodeList relationships = document.getElementsByTagNameNS(RELATIONSHIP_NAMESPACE, "Relationship");
		for (int index = 0; index < relationships.getLength(); index++) {
			Element relationship = (Element) relationships.item(index);
			if (relationship.getAttribute("Type").endsWith("/drawing")) {
				relationship.setAttribute("Target", "../drawings/drawing" + pageNumber + ".xml");
			}
		}
		return serializeXml(document);
	}

	// 方法：建立不超過 Excel 31 字元的分頁工作表名稱。
	private String pageSheetName(String baseName, int pageNumber) {
		String suffix = " (" + pageNumber + ")";
		int available = Math.max(1, 31 - suffix.length());
		String prefix = baseName.codePoints()
			.limit(available)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
		return prefix + suffix;
	}

	// 方法：取得指定頁面的工作表封裝路徑。
	private String sheetEntry(int pageNumber) {
		return "xl/worksheets/sheet" + pageNumber + ".xml";
	}

	// 方法：取得指定頁面的工作表關係封裝路徑。
	private String sheetRelationshipsEntry(int pageNumber) {
		return "xl/worksheets/_rels/sheet" + pageNumber + ".xml.rels";
	}

	// 方法：取得指定頁面的 drawing 封裝路徑。
	private String drawingEntry(int pageNumber) {
		return "xl/drawings/drawing" + pageNumber + ".xml";
	}

	// 方法：取得指定頁面的 drawing 關係封裝路徑。
	private String drawingRelationshipsEntry(int pageNumber) {
		return "xl/drawings/_rels/drawing" + pageNumber + ".xml.rels";
	}

	// 方法：在既有 drawing1 保留所有圖片與蓋章，並附加一個等比例工程圖片錨點。
	private byte[] appendSelectedImageDrawing(byte[] source, SelectedImage image) throws Exception {
		Document document = parseXml(source);
		Element root = document.getDocumentElement();
		Element anchor = document.createElementNS(DRAWING_NAMESPACE, "xdr:oneCellAnchor");
		Element from = child(document, anchor, DRAWING_NAMESPACE, "xdr:from");
		textChild(document, from, DRAWING_NAMESPACE, "xdr:col", "0");
		textChild(document, from, DRAWING_NAMESPACE, "xdr:colOff", "0");
		textChild(document, from, DRAWING_NAMESPACE, "xdr:row", Integer.toString(image.startRow()));
		textChild(document, from, DRAWING_NAMESPACE, "xdr:rowOff", "0");

		Element extent = child(document, anchor, DRAWING_NAMESPACE, "xdr:ext");
		extent.setAttribute("cx", Long.toString(image.widthPixels() * EMU_PER_PIXEL));
		extent.setAttribute("cy", Long.toString(image.heightPixels() * EMU_PER_PIXEL));

		Element picture = child(document, anchor, DRAWING_NAMESPACE, "xdr:pic");
		Element nonVisual = child(document, picture, DRAWING_NAMESPACE, "xdr:nvPicPr");
		Element properties = child(document, nonVisual, DRAWING_NAMESPACE, "xdr:cNvPr");
		properties.setAttribute("id", Integer.toString(nextDrawingId(document)));
		properties.setAttribute("name", "報價工程圖片");
		Element nonVisualPicture = child(document, nonVisual, DRAWING_NAMESPACE, "xdr:cNvPicPr");
		Element locks = child(document, nonVisualPicture, DRAWING_MAIN_NAMESPACE, "a:picLocks");
		locks.setAttribute("noChangeAspect", "1");

		Element blipFill = child(document, picture, DRAWING_NAMESPACE, "xdr:blipFill");
		Element blip = child(document, blipFill, DRAWING_MAIN_NAMESPACE, "a:blip");
		blip.setAttributeNS(OFFICE_RELATIONSHIP_NAMESPACE, "r:embed", "rIdQuotationSelected");
		Element stretch = child(document, blipFill, DRAWING_MAIN_NAMESPACE, "a:stretch");
		child(document, stretch, DRAWING_MAIN_NAMESPACE, "a:fillRect");

		Element shape = child(document, picture, DRAWING_NAMESPACE, "xdr:spPr");
		Element transform = child(document, shape, DRAWING_MAIN_NAMESPACE, "a:xfrm");
		Element offset = child(document, transform, DRAWING_MAIN_NAMESPACE, "a:off");
		offset.setAttribute("x", "0");
		offset.setAttribute("y", "0");
		Element pictureExtent = child(document, transform, DRAWING_MAIN_NAMESPACE, "a:ext");
		pictureExtent.setAttribute("cx", Long.toString(image.widthPixels() * EMU_PER_PIXEL));
		pictureExtent.setAttribute("cy", Long.toString(image.heightPixels() * EMU_PER_PIXEL));
		Element geometry = child(document, shape, DRAWING_MAIN_NAMESPACE, "a:prstGeom");
		geometry.setAttribute("prst", "rect");
		child(document, geometry, DRAWING_MAIN_NAMESPACE, "a:avLst");
		child(document, anchor, DRAWING_NAMESPACE, "xdr:clientData");
		root.appendChild(anchor);
		return serializeXml(document);
	}

	// 方法：讓複製頁的 drawing 物件識別碼在整份活頁簿中保持唯一。
	private byte[] uniquifyDrawing(byte[] source, int pageNumber) throws Exception {
		if (pageNumber == 1) return source;

		Document document = parseXml(source);
		NodeList properties = document.getElementsByTagNameNS(DRAWING_NAMESPACE, "cNvPr");
		for (int index = 0; index < properties.getLength(); index++) {
			Element property = (Element) properties.item(index);
			try {
				int original = Integer.parseInt(property.getAttribute("id"));
				property.setAttribute("id", Integer.toString(pageNumber * 1000 + original));
			}
			catch (NumberFormatException ignored) {
				// 非數字識別碼保留原值，避免改動未知範本物件。
			}
			property.setAttribute("name", property.getAttribute("name") + "-頁" + pageNumber);
		}
		return serializeXml(document);
	}

	// 方法：讓既有 drawing1 以獨立關係指向新加入的工程圖片媒體檔。
	private byte[] appendSelectedImageRelationship(byte[] source) throws Exception {
		Document document = parseXml(source);
		NodeList relationships = document.getElementsByTagNameNS(RELATIONSHIP_NAMESPACE, "Relationship");
		for (int index = 0; index < relationships.getLength(); index++) {
			Element relationship = (Element) relationships.item(index);
			if ("rIdQuotationSelected".equals(relationship.getAttribute("Id"))) {
				throw validation("Excel 範本已含保留的工程圖片關係");
			}
		}

		Element relationship = document.createElementNS(RELATIONSHIP_NAMESPACE, "Relationship");
		relationship.setAttribute("Id", "rIdQuotationSelected");
		relationship.setAttribute(
			"Type",
			"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
		);
		relationship.setAttribute("Target", "../media/quotation-selected.png");
		document.getDocumentElement().appendChild(relationship);
		return serializeXml(document);
	}

	// 方法：建立帶指定命名空間的 XML 子元素並附加至父節點。
	private Element child(
		Document document,
		Element parent,
		String namespace,
		String qualifiedName
	) {
		Element child = document.createElementNS(namespace, qualifiedName);
		parent.appendChild(child);
		return child;
	}

	// 方法：建立帶文字值的 XML 子元素。
	private void textChild(
		Document document,
		Element parent,
		String namespace,
		String qualifiedName,
		String value
	) {
		Element child = child(document, parent, namespace, qualifiedName);
		child.setTextContent(value);
	}

	// 方法：掃描既有圖片與形狀識別碼，為工程圖片配置不衝突的新值。
	private int nextDrawingId(Document document) {
		NodeList properties = document.getElementsByTagNameNS(DRAWING_NAMESPACE, "cNvPr");
		int maximum = 0;
		for (int index = 0; index < properties.getLength(); index++) {
			Element property = (Element) properties.item(index);
			try {
				maximum = Math.max(maximum, Integer.parseInt(property.getAttribute("id")));
			}
			catch (NumberFormatException ignored) {
				// 非數字識別碼不參與配置，既有物件仍完整保留。
			}
		}
		return maximum + 1;
	}

	// 方法：尋找範本中必須預先存在的儲存格，避免靜默產出錯位報表。
	private Element requiredCell(Document document, String address) {
		NodeList cells = document.getElementsByTagNameNS(SPREADSHEET_NAMESPACE, "c");
		for (int index = 0; index < cells.getLength(); index++) {
			Element cell = (Element) cells.item(index);
			if (address.equals(cell.getAttribute("r"))) return cell;
		}
		throw validation("Excel 範本缺少必要儲存格：" + address);
	}

	// 方法：替換條款內嵌的有效日期變數，其餘明細變數由工作表儲存格直接覆寫。
	private byte[] replaceSharedStringPlaceholders(byte[] source, Header header) throws Exception {
		Document document = parseXml(source);
		NodeList textNodes = document.getElementsByTagNameNS(SPREADSHEET_NAMESPACE, "t");
		for (int index = 0; index < textNodes.getLength(); index++) {
			Node text = textNodes.item(index);
			text.setTextContent(text.getTextContent().replace("{{validUntil}}", header.validUntil()));
		}
		return serializeXml(document);
	}

	// 方法：移除舊計算鏈關係，避免變更公式後 Excel 嘗試使用失效的計算鏈。
	private byte[] removeCalculationChainRelationship(byte[] source) throws Exception {
		Document document = parseXml(source);
		NodeList relationships = document.getElementsByTagNameNS(RELATIONSHIP_NAMESPACE, "Relationship");
		removeElementsWithAttributeSuffix(relationships, "Type", "/calcChain");
		return serializeXml(document);
	}

	// 方法：移除舊計算鏈的內容類型宣告。
	private byte[] removeCalculationChainContentType(byte[] source) throws Exception {
		Document document = parseXml(source);
		NodeList overrides = document.getElementsByTagNameNS(CONTENT_TYPE_NAMESPACE, "Override");
		removeElementsWithAttributeSuffix(overrides, "PartName", "/xl/calcChain.xml");
		return serializeXml(document);
	}

	// 方法：要求 Excel 開啟檔案時重新計算公式，但仍保留本次寫入的快取金額。
	private byte[] enableAutomaticCalculation(byte[] source) throws Exception {
		Document document = parseXml(source);
		NodeList calculationProperties = document.getElementsByTagNameNS(SPREADSHEET_NAMESPACE, "calcPr");
		Element calculation = calculationProperties.getLength() > 0
			? (Element) calculationProperties.item(0)
			: (Element) document.getDocumentElement().appendChild(
				document.createElementNS(SPREADSHEET_NAMESPACE, "calcPr")
			);
		calculation.setAttribute("calcMode", "auto");
		calculation.setAttribute("fullCalcOnLoad", "1");
		calculation.setAttribute("forceFullCalc", "1");
		return serializeXml(document);
	}

	// 方法：以複製清單移除符合屬性尾碼的元素，避免操作即時 NodeList 時漏項。
	private void removeElementsWithAttributeSuffix(NodeList elements, String attribute, String suffix) {
		List<Element> removals = new ArrayList<>();
		for (int index = 0; index < elements.getLength(); index++) {
			Element element = (Element) elements.item(index);
			if (element.getAttribute(attribute).endsWith(suffix)) removals.add(element);
		}
		removals.forEach(element -> element.getParentNode().removeChild(element));
	}

	// 方法：以停用外部實體與 DTD 的安全設定解析 OOXML 片段。
	private Document parseXml(byte[] source) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		// XML：只解析來自已選定 XLSX 範本的封裝內容，且禁止任何外部資源。
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
	}

	// 方法：以禁止外部樣式表的 Transformer 將 DOM 寫回 UTF-8 OOXML。
	private byte[] serializeXml(Document document) throws Exception {
		TransformerFactory factory = TransformerFactory.newInstance();
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		// XML：保留 XML 宣告並避免為 OOXML 加入非必要縮排空白。
		var transformer = factory.newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		transformer.setOutputProperty(OutputKeys.INDENT, "no");
		transformer.transform(new DOMSource(document), new StreamResult(output));
		return output.toByteArray();
	}

	//#endregion

	//#region 範本與輸出路徑

	// 方法：從 classpath JSON 載入五種範本定位與欄位規則。
	private Map<String, TemplateDefinition> loadTemplateDefinitions() {
		try {
			Resource resource = resourceLoader.getResource(TEMPLATE_DEFINITIONS);

			// 序列化：讀取受版本控制的範本定義，不接受使用者動態指定任意檔案。
			JsonNode root;
			try (InputStream input = resource.getInputStream()) {
				root = objectMapper.readTree(input);
			}
			Map<String, TemplateDefinition> definitions = new LinkedHashMap<>();
			for (JsonNode node : root.path("templates")) {
				TemplateDefinition definition = templateDefinition(node);
				if (definitions.put(definition.schemeCode(), definition) != null) {
					throw validation("範本定義包含重複報價格式：" + definition.schemeCode());
				}
			}
			return Map.copyOf(definitions);
		}
		catch (QuotationAdminException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new QuotationAdminException("TEMPLATE_CONFIGURATION_ERROR", "無法載入 Excel 範本定義", exception);
		}
	}

	// 方法：將單筆 JSON 範本定義轉成明確型別並保留所有儲存格定位。
	private TemplateDefinition templateDefinition(JsonNode node) {
		JsonNode header = node.path("header");
		JsonNode detail = node.path("detail");
		JsonNode columns = detail.path("columns");
		JsonNode totals = node.path("totals");
		return new TemplateDefinition(
			node.path("schemeCode").asString().toUpperCase(Locale.ROOT),
			node.path("workbookPath").asString(),
			node.path("sheetName").asString(),
			node.path("summaryOnly").asBoolean(),
			node.path("taxRate").decimalValue(),
			node.path("quoteNumberPrefix").asString(""),
			new HeaderCells(
				header.path("customerName").asString(),
				header.path("phoneFax").asString(),
				header.path("customerEmail").asString(),
				header.path("contact").asString(),
				header.path("projectSite").asString(),
				header.path("quoteNumber").asString(),
				header.path("quoteDate").asString(),
				header.path("salesRepresentative").asString()
			),
			new Detail(
				detail.path("firstRow").asInt(),
				detail.path("lastRow").asInt(),
				new DetailColumns(
					columns.path("lineNumber").asString(),
					columns.path("itemName").asString(),
					columns.path("specification").asString(),
					columns.path("quantity").asString(),
					columns.path("unit").asString(),
					columns.path("unitPrice").asString(),
					columns.path("lineAmount").asString(),
					columns.path("remark").asString()
				)
			),
			new Totals(
				totals.path("subtotal").asString(),
				totals.path("preTax").asString(),
				totals.path("tax").asString(),
				totals.path("total").asString()
			)
		);
	}

	// 方法：優先使用本機可編輯範本，封裝執行時則改用 Maven 內嵌的同名 classpath 範本。
	private InputStream openTemplate(TemplateDefinition template) throws IOException {
		Path localPath = Path.of(template.workbookPath()).toAbsolutePath().normalize();
		if (Files.isRegularFile(localPath)) {
			// 檔案系統：開啟管理者可直接替換的本機 Excel 範本。
			return Files.newInputStream(localPath);
		}

		String fileName = Path.of(template.workbookPath()).getFileName().toString();
		Resource classpathTemplate = resourceLoader.getResource("classpath:quotation/templates/" + fileName);
		if (!classpathTemplate.exists()) throw validation("找不到 Excel 範本：" + template.workbookPath());

		return classpathTemplate.getInputStream();
	}

	// 方法：以報價名稱與格式建立唯一檔名，不覆寫既有報價單。
	private Path moveToUniqueOutput(Path temporaryFile, Path directory, String schemeCode)
		throws IOException {
		String baseName = directory.getFileName() + "-" + schemeCode;
		for (int collision = 0; collision < MAXIMUM_OUTPUT_COLLISIONS; collision++) {
			String suffix = collision == 0 ? "" : "-" + (collision + 1);
			Path candidate = directory.resolve(baseName + suffix + ".xlsx").normalize();
			if (!candidate.getParent().equals(directory)) throw validation("Excel 輸出檔名超出報價資料夾");

			try {
				// 檔案系統：先原子保留空檔名，避免 Windows 原子移動覆寫同名檔。
				Files.createFile(candidate);
			}
			catch (FileAlreadyExistsException ignored) {
				// 同名檔案已存在時嘗試下一個流水後綴。
				continue;
			}

			try {
				// 檔案系統：只覆寫本次剛保留的空檔，並優先以原子移動發布完整報價檔。
				return Files.move(
					temporaryFile,
					candidate,
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE
				);
			}
			catch (AtomicMoveNotSupportedException exception) {
				try {
					return Files.move(temporaryFile, candidate, StandardCopyOption.REPLACE_EXISTING);
				}
				catch (IOException moveException) {
					// 檔案系統：發布失敗時移除本次建立的空白保留檔。
					Files.deleteIfExists(candidate);
					throw moveException;
				}
			}
			catch (IOException exception) {
				// 檔案系統：發布失敗時移除本次建立的空白保留檔。
				Files.deleteIfExists(candidate);
				throw exception;
			}
		}
		throw validation("同名報價檔案數量過多，無法建立新檔");
	}

	// 方法：以同一正式路徑原子發布報價，重試時不配置新流水號。
	private void publishFormalOutput(Path temporaryFile, Path output) throws IOException {
		try {
			// 檔案系統：優先原子取代同一張正式報價的舊 XLSX。
			Files.move(
				temporaryFile,
				output,
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			);
		}
		catch (AtomicMoveNotSupportedException exception) {
			// 檔案系統：不支援原子移動的磁碟以同路徑取代，仍不配置新流水號。
			Files.move(temporaryFile, output, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// 方法：為銷售報價單補上固定前綴，但不重複加在已含前綴的單號上。
	private String applyQuoteNumberPrefix(String prefix, String quoteNumber) {
		if (prefix == null || prefix.isBlank() || quoteNumber.startsWith(prefix)) return quoteNumber;

		return prefix + quoteNumber;
	}

	//#endregion

	//#region 驗證與正規化

	// 方法：確認報價至少有一筆可信品項且單價完整，並符合範本列數上限。
	private void validateItems(
		TemplateDefinition template,
		List<QuotationRequestValidationService.ResolvedItem> items
	) {
		if (items.isEmpty()) throw validation("報價單至少需要一筆顯示品項");

		int capacity = template.detail().lastRow() - template.detail().firstRow() + 1;
		if (items.size() > capacity) {
			throw validation(template.schemeCode() + " 範本最多只能顯示 " + capacity + " 筆品項");
		}
		for (QuotationRequestValidationService.ResolvedItem item : items) {
			if (item.unitPrice() == null) throw validation("品項「" + item.itemName() + "」缺少單價");

			if (item.quantity() == null && item.lineAmount() != null) {
				throw validation("品項「" + item.itemName() + "」數量空白時複價也必須空白");
			}
			if (item.quantity() != null && item.lineAmount() == null) {
				throw validation("品項「" + item.itemName() + "」尚未完成計價");
			}
		}
	}

	// 方法：限制表頭長度並為未提供日期的報價使用本機日期。
	private Header normalizeHeader(Header header) {
		Header source = header == null ? new Header(null, null, null, null, null, null, null, null, null) : header;
		return new Header(
			normalizedText(source.quoteNumber(), "報價單號", 100),
			source.quoteDate() == null || source.quoteDate().isBlank()
				? LocalDate.now().toString()
				: normalizedText(source.quoteDate(), "報價日期", 30),
			normalizedText(source.customerName(), "客戶名稱", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.phoneFax(), "公司電話／傳真", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.customerEmail(), "公司信箱", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.contact(), "聯絡人及電話", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.projectSite(), "工程地點", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.salesRepresentative(), "業務承辦", MAXIMUM_HEADER_LENGTH),
			normalizedText(source.validUntil(), "報價有效期限", 30)
		);
	}

	// 方法：正規化可選文字並拒絕超出報表容量的輸入。
	private String normalizedText(String value, String label, int maximumLength) {
		if (value == null) return "";

		String normalized = value.trim();
		if (normalized.length() > maximumLength) throw validation(label + "長度不可超過 " + maximumLength);

		return normalized;
	}

	// 方法：將格式代碼正規化為資料庫與範本共同使用的大寫代碼。
	private String normalizeSchemeCode(String schemeCode) {
		if (schemeCode == null || schemeCode.isBlank()) throw validation("報價格式不可留空");

		return schemeCode.trim().toUpperCase(Locale.ROOT);
	}

	// 方法：以不含科學記號的 Excel 相容格式輸出十進位數字。
	private String decimal(BigDecimal value) {
		if (value == null) throw validation("Excel 數值不可留空");

		return value.stripTrailingZeros().toPlainString();
	}

	// 方法：建立統一的報價欄位驗證錯誤。
	private QuotationAdminException validation(String message) {
		return new QuotationAdminException("VALIDATION_ERROR", message);
	}

	//#endregion

	public record Header(
		String quoteNumber,
		String quoteDate,
		String customerName,
		String phoneFax,
		String customerEmail,
		String contact,
		String projectSite,
		String salesRepresentative,
		String validUntil
	) {}

	public record GenerationResult(
		Path path,
		String fileName,
		String schemeCode,
		BigDecimal subtotal,
		BigDecimal tax,
		BigDecimal total
	) {}

	private record TemplateDefinition(
		String schemeCode,
		String workbookPath,
		String sheetName,
		boolean summaryOnly,
		BigDecimal taxRate,
		String quoteNumberPrefix,
		HeaderCells header,
		Detail detail,
		Totals totals
	) {}

	private record HeaderCells(
		String customerName,
		String phoneFax,
		String customerEmail,
		String contact,
		String projectSite,
		String quoteNumber,
		String quoteDate,
		String salesRepresentative
	) {}

	private record Detail(int firstRow, int lastRow, DetailColumns columns) {}

	private record DetailColumns(
		String lineNumber,
		String itemName,
		String specification,
		String quantity,
		String unit,
		String unitPrice,
		String lineAmount,
		String remark
	) {}

	private record Totals(String subtotal, String preTax, String tax, String total) {}

	private record SelectedImage(
		byte[] pngBytes,
		int startRow,
		int widthPixels,
		int heightPixels
	) {}

	private record PaginationPlan(
		List<List<QuotationRequestValidationService.ResolvedItem>> pages,
		int selectedImagePageIndex,
		SelectedImage selectedImage
	) {}

	private record PackagePart(byte[] content, long time) {}
}
