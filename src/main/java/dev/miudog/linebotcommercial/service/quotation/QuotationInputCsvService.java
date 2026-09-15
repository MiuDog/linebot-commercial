package dev.miudog.linebotcommercial.service.quotation;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 以檔案中的明確值建立報價 patch，不呼叫模型，也不繞過主檔與確認流程。 */
public final class QuotationInputCsvService {

	public static final int MAXIMUM_BYTES = 1024 * 1024;
	public static final String PREFIX = "#報價 CSV\n";
	public static final String HEADER = "schemeCode,companyName,workName,salesRepresentative,contactName,phone,"
		+ "itemCode,itemName,specification,unit,unitPrice,quantity,remark";
	private final QuotationRequestValidationService validation;
	private final ObjectMapper mapper;

	// 方法：沿用既有業務驗證器，讓 CSV 與自然語言共用價格及資料規則。
	public QuotationInputCsvService(QuotationRequestValidationService validation, ObjectMapper mapper) {
		this.validation = validation;
		this.mapper = mapper;
	}

	// 方法：將嚴格 UTF-8 CSV 的每列轉成可合併且可追溯的草稿 patch。
	public QuotationAiParsingService.ParseResult parse(String text) {
		List<List<String>> rows = rows(text);
		if (rows.size() < 2 || !String.join(",", rows.getFirst()).equals(HEADER)) {
			throw invalid(1, "標題必須與報價輸入 CSV 範例相同，且至少有一筆品項");
		}
		String scheme = rows.get(1).getFirst().trim();
		if (!Set.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES").contains(scheme)) {
			throw invalid(2, "schemeCode 不正確");
		}
		boolean dynamic = Set.of("MARINE", "BLANK", "SALES").contains(scheme);
		ObjectNode root = mapper.createObjectNode();
		root.put("schemaVersion", "2.0").put("schemeCode", scheme).put("schemeConfidence", 1);
		ObjectNode header = root.putObject("headerPatch");
		var standardItems = root.putArray("standardItemIntents");
		var customItems = root.putArray("customItems");
		List<String> names = List.of(HEADER.split(","));
		for (int index = 1; index < rows.size(); index++) {
			List<String> row = rows.get(index);
			int line = index + 1;
			if (row.size() != names.size()) throw invalid(line, "欄位數量必須為 " + names.size());

			if (!row.getFirst().trim().equals(scheme)) throw invalid(line, "同一檔案不可混用報價格式");

			for (int column = 1; column <= 5; column++) {
				String value = row.get(column).trim();
				if (value.isEmpty()) continue;

				String key = names.get(column);
				if (header.has(key) && !header.path(key).path("value").asString().equals(value)) {
					throw invalid(line, key + " 與前列衝突");
				}
				header.set(key, evidence(value, line, key));
			}
			String code = row.get(6).trim();
			BigDecimal quantity = decimal(row.get(11), line, "quantity", false);
			if (!code.isEmpty()) {
				if (dynamic) throw invalid(line, "此格式不可指定固定 itemCode");

				for (int column : List.of(7, 8, 9, 10, 12)) {
					if (!row.get(column).isBlank()) throw invalid(line, names.get(column) + " 應留空，由主檔提供");
				}
				standardItems.addObject().put("itemCode", code).putNull("matchedName").put("quantity", quantity)
					.put("sourceText", "CSV 第 " + line + " 列：" + code + " " + quantity).put("confidence", 1);
			}
			else {
				ObjectNode item = customItems.addObject();
				item.put("clientItemId", "csv_" + index).put("kind", dynamic ? "DYNAMIC" : "TEMPORARY");
				for (int column : List.of(7, 8, 9, 12)) {
					String key = names.get(column);
					String value = row.get(column).trim();
					if (value.isEmpty() && column != 12) throw invalid(line, key + " 不可留空");

					if (value.isEmpty()) item.putNull(key);
					else item.set(key, evidence(value, line, key));
				}
				item.set("quantity", evidence(row.get(11).trim(), line, "quantity").put("value", quantity));
				item.set("unitPrice", evidence(row.get(10).trim(), line, "unitPrice")
					.put("value", decimal(row.get(10), line, "unitPrice", true)));
			}
		}
		for (String name : List.of("removedItemCodes", "imageAssessments", "missingBaseFields", "missingItemFields", "warnings")) {
			root.putArray(name);
		}
		root.putNull("selectedImageMessageId").put("imageDeclined", false);
		root.put("nextAction", Set.of("MARINE", "BLANK").contains(scheme) ? "REQUEST_IMAGE_DECISION" : "SHOW_PREVIEW");
		return new QuotationAiParsingService.ParseResult(validation.validate(root, scheme), mapper.writeValueAsString(root));
	}

	// 方法：保留檔案列與欄位作為來源，數值由使用者檔案提供而不是模型推測。
	private ObjectNode evidence(String value, int line, String field) {
		return mapper.createObjectNode().put("value", value)
			.put("sourceText", "CSV 第 " + line + " 列 " + field + "：" + value).put("confidence", 1);
	}

	// 方法：拒絕非有限、負值及超出業務上限的數量／價格。
	private BigDecimal decimal(String value, int line, String field, boolean allowZero) {
		if (!value.trim().matches("[0-9]{1,10}(\\.[0-9]{1,6})?")) {
			throw invalid(line, field + " 必須為一般十進位數字，最多 6 位小數，不支援科學記號");
		}

		try {
			BigDecimal number = new BigDecimal(value.trim());
			if (number.signum() < 0 || (!allowZero && number.signum() == 0)
				|| number.compareTo(new BigDecimal("1000000000")) > 0) throw invalid(line, field + " 超出允許範圍");

			return number;
		}
		catch (NumberFormatException exception) {
			throw invalid(line, field + " 必須為數字");
		}
	}

	// 方法：解析 CSV 引號、逗號與儲存格換行，同時限制大小與最多 200 筆品項。
	private List<List<String>> rows(String text) {
		if (text == null || text.length() > MAXIMUM_BYTES) throw invalid(1, "檔案超過上限或沒有內容");

		String input = text.startsWith("\uFEFF") ? text.substring(1) : text;
		List<List<String>> rows = new ArrayList<>();
		List<String> cells = new ArrayList<>();
		StringBuilder value = new StringBuilder();
		boolean quoted = false;
		boolean closed = false;
		for (int index = 0; index < input.length(); index++) {
			char character = input.charAt(index);
			if (quoted) {
				if (character == '"' && index + 1 < input.length() && input.charAt(index + 1) == '"') {
					value.append('"');
					index++;
				}
				else if (character == '"') {
					quoted = false;
					closed = true;
				}
				else value.append(character);
				continue;
			}
			if (character == '"' && value.isEmpty() && !closed) {
				quoted = true;
				continue;
			}

			if (character == ',' || character == '\n' || character == '\r') {
				cells.add(value.toString());
				value.setLength(0);
				closed = false;
				if (character == ',') continue;

				if (character == '\r' && index + 1 < input.length() && input.charAt(index + 1) == '\n') index++;

				rows.add(List.copyOf(cells));
				cells.clear();
				if (rows.size() > 201) throw invalid(rows.size(), "最多 200 筆品項");
			}
			else {
				if (closed || character == '"') throw invalid(rows.size() + 1, "引號位置不正確");

				value.append(character);
			}
		}
		if (quoted) throw invalid(rows.size() + 1, "引號未結束");

		if (!cells.isEmpty() || !value.isEmpty() || closed) {
			cells.add(value.toString());
			rows.add(List.copyOf(cells));
		}
		if (rows.size() > 201) throw invalid(rows.size(), "最多 200 筆品項");

		return rows;
	}

	// 方法：回覆可定位的錯誤，不把整列客戶資料寫入錯誤訊息。
	private QuotationLineWorkflowException invalid(int line, String message) {
		return new QuotationLineWorkflowException("INVALID_QUOTATION_CSV", "CSV 第 " + line + " 列：" + message);
	}
}
