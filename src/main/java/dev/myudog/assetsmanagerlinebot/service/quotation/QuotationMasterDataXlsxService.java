package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.QuotationAdminRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ?????????????????? XLSX ????
 */
@Service
public class QuotationMasterDataXlsxService {

	private static final int EXCEL_MAXIMUM_ROWS = 1_048_576;
	private static final List<String> HEADERS = List.of(
		"\u5831\u50f9\u683c\u5f0f\u4ee3\u78bc", "\u5831\u50f9\u683c\u5f0f", "\u54c1\u9805\u4ee3\u78bc",
		"\u54c1\u9805", "AI \u5225\u540d", "\u898f\u683c\uff0f\u8aaa\u660e", "\u55ae\u4f4d",
		"\u55ae\u50f9", "\u5099\u8a3b", "\u986f\u793a\u9806\u5e8f", "\u8a08\u50f9\u6a21\u5f0f",
		"\u986f\u793a\u65bc\u5ba2\u6236", "\u683c\u5f0f\u54c1\u9805\u555f\u7528",
		"\u54c1\u9805\u4e3b\u6a94\u555f\u7528"
	);

	private final QuotationAdminRepository repository;

	// 方法：處理報價主檔。
	public QuotationMasterDataXlsxService(QuotationAdminRepository repository) {
		this.repository = repository;
	}

	// 方法：處理報價主檔。
	public byte[] export() {
		// 方法：處理報價主檔。
		List<QuotationAdminRepository.MasterDataExportRow> rows = repository.findMasterDataExportRows();
		if (rows.size() + 1 > EXCEL_MAXIMUM_ROWS) throw new IllegalStateException("Excel row limit exceeded");

		try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
			writeEntry(zip, "[Content_Types].xml", contentTypesXml());
			writeEntry(zip, "_rels/.rels", packageRelationshipsXml());
			writeEntry(zip, "xl/workbook.xml", workbookXml());
			writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelationshipsXml());
			writeEntry(zip, "xl/styles.xml", stylesXml());
			writeEntry(zip, "xl/worksheets/sheet1.xml", worksheetXml(rows));
			zip.finish();
			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to create the Excel master-data workbook", exception);
		}
	}

	// 方法：處理報價主檔。
	private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
		// 方法：處理報價主檔。
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	// 方法：處理報價主檔。
	private String worksheetXml(List<QuotationAdminRepository.MasterDataExportRow> rows) {
		int lastRow = rows.size() + 1;
		StringBuilder sheetData = new StringBuilder();
		sheetData.append("<row r=\"1\" ht=\"30\" customHeight=\"1\">");
		for (int column = 0; column < HEADERS.size(); column++) {
			sheetData.append(textCell(cellAddress(column, 1), HEADERS.get(column), 1));
		}
		sheetData.append("</row>");

		for (int index = 0; index < rows.size(); index++) {
			appendDataRow(sheetData, rows.get(index), index + 2);
		}

		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
				<dimension ref="A1:N%d"/>
				<sheetViews><sheetView showGridLines="0" workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
				<sheetFormatPr defaultRowHeight="20"/>
				<cols>
					<col min="1" max="1" width="18" customWidth="1"/><col min="2" max="2" width="14" customWidth="1"/>
					<col min="3" max="3" width="24" customWidth="1"/><col min="4" max="4" width="20" customWidth="1"/>
					<col min="5" max="5" width="24" customWidth="1"/><col min="6" max="6" width="22" customWidth="1"/>
					<col min="7" max="8" width="14" customWidth="1"/><col min="9" max="9" width="26" customWidth="1"/>
					<col min="10" max="14" width="15" customWidth="1"/>
				</cols>
				<sheetData>%s</sheetData>
				<autoFilter ref="A1:N%d"/>
				<pageMargins left="0.25" right="0.25" top="0.5" bottom="0.5" header="0.2" footer="0.2"/>
			</worksheet>
			""".formatted(lastRow, sheetData, lastRow);
	}

	// 方法：處理報價主檔。
	private void appendDataRow(
		StringBuilder sheetData,
		QuotationAdminRepository.MasterDataExportRow row,
		int rowNumber
	) {
		boolean alternate = rowNumber % 2 == 0;
		int textStyle = alternate ? 5 : 2;
		int numberStyle = alternate ? 6 : 3;
		int centerStyle = alternate ? 7 : 4;
		sheetData.append("<row r=\"").append(rowNumber).append("\">")
			.append(textCell("A" + rowNumber, row.schemeCode(), textStyle))
			.append(textCell("B" + rowNumber, row.schemeName(), textStyle))
			.append(textCell("C" + rowNumber, row.itemCode(), textStyle))
			.append(textCell("D" + rowNumber, row.itemName(), textStyle))
			.append(textCell("E" + rowNumber, String.join("\u3001", row.aliases()), textStyle))
			.append(textCell("F" + rowNumber, row.specification(), textStyle))
			.append(textCell("G" + rowNumber, row.unit(), centerStyle))
			.append(numberCell("H" + rowNumber, row.unitPrice(), numberStyle))
			.append(textCell("I" + rowNumber, row.remark(), textStyle))
			.append(numberCell("J" + rowNumber, row.displayOrder(), centerStyle))
			.append(textCell("K" + rowNumber, row.calculationMode(), centerStyle))
			.append(textCell("L" + rowNumber, booleanText(row.isCustomerVisible()), centerStyle))
			.append(textCell("M" + rowNumber, booleanText(row.isMappingActive()), centerStyle))
			.append(textCell("N" + rowNumber, booleanText(row.isItemActive()), centerStyle))
			.append("</row>");
	}

	// 方法：處理報價主檔。
	private String textCell(String address, String value, int style) {
		String safeValue = value == null ? "" : sanitizeXml(value);
		return "<c r=\"" + address + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
			+ safeValue + "</t></is></c>";
	}

	// 方法：處理報價主檔。
	private String numberCell(String address, Number value, int style) {
		if (value == null) return "<c r=\"" + address + "\" s=\"" + style + "\"/>";

		String number = value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
		return "<c r=\"" + address + "\" s=\"" + style + "\"><v>" + number + "</v></c>";
	}

	// 方法：處理報價主檔。
	private String booleanText(Boolean value) {
		return value == null ? "" : value ? "\u662f" : "\u5426";
	}

	// 方法：處理報價主檔。
	private String cellAddress(int zeroBasedColumn, int row) {
		return Character.toString('A' + zeroBasedColumn) + row;
	}

	// 方法：處理報價主檔。
	private String sanitizeXml(String value) {
		StringBuilder safe = new StringBuilder();
		value.codePoints().filter(this::isXmlCharacter).forEach(safe::appendCodePoint);
		return safe.toString()
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&apos;");
	}

	// 方法：處理報價主檔。
	private boolean isXmlCharacter(int codePoint) {
		return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
			|| codePoint >= 0x20 && codePoint <= 0xD7FF
			|| codePoint >= 0xE000 && codePoint <= 0xFFFD
			|| codePoint >= 0x10000 && codePoint <= 0x10FFFF;
	}

	// 方法：處理報價主檔。
	private String contentTypesXml() {
		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
				<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
				<Default Extension="xml" ContentType="application/xml"/>
				<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
				<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
				<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
			</Types>
			""";
	}

	// 方法：處理報價主檔。
	private String packageRelationshipsXml() {
		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
				<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
			</Relationships>
			""";
	}

	// 方法：處理報價主檔。
	private String workbookXml() {
		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
				<bookViews><workbookView/></bookViews>
				<sheets><sheet name="\u5831\u50f9\u4e3b\u6a94" sheetId="1" r:id="rId1"/></sheets>
				<calcPr calcId="0" fullCalcOnLoad="1"/>
			</workbook>
			""";
	}

	// 方法：處理報價主檔。
	private String workbookRelationshipsXml() {
		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
				<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
				<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
			</Relationships>
			""";
	}

	// 方法：處理報價主檔。
	private String stylesXml() {
		return """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
				<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
				<fonts count="2"><font><sz val="11"/><name val="Microsoft JhengHei"/><family val="2"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Microsoft JhengHei"/><family val="2"/></font></fonts>
				<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF176B52"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF1F6F3"/><bgColor indexed="64"/></patternFill></fill></fills>
				<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD7D8CF"/></left><right style="thin"><color rgb="FFD7D8CF"/></right><top style="thin"><color rgb="FFD7D8CF"/></top><bottom style="thin"><color rgb="FFD7D8CF"/></bottom><diagonal/></border></borders>
				<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
				<cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf></cellXfs>
				<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
			</styleSheet>
			""";
	}
}
