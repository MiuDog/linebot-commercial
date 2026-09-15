package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 驗證 AI／OCR 2.x extraction patch，並以資料庫補齊標準品項預覽資料。
 */
@Service
public class QuotationRequestValidationService {

	private static final String CONTRACT_VERSION = "2.0";
	private static final int MAXIMUM_CUSTOM_ITEMS = 200;
	private static final BigDecimal MINIMUM_CONFIDENCE = new BigDecimal("0.75");
	private static final BigDecimal MAXIMUM_VALUE = new BigDecimal("1000000000");
	private static final Set<String> SCHEME_CODES = Set.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
	private static final Set<String> CATALOG_FREE_SCHEMES = Set.of("MARINE", "BLANK", "SALES");
	private static final Set<String> ROOT_FIELDS = Set.of(
		"schemaVersion",
		"schemeCode",
		"schemeConfidence",
		"headerPatch",
		"standardItemIntents",
		"customItems",
		"removedItemCodes",
		"imageAssessments",
		"selectedImageMessageId",
		"imageDeclined",
		"missingBaseFields",
		"missingItemFields",
		"nextAction",
		"warnings"
	);
	private static final Set<String> HEADER_FIELDS = Set.of(
		"companyName",
		"workName",
		"contactName",
		"phone",
		"fax",
		"email",
		"projectLocation",
		"salesRepresentative",
		"additionalHeader"
	);
	private static final Set<String> STANDARD_ITEM_FIELDS = Set.of(
		"itemCode",
		"quantity",
		"sourceText",
		"confidence"
	);
	private static final Set<String> STANDARD_ITEM_ALLOWED_FIELDS = Set.of(
		"itemCode",
		"matchedName",
		"quantity",
		"sourceText",
		"confidence"
	);
	private static final Set<String> PLACEHOLDER_ITEM_CODES = Set.of(
		"UNKNOWN",
		"UNKNOWN_ITEM",
		"NA",
		"N_A",
		"NONE",
		"NULL",
		"OTHER",
		"OTHERS",
		"MISC",
		"TBD",
		"TODO",
		"ITEM",
		"CUSTOM",
		"TEMPORARY",
		"PLACEHOLDER"
	);
	private static final Set<String> CUSTOM_ITEM_FIELDS = Set.of(
		"clientItemId",
		"kind",
		"itemName",
		"specification",
		"unit",
		"unitPrice",
		"quantity",
		"remark"
	);
	private static final Set<String> EXTRACTED_VALUE_FIELDS = Set.of(
		"value",
		"sourceText",
		"confidence"
	);
	private static final Set<String> IMAGE_FIELDS = Set.of(
		"messageId",
		"qualityScore",
		"viewpointScore",
		"distinctivenessScore",
		"reason"
	);
	private static final Set<String> MISSING_BASE_ENTRY_FIELDS = Set.of(
		"field",
		"reason",
		"sourceText",
		"confidence"
	);
	private static final Set<String> MISSING_ITEM_ENTRY_FIELDS = Set.of(
		"itemRef",
		"fields",
		"reason",
		"sourceText",
		"confidence"
	);
	private static final Set<String> ITEM_FIELD_NAMES = Set.of(
		"itemCode",
		"itemName",
		"specification",
		"unit",
		"unitPrice",
		"quantity",
		"remark"
	);
	private static final Set<String> MISSING_REASONS = Set.of(
		"MISSING",
		"LOW_CONFIDENCE",
		"CONFLICT"
	);
	private static final Set<String> NEXT_ACTIONS = Set.of(
		"REQUEST_BASE_FIELDS",
		"REQUEST_ITEM_FIELDS",
		"REQUEST_IMAGE_DECISION",
		"SHOW_PREVIEW"
	);

	private final QuotationAdminRepository repository;

	// 方法：建立 AI／OCR extraction patch 驗證服務。
	public QuotationRequestValidationService(QuotationAdminRepository repository) {
		this.repository = repository;
	}

	// 方法：驗證 2.x patch、補齊資料庫固定欄位，並回傳不含任何計算結果的完整預覽資料。
	public ValidatedQuotationRequest validate(JsonNode root) {
		return validate(root, null);
	}

	// 方法：以使用者已指定的報價格式為準驗證 2.x patch；AI 不得自行判定或改判格式。
	public ValidatedQuotationRequest validate(JsonNode root, String lockedSchemeCode) {
		requireObject(root, "最外層資料");
		requireExactFields(root, ROOT_FIELDS, "最外層資料");

		String schemaVersion = requiredText(root, "schemaVersion", "schemaVersion", 3);
		if (!CONTRACT_VERSION.equals(schemaVersion)) throw validation("schemaVersion 必須為 " + CONTRACT_VERSION);

		String declaredSchemeCode = optionalUppercaseText(root, "schemeCode", "報價格式", 20);
		BigDecimal schemeConfidence = decimalInRange(
			root,
			"schemeConfidence",
			"報價格式信心",
			BigDecimal.ZERO,
			BigDecimal.ONE
		);
		String schemeCode = resolveSchemeCode(lockedSchemeCode, declaredSchemeCode, schemeConfidence);

		HeaderPatch headerPatch = validateHeaderPatch(root.get("headerPatch"));
		Map<String, QuotationAdminRepository.SchemeItem> catalog = activeCatalog(schemeCode);
		List<ResolvedItem> standardItems = validateStandardItems(
			root.get("standardItemIntents"),
			schemeCode,
			catalog
		);
		List<CustomItem> customItems = validateCustomItems(root.get("customItems"), schemeCode);
		List<String> removedItemCodes = validateRemovedItemCodes(
			root.get("removedItemCodes"),
			schemeCode,
			catalog,
			standardItems
		);

		List<ImageAssessment> imageAssessments = validateImageAssessments(root.get("imageAssessments"));
		String selectedImageMessageId = optionalText(
			root,
			"selectedImageMessageId",
			"選定圖片訊息代碼",
			200
		);
		boolean imageDeclined = requiredBoolean(root, "imageDeclined", "拒絕提供圖片");
		validateImageDecision(selectedImageMessageId, imageDeclined, imageAssessments);

		List<MissingBaseField> missingBaseFields = validateMissingBaseFields(root.get("missingBaseFields"));
		List<MissingItemFieldGroup> missingItemFields = validateMissingItemFields(root.get("missingItemFields"));
		String nextAction = requiredEnum(root, "nextAction", "nextAction", NEXT_ACTIONS);
		validateNextAction(
			nextAction,
			schemeCode,
			selectedImageMessageId,
			imageDeclined,
			imageAssessments,
			missingBaseFields,
			missingItemFields
		);
		List<String> warnings = stringList(root.get("warnings"), "警告", 20, 1000);

		return new ValidatedQuotationRequest(
			schemaVersion,
			schemeCode,
			schemeConfidence,
			headerPatch,
			standardItems,
			customItems,
			removedItemCodes,
			imageAssessments,
			selectedImageMessageId,
			imageDeclined,
			missingBaseFields,
			missingItemFields,
			nextAction,
			warnings
		);
	}

	// 方法：報價格式一律以使用者指定值為準；未指定時 AI 不得提出格式，已指定時不得改判。
	private String resolveSchemeCode(String lockedSchemeCode, String declaredSchemeCode, BigDecimal confidence) {
		if (lockedSchemeCode == null) {
			if (declaredSchemeCode != null) {
				throw validation("報價格式必須由使用者指定，AI 不可自行判定為 " + declaredSchemeCode);
			}

			requireNoSchemeClaim(confidence);
			return null;
		}

		String locked = lockedSchemeCode.trim().toUpperCase(Locale.ROOT);
		if (!SCHEME_CODES.contains(locked)) throw validation("不支援的報價格式：" + locked);

		if (declaredSchemeCode != null && !declaredSchemeCode.equals(locked)) {
			throw validation(
				"報價格式已由使用者指定為 " + locked + "，AI 不可改判為 " + declaredSchemeCode
			);
		}

		boolean active = repository.findSchemes().stream()
			.anyMatch(scheme -> scheme.code().equals(locked) && scheme.isActive());
		if (!active) throw validation("不存在或未啟用的報價格式：" + locked);

		return locked;
	}

	// 方法：沒有使用者指定格式時，AI 不得以任何信心宣稱格式。
	private void requireNoSchemeClaim(BigDecimal confidence) {
		if (confidence.signum() != 0) throw validation("報價格式為 null 時，schemeConfidence 必須為 0");
	}

	// 方法：驗證本次訊息明確提供的頂部欄位 patch。
	private HeaderPatch validateHeaderPatch(JsonNode node) {
		requireObject(node, "頂部欄位 patch");
		requireAllowedFields(node, HEADER_FIELDS, "頂部欄位 patch");

		return new HeaderPatch(
			optionalExtractedString(node, "companyName", "公司名稱"),
			optionalExtractedString(node, "workName", "工作名稱"),
			optionalExtractedString(node, "contactName", "聯絡人"),
			optionalExtractedString(node, "phone", "電話"),
			optionalExtractedString(node, "fax", "傳真"),
			optionalExtractedString(node, "email", "信箱"),
			optionalExtractedString(node, "projectLocation", "工程地點"),
			optionalExtractedString(node, "salesRepresentative", "業務承辦"),
			optionalExtractedString(node, "additionalHeader", "其他抬頭資料")
		);
	}

	// 方法：取得所選格式的啟用品項目錄；格式未辨識時不查詢或猜測主檔。
	private Map<String, QuotationAdminRepository.SchemeItem> activeCatalog(String schemeCode) {
		if (schemeCode == null) return Map.of();

		Map<String, QuotationAdminRepository.SchemeItem> catalog = new LinkedHashMap<>();
		for (QuotationAdminRepository.SchemeItem item : repository.findActiveSchemeItems(schemeCode)) {
			catalog.put(item.itemCode(), item);
		}
		return Map.copyOf(catalog);
	}

	// 方法：驗證標準品項意圖，並只從資料庫補齊固定欄位，不執行任何金額計算。
	private List<ResolvedItem> validateStandardItems(
		JsonNode node,
		String schemeCode,
		Map<String, QuotationAdminRepository.SchemeItem> catalog
	) {
		requireArray(node, "標準品項", 0, 100);
		if (schemeCode == null && !node.isEmpty()) throw validation("報價格式未指定前不可提出標準品項");

		if (CATALOG_FREE_SCHEMES.contains(schemeCode) && !node.isEmpty()) {
			throw validation(catalogFreeMessage(schemeCode));
		}

		Set<String> seenCodes = new HashSet<>();
		List<ResolvedItem> items = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode itemNode = node.get(index);
			String label = "標準品項第 " + (index + 1) + " 筆";
			requireObject(itemNode, label);
			requireAllowedFields(itemNode, STANDARD_ITEM_ALLOWED_FIELDS, label);
			requireRequiredFields(itemNode, STANDARD_ITEM_FIELDS, label);

			String itemCode = requiredUppercaseText(itemNode, "itemCode", label + "代碼", 100);
			if (!seenCodes.add(itemCode)) throw validation("標準品項代碼不可重複：" + itemCode);

			if (PLACEHOLDER_ITEM_CODES.contains(itemCode)) {
				throw validation("標準品項代碼 " + itemCode + " 是佔位代碼；無法對應主檔時應改列入品項缺漏欄位");
			}

			BigDecimal quantity = optionalPositiveDecimal(itemNode, "quantity", label + "數量");
			String sourceText = requiredText(itemNode, "sourceText", label + "來源文字", 1000);
			BigDecimal confidence = decimalInRange(
				itemNode,
				"confidence",
				label + "信心",
				BigDecimal.ZERO,
				BigDecimal.ONE
			);
			ensureConfident(confidence, label);

			QuotationAdminRepository.SchemeItem master = catalog.get(itemCode);
			if (master == null) throw validation("標準品項 " + itemCode + " 不屬於所選格式 " + schemeCode);

			items.add(
				new ResolvedItem(
					master.itemCode(),
					master.itemName(),
					master.specification(),
					quantity,
					master.unit(),
					master.unitPrice(),
					null,
					master.remark(),
					master.displayOrder(),
					master.calculationMode(),
					master.isCustomerVisible(),
					sourceText,
					confidence,
					matchedName(itemNode, label, master.itemName())
				)
			);
		}
		return List.copyOf(items);
	}

	// 方法：驗證臨時或動態品項只包含使用者明確提供且附來源信心的欄位。
	private List<CustomItem> validateCustomItems(JsonNode node, String schemeCode) {
		requireArray(node, "臨時或動態品項", 0, MAXIMUM_CUSTOM_ITEMS);

		Set<String> seenIds = new HashSet<>();
		List<CustomItem> items = new ArrayList<>();
		int temporaryCount = 0;
		for (int index = 0; index < node.size(); index++) {
			JsonNode itemNode = node.get(index);
			String label = "臨時或動態品項第 " + (index + 1) + " 筆";
			requireObject(itemNode, label);
			requireExactFields(itemNode, CUSTOM_ITEM_FIELDS, label);

			String clientItemId = requiredText(itemNode, "clientItemId", label + "識別碼", 100);
			if (!clientItemId.matches("[A-Za-z0-9_-]+")) throw validation(label + "識別碼格式錯誤");

			if (!seenIds.add(clientItemId)) throw validation("臨時或動態品項識別碼不可重複：" + clientItemId);

			String kind = requiredEnum(itemNode, "kind", label + "種類", Set.of("TEMPORARY", "DYNAMIC"));
			validateCustomItemKind(schemeCode, kind, label);
			if ("TEMPORARY".equals(kind)) temporaryCount++;

			items.add(
				new CustomItem(
					clientItemId,
					kind,
					nullableExtractedString(itemNode, "itemName", label + "品項"),
					nullableExtractedString(itemNode, "specification", label + "規格"),
					nullableExtractedString(itemNode, "unit", label + "單位"),
					nullableExtractedDecimal(itemNode, "unitPrice", label + "單價", true),
					nullableExtractedDecimal(itemNode, "quantity", label + "數量", false),
					nullableExtractedString(itemNode, "remark", label + "備註")
				)
			);
		}

		if (("CNS".equals(schemeCode) || "GENERAL".equals(schemeCode)) && temporaryCount > 2) {
			throw validation("CNS／一般架每張報價最多 2 筆臨時品項");
		}
		return List.copyOf(items);
	}

	// 方法：依五格式限制臨時與動態品項的使用方式。
	private void validateCustomItemKind(String schemeCode, String kind, String label) {
		if (("CNS".equals(schemeCode) || "GENERAL".equals(schemeCode)) && !"TEMPORARY".equals(kind)) {
			throw validation(label + "在 CNS／一般架只能使用 TEMPORARY");
		}

		if (CATALOG_FREE_SCHEMES.contains(schemeCode) && !"DYNAMIC".equals(kind)) {
			throw validation(label + "在船用／空白／銷售格式只能使用 DYNAMIC");
		}
	}

	// 方法：說明不使用固定品項的格式應改由使用者直接定義品項內容。
	private String catalogFreeMessage(String schemeCode) {
		return schemeCode + " 格式不使用固定品項，請直接說明品項名稱、規格、單位、單價與數量";
	}

	// 方法：驗證明確刪除的標準品項代碼存在於所選格式，且不與數量意圖衝突。
	private List<String> validateRemovedItemCodes(
		JsonNode node,
		String schemeCode,
		Map<String, QuotationAdminRepository.SchemeItem> catalog,
		List<ResolvedItem> standardItems
	) {
		List<String> codes = uppercaseStringList(node, "刪除品項代碼", 100, 100);
		if (schemeCode == null && !codes.isEmpty()) throw validation("報價格式未指定前不可提出刪除品項");

		if (CATALOG_FREE_SCHEMES.contains(schemeCode) && !codes.isEmpty()) {
			throw validation(catalogFreeMessage(schemeCode));
		}

		Set<String> intentCodes = new HashSet<>();
		standardItems.forEach(item -> intentCodes.add(item.itemCode()));
		for (String code : codes) {
			if (!catalog.containsKey(code)) throw validation("刪除品項 " + code + " 不屬於所選格式 " + schemeCode);

			if (intentCodes.contains(code)) throw validation("品項不可同時設定數量及刪除：" + code);
		}
		return codes;
	}

	// 方法：驗證所有候選圖片的品質、視角、區別度與理由。
	private List<ImageAssessment> validateImageAssessments(JsonNode node) {
		requireArray(node, "候選圖片評估", 0, 20);

		Set<String> seenIds = new HashSet<>();
		List<ImageAssessment> assessments = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode assessmentNode = node.get(index);
			String label = "候選圖片評估第 " + (index + 1) + " 筆";
			requireObject(assessmentNode, label);
			requireExactFields(assessmentNode, IMAGE_FIELDS, label);

			String messageId = requiredText(assessmentNode, "messageId", label + "訊息代碼", 200);
			if (!seenIds.add(messageId)) throw validation("候選圖片訊息代碼不可重複：" + messageId);

			assessments.add(
				new ImageAssessment(
					messageId,
					score(assessmentNode, "qualityScore", label + "品質分數"),
					score(assessmentNode, "viewpointScore", label + "視角分數"),
					score(assessmentNode, "distinctivenessScore", label + "區別度分數"),
					requiredText(assessmentNode, "reason", label + "理由", 1000)
				)
			);
		}
		return List.copyOf(assessments);
	}

	// 方法：確認拒絕圖片與選圖互斥，且選圖為最高區別度候選之一。
	private void validateImageDecision(
		String selectedImageMessageId,
		boolean imageDeclined,
		List<ImageAssessment> assessments
	) {
		if (imageDeclined && selectedImageMessageId != null) throw validation("拒絕提供圖片時不可同時選定圖片");

		if (selectedImageMessageId == null) return;

		ImageAssessment selected = assessments.stream()
			.filter(assessment -> selectedImageMessageId.equals(assessment.messageId()))
			.findFirst()
			.orElseThrow(() -> validation("選定圖片訊息代碼必須存在於候選圖片評估"));
		BigDecimal maximumScore = assessments.stream()
			.map(ImageAssessment::distinctivenessScore)
			.max(BigDecimal::compareTo)
			.orElseThrow();
		if (selected.distinctivenessScore().compareTo(maximumScore) < 0) {
			throw validation("選定圖片必須是區別度分數最高的候選圖片；同分可任選");
		}
	}

	// 方法：驗證頂部缺漏分組保留原因、來源原文及信心，且每個欄位只出現一次。
	private List<MissingBaseField> validateMissingBaseFields(JsonNode node) {
		requireArray(node, "頂部缺漏欄位", 0, 20);

		Set<String> seenFields = new HashSet<>();
		List<MissingBaseField> fields = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode fieldNode = node.get(index);
			String label = "頂部缺漏第 " + (index + 1) + " 筆";
			requireObject(fieldNode, label);
			requireExactFields(fieldNode, MISSING_BASE_ENTRY_FIELDS, label);

			String field = requiredExactEnum(fieldNode, "field", label + "欄位", baseFieldNames());
			if (!seenFields.add(field)) throw validation("頂部缺漏欄位不可重複：" + field);

			MissingEvidence evidence = missingEvidence(fieldNode, label);
			fields.add(
				new MissingBaseField(
					field,
					evidence.reason(),
					evidence.sourceText(),
					evidence.confidence()
				)
			);
		}
		return List.copyOf(fields);
	}

	// 方法：驗證每一品項的全部缺漏欄位集中在同一分組，供應用程式一次回問。
	private List<MissingItemFieldGroup> validateMissingItemFields(JsonNode node) {
		requireArray(node, "品項缺漏欄位", 0, 50);

		Set<String> seenItemReferences = new HashSet<>();
		List<MissingItemFieldGroup> groups = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode groupNode = node.get(index);
			String label = "品項缺漏第 " + (index + 1) + " 組";
			requireObject(groupNode, label);
			requireExactFields(groupNode, MISSING_ITEM_ENTRY_FIELDS, label);

			String itemReference = requiredText(groupNode, "itemRef", label + "品項參照", 100);
			if (!seenItemReferences.add(itemReference)) {
				throw validation("同一品項的缺漏欄位必須集中在單一分組：" + itemReference);
			}

			List<String> fields = enumStringList(
				groupNode.get("fields"),
				label + "欄位",
				1,
				7,
				ITEM_FIELD_NAMES
			);
			MissingEvidence evidence = missingEvidence(groupNode, label);
			groups.add(
				new MissingItemFieldGroup(
					itemReference,
					fields,
					evidence.reason(),
					evidence.sourceText(),
					evidence.confidence()
				)
			);
		}
		return List.copyOf(groups);
	}

	// 方法：依合併缺漏優先順序驗證 nextAction，只回傳宣告值而不執行任何外部動作。
	private void validateNextAction(
		String nextAction,
		String schemeCode,
		String selectedImageMessageId,
		boolean imageDeclined,
		List<ImageAssessment> assessments,
		List<MissingBaseField> missingBaseFields,
		List<MissingItemFieldGroup> missingItemFields
	) {
		String expected;
		if (!missingBaseFields.isEmpty()) expected = "REQUEST_BASE_FIELDS";
		else if (!missingItemFields.isEmpty()) expected = "REQUEST_ITEM_FIELDS";
		else if (requiresImageDecision(schemeCode, selectedImageMessageId, imageDeclined, assessments)) {
			expected = "REQUEST_IMAGE_DECISION";
		}
		else expected = "SHOW_PREVIEW";

		if (!expected.equals(nextAction)) {
			throw validation("nextAction 必須為 " + expected + "；應用程式只會執行通過驗證的白名單動作");
		}
	}

	// 方法：船用、空白或已有候選圖片但尚未選擇時，需要明確完成圖片決策。
	private boolean requiresImageDecision(
		String schemeCode,
		String selectedImageMessageId,
		boolean imageDeclined,
		List<ImageAssessment> assessments
	) {
		if (selectedImageMessageId != null || imageDeclined) return false;

		return "MARINE".equals(schemeCode) || "BLANK".equals(schemeCode) || !assessments.isEmpty();
	}

	// 方法：解析明確提取的文字欄位，拒絕低信心值進入 patch。
	private ExtractedString optionalExtractedString(JsonNode object, String field, String label) {
		JsonNode node = object.get(field);
		if (node == null || node.isNull()) return null;

		return extractedString(node, label);
	}

	// 方法：解析固定存在但允許為 null 的臨時或動態品項文字欄位。
	private ExtractedString nullableExtractedString(JsonNode object, String field, String label) {
		JsonNode node = object.get(field);
		if (node == null) throw validation(label + "缺少必要欄位");

		if (node.isNull()) return null;

		return extractedString(node, label);
	}

	// 方法：驗證文字值、原文及信心三欄固定結構。
	private ExtractedString extractedString(JsonNode node, String label) {
		requireObject(node, label);
		requireExactFields(node, EXTRACTED_VALUE_FIELDS, label);

		String value = requiredText(node, "value", label + "值", 1000);
		String sourceText = requiredText(node, "sourceText", label + "來源原文", 2000);
		BigDecimal confidence = decimalInRange(
			node,
			"confidence",
			label + "信心",
			BigDecimal.ZERO,
			BigDecimal.ONE
		);
		ensureConfident(confidence, label);
		return new ExtractedString(value, sourceText, confidence);
	}

	// 方法：解析固定存在但允許為 null 的臨時或動態品項數字欄位。
	private ExtractedDecimal nullableExtractedDecimal(
		JsonNode object,
		String field,
		String label,
		boolean allowZero
	) {
		JsonNode node = object.get(field);
		if (node == null) throw validation(label + "缺少必要欄位");

		if (node.isNull()) return null;

		requireObject(node, label);
		requireExactFields(node, EXTRACTED_VALUE_FIELDS, label);
		BigDecimal value = decimalInRange(node, "value", label + "值", BigDecimal.ZERO, MAXIMUM_VALUE);
		if (!allowZero && value.signum() <= 0) throw validation(label + "必須大於 0");

		String sourceText = requiredText(node, "sourceText", label + "來源原文", 2000);
		BigDecimal confidence = decimalInRange(
			node,
			"confidence",
			label + "信心",
			BigDecimal.ZERO,
			BigDecimal.ONE
		);
		ensureConfident(confidence, label);
		return new ExtractedDecimal(value, sourceText, confidence);
	}

	// 方法：解析缺漏原因及可空來源證據，並確保低信心原因真的低於採用門檻。
	private MissingEvidence missingEvidence(JsonNode node, String label) {
		String reason = requiredEnum(node, "reason", label + "原因", MISSING_REASONS);
		String sourceText = optionalText(node, "sourceText", label + "來源原文", 2000);
		BigDecimal confidence = optionalDecimalInRange(
			node,
			"confidence",
			label + "信心",
			BigDecimal.ZERO,
			BigDecimal.ONE
		);

		if ("LOW_CONFIDENCE".equals(reason)) {
			if (sourceText == null || confidence == null) throw validation(label + "低信心缺漏必須保留來源原文及信心");

			if (confidence.compareTo(MINIMUM_CONFIDENCE) >= 0) {
				throw validation(label + "標記 LOW_CONFIDENCE 時信心必須低於 " + MINIMUM_CONFIDENCE);
			}
		}
		return new MissingEvidence(reason, sourceText, confidence);
	}

	// 方法：拒絕低於採用門檻的 AI 值，避免模型猜測後直接進入預覽資料。
	private void ensureConfident(BigDecimal confidence, String label) {
		if (confidence.compareTo(MINIMUM_CONFIDENCE) < 0) {
			throw validation(label + "為低信心資料，不可猜測；請改列入缺漏欄位");
		}
	}

	// 方法：取得全部允許的頂部缺漏欄位名稱。
	private Set<String> baseFieldNames() {
		Set<String> fields = new HashSet<>(HEADER_FIELDS);
		fields.add("schemeCode");
		return Set.copyOf(fields);
	}

	// 方法：取得 AI 對應到主檔品項時使用者原本的說法，供預覽向使用者確認。
	private String matchedName(JsonNode itemNode, String label, String masterItemName) {
		JsonNode value = itemNode.get("matchedName");
		if (value == null || value.isNull()) return null;

		String matched = requiredText(itemNode, "matchedName", label + "對應原文", 100);
		return matched.equals(masterItemName) ? null : matched;
	}

	// 方法：驗證 JSON 節點為物件。
	private void requireObject(JsonNode node, String label) {
		if (node == null || !node.isObject()) throw validation(label + "必須是 JSON 物件");
	}

	// 方法：驗證物件只含允許欄位，且固定契約欄位全部存在。
	private void requireExactFields(JsonNode node, Set<String> allowedFields, String label) {
		requireAllowedFields(node, allowedFields, label);
		requireRequiredFields(node, allowedFields, label);
	}

	// 方法：驗證固定契約欄位全部存在，供同時具備選填欄位的物件使用。
	private void requireRequiredFields(JsonNode node, Set<String> requiredFields, String label) {
		Set<String> missingFields = new LinkedHashSet<>(requiredFields);
		missingFields.removeAll(node.propertyNames());
		if (!missingFields.isEmpty()) throw validation(label + "缺少必要欄位：" + String.join("、", missingFields));
	}

	// 方法：拒絕契約未定義欄位，避免 AI 夾帶固定資料、計算結果或執行指令。
	private void requireAllowedFields(JsonNode node, Set<String> allowedFields, String label) {
		Set<String> unexpectedFields = new LinkedHashSet<>(node.propertyNames());
		unexpectedFields.removeAll(allowedFields);
		if (!unexpectedFields.isEmpty()) throw validation(label + "含有不允許欄位：" + String.join("、", unexpectedFields));
	}

	// 方法：驗證 JSON 節點為有界陣列。
	private void requireArray(JsonNode node, String label, int minimumSize, int maximumSize) {
		if (node == null || !node.isArray()) throw validation(label + "必須是 JSON 陣列");

		if (node.size() < minimumSize || node.size() > maximumSize) {
			throw validation(label + "筆數必須介於 " + minimumSize + " 至 " + maximumSize);
		}
	}

	// 方法：取得必要文字並限制正規化後長度。
	private String requiredText(JsonNode object, String field, String label, int maximumLength) {
		JsonNode value = object.get(field);
		if (value == null || !value.isString()) throw validation(label + "必須是文字");

		String normalized = value.stringValue().trim();
		if (normalized.isEmpty()) throw validation(label + "不可留空");

		if (normalized.length() > maximumLength) throw validation(label + "長度不可超過 " + maximumLength);

		return normalized;
	}

	// 方法：取得必要大寫代碼文字。
	private String requiredUppercaseText(JsonNode object, String field, String label, int maximumLength) {
		return requiredText(object, field, label, maximumLength).toUpperCase(Locale.ROOT);
	}

	// 方法：取得可為 null 的文字欄位。
	private String optionalText(JsonNode object, String field, String label, int maximumLength) {
		JsonNode value = object.get(field);
		if (value == null) throw validation(label + "缺少必要欄位");

		if (value.isNull()) return null;

		return requiredText(object, field, label, maximumLength);
	}

	// 方法：取得可為 null 的大寫代碼文字。
	private String optionalUppercaseText(JsonNode object, String field, String label, int maximumLength) {
		String value = optionalText(object, field, label, maximumLength);
		return value == null ? null : value.toUpperCase(Locale.ROOT);
	}

	// 方法：驗證必要布林欄位。
	private boolean requiredBoolean(JsonNode object, String field, String label) {
		JsonNode value = object.get(field);
		if (value == null || !value.isBoolean()) throw validation(label + "必須是布林值");

		return value.booleanValue();
	}

	// 方法：驗證必要列舉文字。
	private String requiredEnum(JsonNode object, String field, String label, Set<String> allowedValues) {
		String value = requiredText(object, field, label, 100).toUpperCase(Locale.ROOT);
		if (!allowedValues.contains(value)) throw validation(label + "不是允許值：" + value);

		return value;
	}

	// 方法：驗證區分大小寫的必要列舉文字，供 camelCase 契約欄位名稱使用。
	private String requiredExactEnum(JsonNode object, String field, String label, Set<String> allowedValues) {
		String value = requiredText(object, field, label, 100);
		if (!allowedValues.contains(value)) throw validation(label + "不是允許值：" + value);

		return value;
	}

	// 方法：驗證必要十進位數值範圍。
	private BigDecimal decimalInRange(
		JsonNode object,
		String field,
		String label,
		BigDecimal minimum,
		BigDecimal maximum
	) {
		JsonNode value = object.get(field);
		if (value == null || !value.isNumber()) throw validation(label + "必須是數字");

		BigDecimal decimal = value.decimalValue();
		if (decimal.compareTo(minimum) < 0 || decimal.compareTo(maximum) > 0) {
			throw validation(label + "必須介於 " + minimum + " 至 " + maximum);
		}
		return decimal;
	}

	// 方法：取得可為 null 的十進位數值。
	private BigDecimal optionalDecimalInRange(
		JsonNode object,
		String field,
		String label,
		BigDecimal minimum,
		BigDecimal maximum
	) {
		JsonNode value = object.get(field);
		if (value == null) throw validation(label + "缺少必要欄位");

		if (value.isNull()) return null;

		return decimalInRange(object, field, label, minimum, maximum);
	}

	// 方法：取得可為 null 且大於零的標準品項數量。
	private BigDecimal optionalPositiveDecimal(JsonNode object, String field, String label) {
		BigDecimal value = optionalDecimalInRange(object, field, label, BigDecimal.ZERO, MAXIMUM_VALUE);
		if (value != null && value.signum() <= 0) throw validation(label + "必須大於 0");

		return value;
	}

	// 方法：驗證 0 到 1 的圖片評分。
	private BigDecimal score(JsonNode object, String field, String label) {
		return decimalInRange(object, field, label, BigDecimal.ZERO, BigDecimal.ONE);
	}

	// 方法：驗證一般文字陣列。
	private List<String> stringList(JsonNode node, String label, int maximumSize, int maximumTextLength) {
		requireArray(node, label, 0, maximumSize);

		List<String> values = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode value = node.get(index);
			if (!value.isString()) throw validation(label + "第 " + (index + 1) + " 筆必須是文字");

			String normalized = value.stringValue().trim();
			if (normalized.isEmpty()) throw validation(label + "第 " + (index + 1) + " 筆不可留空");

			if (normalized.length() > maximumTextLength) {
				throw validation(label + "第 " + (index + 1) + " 筆長度不可超過 " + maximumTextLength);
			}
			values.add(normalized);
		}
		return List.copyOf(values);
	}

	// 方法：驗證大寫代碼陣列並拒絕重複值。
	private List<String> uppercaseStringList(JsonNode node, String label, int maximumSize, int maximumTextLength) {
		List<String> values = stringList(node, label, maximumSize, maximumTextLength).stream()
			.map(value -> value.toUpperCase(Locale.ROOT))
			.toList();
		if (new HashSet<>(values).size() != values.size()) throw validation(label + "不可重複");

		return List.copyOf(values);
	}

	// 方法：驗證必要且不重複的列舉文字陣列。
	private List<String> enumStringList(
		JsonNode node,
		String label,
		int minimumSize,
		int maximumSize,
		Set<String> allowedValues
	) {
		requireArray(node, label, minimumSize, maximumSize);

		Set<String> values = new LinkedHashSet<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode value = node.get(index);
			if (!value.isString()) throw validation(label + "第 " + (index + 1) + " 筆必須是文字");

			String normalized = value.stringValue().trim();
			if (!allowedValues.contains(normalized)) throw validation(label + "含有不允許值：" + normalized);

			if (!values.add(normalized)) throw validation(label + "不可重複：" + normalized);
		}
		return List.copyOf(values);
	}

	// 方法：建立固定驗證錯誤型別供 API 與 AI 解析流程一致處理。
	private QuotationAdminException validation(String message) {
		return new QuotationAdminException("VALIDATION_ERROR", message);
	}

	public record ValidatedQuotationRequest(
		String schemaVersion,
		String schemeCode,
		BigDecimal schemeConfidence,
		HeaderPatch headerPatch,
		List<ResolvedItem> standardItems,
		List<CustomItem> customItems,
		List<String> removedItemCodes,
		List<ImageAssessment> imageAssessments,
		String selectedImageMessageId,
		boolean imageDeclined,
		List<MissingBaseField> missingBaseFields,
		List<MissingItemFieldGroup> missingItemFields,
		String nextAction,
		List<String> warnings
	) {

		// 方法：保留既有報表測試與服務的建構介面；正式 AI 輸入仍只接受 2.0 契約。
		public ValidatedQuotationRequest(
			String schemaVersion,
			String quotationName,
			String schemeCode,
			BigDecimal schemeConfidence,
			List<ResolvedItem> items,
			String selectedImageMessageId,
			List<ImageAssessment> imageAssessments,
			List<String> missingFields,
			List<String> warnings
		) {
			this(
				schemaVersion,
				schemeCode,
				schemeConfidence,
				new HeaderPatch(
					null,
					new ExtractedString(quotationName, quotationName, BigDecimal.ONE),
					null,
					null,
					null,
					null,
					null,
					null
				),
				items,
				List.of(),
				List.of(),
				imageAssessments,
				selectedImageMessageId,
				false,
				List.of(),
				List.of(),
				missingFields.isEmpty() ? "SHOW_PREVIEW" : "REQUEST_BASE_FIELDS",
				warnings
			);
		}

		// 方法：保留既有報表服務的唯讀介面；2.0 驗證階段不會建立複價。
		public List<ResolvedItem> items() {
			return standardItems;
		}

		// 方法：由頂部 patch 提供暫時顯示名稱，正式檔名仍由後續確認流程決定。
		public String quotationName() {
			if (headerPatch.workName() != null) return headerPatch.workName().value();

			if (headerPatch.companyName() != null) return headerPatch.companyName().value();

			return "未命名報價";
		}
	}

	public record HeaderPatch(
		ExtractedString companyName,
		ExtractedString workName,
		ExtractedString contactName,
		ExtractedString phone,
		ExtractedString fax,
		ExtractedString email,
		ExtractedString projectLocation,
		ExtractedString salesRepresentative,
		ExtractedString additionalHeader
	) {

		// 方法：保留既有測試與內部呼叫的八欄建構介面。
		public HeaderPatch(
			ExtractedString companyName,
			ExtractedString workName,
			ExtractedString contactName,
			ExtractedString phone,
			ExtractedString fax,
			ExtractedString email,
			ExtractedString projectLocation,
			ExtractedString additionalHeader
		) {
			this(
				companyName,
				workName,
				contactName,
				phone,
				fax,
				email,
				projectLocation,
				null,
				additionalHeader
			);
		}
	}

	public record ExtractedString(String value, String sourceText, BigDecimal confidence) {}

	public record ExtractedDecimal(BigDecimal value, String sourceText, BigDecimal confidence) {}

	public record ResolvedItem(
		String itemCode,
		String itemName,
		String specification,
		BigDecimal quantity,
		String unit,
		BigDecimal unitPrice,
		BigDecimal lineAmount,
		String remark,
		int displayOrder,
		String calculationMode,
		boolean isCustomerVisible,
		String sourceText,
		BigDecimal confidence,
		String matchedName
	) {

		// 方法：保留報表與既有服務的建構介面；未經名稱近似對應的品項沒有待確認原文。
		public ResolvedItem(
			String itemCode,
			String itemName,
			String specification,
			BigDecimal quantity,
			String unit,
			BigDecimal unitPrice,
			BigDecimal lineAmount,
			String remark,
			int displayOrder,
			String calculationMode,
			boolean isCustomerVisible,
			String sourceText,
			BigDecimal confidence
		) {
			this(
				itemCode,
				itemName,
				specification,
				quantity,
				unit,
				unitPrice,
				lineAmount,
				remark,
				displayOrder,
				calculationMode,
				isCustomerVisible,
				sourceText,
				confidence,
				null
			);
		}
	}

	public record CustomItem(
		String clientItemId,
		String kind,
		ExtractedString itemName,
		ExtractedString specification,
		ExtractedString unit,
		ExtractedDecimal unitPrice,
		ExtractedDecimal quantity,
		ExtractedString remark
	) {}

	public record ImageAssessment(
		String messageId,
		BigDecimal qualityScore,
		BigDecimal viewpointScore,
		BigDecimal distinctivenessScore,
		String reason
	) {}

	public record MissingBaseField(
		String field,
		String reason,
		String sourceText,
		BigDecimal confidence
	) {}

	public record MissingItemFieldGroup(
		String itemRef,
		List<String> fields,
		String reason,
		String sourceText,
		BigDecimal confidence
	) {}

	private record MissingEvidence(String reason, String sourceText, BigDecimal confidence) {}
}
