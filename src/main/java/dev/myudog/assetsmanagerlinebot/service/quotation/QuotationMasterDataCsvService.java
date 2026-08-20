package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.QuotationAdminRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 將報價主檔匯出成 Excel 可開啟但不冒充 XLSX 的安全 UTF-8 CSV。
 */
@Service
public class QuotationMasterDataCsvService {

	private static final String HEADER = String.join(",", List.of(
		"報價格式代碼",
		"報價格式",
		"品項代碼",
		"品項",
		"AI別名",
		"規格/說明",
		"單位",
		"單價",
		"備註",
		"顯示順序",
		"計價模式",
		"顯示於客戶",
		"格式品項啟用",
		"品項主檔啟用"
	));

	private static final int MAXIMUM_ROWS = 5000;
	private static final int MAXIMUM_CONTENT_BYTES = 4 * 1024 * 1024;
	private static final Set<String> CALCULATION_MODES = Set.of("DIRECT", "DERIVED", "MANUAL");
	private static final Set<String> CATALOG_FREE_SCHEMES = Set.of("MARINE", "BLANK", "SALES");

	private final QuotationAdminRepository repository;

	// 方法：建立報價主檔 CSV 匯出服務。
	public QuotationMasterDataCsvService(QuotationAdminRepository repository) {
		this.repository = repository;
	}

	// 方法：輸出含 UTF-8 BOM 的 CSV，讓 Windows Excel 正確辨識中文。
	public byte[] export() {
		StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
		for (QuotationAdminRepository.MasterDataExportRow row : repository.findMasterDataExportRows()) {
			csv.append(toCsvRow(row)).append("\r\n");
		}

		// 平台 API：依 UTF-8 產生下載內容，避免系統預設編碼造成中文亂碼。
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	// 方法：以匯出的同一份欄位格式整批覆蓋主檔；未出現在檔案中的資料一律停用而非刪除。
	@Transactional
	public ImportResult importCsv(byte[] content) {
		List<List<String>> rows = readRows(content);
		if (rows.isEmpty()) throw invalid("主檔 CSV 沒有任何內容");

		List<String> header = rows.getFirst();
		List<String> expectedHeader = List.of(HEADER.split(","));
		if (!header.equals(expectedHeader)) {
			throw invalid("主檔 CSV 標題必須與匯出格式相同：" + HEADER);
		}

		List<ParsedRow> parsedRows = new ArrayList<>();
		Set<String> seenItemCodes = new LinkedHashSet<>();
		Set<String> seenSchemeItemKeys = new LinkedHashSet<>();
		for (int index = 1; index < rows.size(); index++) {
			ParsedRow row = parseRow(rows.get(index), index + 1);
			if (row.schemeCode() == null) {
				if (!seenItemCodes.add(row.itemCode())) {
					throw invalid("第 " + (index + 1) + " 列品項代碼重複：" + row.itemCode());
				}
			}
			else if (!seenSchemeItemKeys.add(row.schemeCode() + "/" + row.itemCode())) {
				throw invalid(
					"第 " + (index + 1) + " 列格式品項重複：" + row.schemeCode() + "／" + row.itemCode()
				);
			}
			parsedRows.add(row);
		}

		// 外部 API：先整批停用，再依檔案內容重新建立，達成「匯出→編輯→上傳覆蓋」的完整取代。
		repository.deactivateAllMasterData();
		int createdItems = 0;
		int updatedItems = 0;
		int schemeItems = 0;
		for (ParsedRow row : parsedRows) {
			Optional<QuotationAdminRepository.Item> existing = repository.findItemByCode(row.itemCode());
			QuotationAdminRepository.Item item;
			if (existing.isPresent()) {
				item = repository.updateItem(
					existing.get().id(),
					row.itemCode(),
					row.itemName(),
					row.aliases(),
					row.itemActive()
				);
				updatedItems++;
			}
			else {
				item = repository.createItem(row.itemCode(), row.itemName(), row.aliases(), row.itemActive());
				createdItems++;
			}

			if (row.schemeCode() == null) continue;

			long schemeId = repository.findSchemeId(row.schemeCode())
				.orElseThrow(() -> invalid("不存在或未啟用的報價格式：" + row.schemeCode()));
			repository.upsertSchemeItem(
				schemeId,
				item.id(),
				row.specification(),
				row.unit(),
				row.unitPrice(),
				row.remark(),
				row.displayOrder(),
				row.calculationMode(),
				row.customerVisible(),
				row.mappingActive()
			);
			schemeItems++;
		}
		return new ImportResult(createdItems, updatedItems, schemeItems);
	}

	// 方法：解析一列主檔資料，任何欄位不合法都立即中止整份匯入。
	private ParsedRow parseRow(List<String> cells, int lineNumber) {
		if (cells.size() != 14) throw invalid("第 " + lineNumber + " 列欄位數必須為 14");

		String schemeCode = trimmed(cells.get(0)).toUpperCase(Locale.ROOT);
		String itemCode = trimmed(cells.get(2)).toUpperCase(Locale.ROOT);
		String itemName = trimmed(cells.get(3));
		if (itemCode.isEmpty()) throw invalid("第 " + lineNumber + " 列缺少品項代碼");

		if (!itemCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
			throw invalid("第 " + lineNumber + " 列品項代碼格式錯誤：" + itemCode);
		}

		if (itemName.isEmpty()) throw invalid("第 " + lineNumber + " 列缺少品項名稱");

		List<String> aliases = aliases(cells.get(4));
		boolean itemActive = requiredBoolean(cells.get(13), lineNumber, "品項主檔啟用");
		if (schemeCode.isEmpty()) {
			return new ParsedRow(
				null, itemCode, itemName, aliases, null, null, null, null, 0, "DIRECT", false, false, itemActive
			);
		}

		if (CATALOG_FREE_SCHEMES.contains(schemeCode)) {
			throw invalid(
				"第 " + lineNumber + " 列的 " + schemeCode + " 格式不使用固定品項，請改由使用者於報價時說明品項"
			);
		}

		String unit = trimmed(cells.get(6));
		if (unit.isEmpty()) throw invalid("第 " + lineNumber + " 列缺少單位");

		String calculationMode = trimmed(cells.get(10)).toUpperCase(Locale.ROOT);
		if (calculationMode.isEmpty()) calculationMode = "DIRECT";

		if (!CALCULATION_MODES.contains(calculationMode)) {
			throw invalid("第 " + lineNumber + " 列計價模式不是允許值：" + calculationMode);
		}

		return new ParsedRow(
			schemeCode,
			itemCode,
			itemName,
			aliases,
			emptyToNull(cells.get(5)),
			unit,
			requiredPrice(cells.get(7), lineNumber),
			emptyToNull(cells.get(8)),
			requiredOrder(cells.get(9), lineNumber),
			calculationMode,
			requiredBoolean(cells.get(11), lineNumber, "顯示於客戶"),
			requiredBoolean(cells.get(12), lineNumber, "格式品項啟用"),
			itemActive
		);
	}

	// 方法：以 RFC 4180 規則解析含引號、逗號與換行的 CSV 內容。
	private List<List<String>> readRows(byte[] content) {
		if (content == null || content.length == 0) throw invalid("主檔 CSV 不可留空");

		if (content.length > MAXIMUM_CONTENT_BYTES) throw invalid("主檔 CSV 超過 4MB");

		// 平台 API：以 UTF-8 解析並移除 Excel 產生的 BOM。
		String text = new String(content, StandardCharsets.UTF_8);
		if (text.startsWith("\uFEFF")) text = text.substring(1);

		List<List<String>> rows = new ArrayList<>();
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (quoted) {
				if (character != '\"') {
					cell.append(character);
					continue;
				}

				if (index + 1 < text.length() && text.charAt(index + 1) == '\"') {
					cell.append('\"');
					index++;
					continue;
				}

				quoted = false;
				continue;
			}

			if (character == '\"' && cell.isEmpty()) {
				quoted = true;
				continue;
			}

			if (character == ',') {
				cells.add(cell.toString());
				cell.setLength(0);
				continue;
			}

			if (character == '\r') continue;

			if (character == '\n') {
				cells.add(cell.toString());
				cell.setLength(0);
				rows.add(List.copyOf(cells));
				cells.clear();
				if (rows.size() > MAXIMUM_ROWS) throw invalid("主檔 CSV 超過 " + MAXIMUM_ROWS + " 列");

				continue;
			}
			cell.append(character);
		}
		if (quoted) throw invalid("主檔 CSV 有未結束的雙引號");

		if (!cell.isEmpty() || !cells.isEmpty()) {
			cells.add(cell.toString());
			rows.add(List.copyOf(cells));
		}
		return List.copyOf(rows);
	}

	// 方法：把中文別名欄位還原成 AI 目錄使用的別名清單。
	private List<String> aliases(String cell) {
		String value = trimmed(cell);
		if (value.isEmpty()) return List.of();

		List<String> aliases = new ArrayList<>();
		for (String alias : value.split("、")) {
			String normalized = alias.trim();
			if (!normalized.isEmpty() && !aliases.contains(normalized)) aliases.add(normalized);
		}
		return List.copyOf(aliases);
	}

	// 方法：讀取匯出時寫入的中文布林狀態。
	private boolean requiredBoolean(String cell, int lineNumber, String label) {
		String value = trimmed(cell);
		if ("是".equals(value)) return true;

		if ("否".equals(value)) return false;

		throw invalid("第 " + lineNumber + " 列的「" + label + "」必須是「是」或「否」");
	}

	// 方法：讀取單價並保留十進位精度。
	private BigDecimal requiredPrice(String cell, int lineNumber) {
		String value = trimmed(cell);
		if (value.isEmpty()) throw invalid("第 " + lineNumber + " 列缺少單價");

		try {
			BigDecimal price = new BigDecimal(value);
			if (price.signum() < 0) throw invalid("第 " + lineNumber + " 列單價不可為負數");

			return price;
		}
		catch (NumberFormatException exception) {
			throw invalid("第 " + lineNumber + " 列單價不是數字：" + value);
		}
	}

	// 方法：讀取顯示順序並確保為非負整數。
	private int requiredOrder(String cell, int lineNumber) {
		String value = trimmed(cell);
		if (value.isEmpty()) return 0;

		try {
			int order = Integer.parseInt(value);
			if (order < 0) throw invalid("第 " + lineNumber + " 列顯示順序不可為負數");

			return order;
		}
		catch (NumberFormatException exception) {
			throw invalid("第 " + lineNumber + " 列顯示順序不是整數：" + value);
		}
	}

	// 方法：去除欄位前後空白並保留 null 安全。
	private String trimmed(String cell) {
		return cell == null ? "" : cell.trim();
	}

	// 方法：把空欄位視為未提供。
	private String emptyToNull(String cell) {
		String value = trimmed(cell);
		return value.isEmpty() ? null : value;
	}

	// 方法：建立與管理 API 一致的匯入錯誤。
	private QuotationAdminException invalid(String message) {
		return new QuotationAdminException("VALIDATION_ERROR", message);
	}

	public record ImportResult(int createdItems, int updatedItems, int schemeItems) {}

	private record ParsedRow(
		String schemeCode,
		String itemCode,
		String itemName,
		List<String> aliases,
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		int displayOrder,
		String calculationMode,
		boolean customerVisible,
		boolean mappingActive,
		boolean itemActive
	) {}

	// 方法：將一筆主檔資料依固定欄位順序編碼成 CSV。
	private String toCsvRow(QuotationAdminRepository.MasterDataExportRow row) {
		List<String> cells = new ArrayList<>();
		cells.add(textCell(row.schemeCode()));
		cells.add(textCell(row.schemeName()));
		cells.add(textCell(row.itemCode()));
		cells.add(textCell(row.itemName()));
		cells.add(textCell(String.join("、", row.aliases())));
		cells.add(textCell(row.specification()));
		cells.add(textCell(row.unit()));
		cells.add(plainCell(row.unitPrice()));
		cells.add(textCell(row.remark()));
		cells.add(plainCell(row.displayOrder()));
		cells.add(textCell(row.calculationMode()));
		cells.add(booleanCell(row.isCustomerVisible()));
		cells.add(booleanCell(row.isMappingActive()));
		cells.add(booleanCell(row.isItemActive()));
		return String.join(",", cells);
	}

	// 方法：編碼文字欄位並防止 Excel 將主檔文字解讀為公式。
	private String textCell(String value) {
		if (value == null) return "";

		String safeValue = startsWithSpreadsheetFormula(value) ? "'" + value : value;
		return quoteCsv(safeValue);
	}

	// 方法：保留受資料庫型別約束的數字原值，不套用文字公式防護。
	private String plainCell(Object value) {
		return value == null ? "" : quoteCsv(value.toString());
	}

	// 方法：將可空布林值轉成清楚的中文狀態。
	private String booleanCell(Boolean value) {
		return value == null ? "" : value ? "是" : "否";
	}

	// 方法：辨識 Excel 可能執行的公式前綴，包含前置空白後的危險符號。
	private boolean startsWithSpreadsheetFormula(String value) {
		String normalized = value.stripLeading();
		if (normalized.isEmpty()) return false;

		char first = normalized.charAt(0);
		return first == '=' || first == '+' || first == '-' || first == '@';
	}

	// 方法：依 RFC 4180 規則處理逗號、雙引號與換行。
	private String quoteCsv(String value) {
		if (!value.contains(",") && !value.contains("\"") && !value.contains("\r") && !value.contains("\n")) return value;

		return "\"" + value.replace("\"", "\"\"") + "\"";
	}
}
