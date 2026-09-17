package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 依主檔與使用者明確意圖建立五格式報價列，並以精確十進位數完成計價。
 */
@Service
public class QuotationCalculationService {

	private static final int MONEY_SCALE = 2;
	private static final int MAXIMUM_DYNAMIC_ITEMS = 200;
	private static final Set<String> FIXED_SCHEMES = Set.of("CNS", "GENERAL");
	private static final Set<String> DYNAMIC_SCHEMES = Set.of("MARINE", "BLANK", "SALES");
	private static final Set<String> SUPPORTED_SCHEMES = Set.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES");

	private final QuotationAdminRepository repository;
	private final QuotationBusinessRules businessRules;

	// 方法：建立報價列解析與計價服務。
	public QuotationCalculationService(
		QuotationAdminRepository repository,
		QuotationBusinessRules businessRules
	) {
		this.repository = repository;
		this.businessRules = businessRules;
	}

	// 方法：建立完整報價列並由程式重算複價、小計、稅額與總額。
	public QuotationCalculationResult calculate(QuotationCalculationRequest request) {
		if (request == null) throw validation("報價計價輸入不可為 null");

		String schemeCode = normalizedSchemeCode(request.schemeCode());
		List<QuotationCalculationRequest.StandardItemIntent> standardIntents = safeList(request.standardItemIntents());
		List<QuotationCalculationRequest.CustomItem> customItems = safeList(request.customItems());
		Set<String> removedItemCodes = normalizedCodes(request.removedItemCodes());

		// 步驟 1：依方案建立固定鎖價列或動態計價列。
		List<QuotationCalculationResult.QuotationLine> lines = FIXED_SCHEMES.contains(schemeCode)
			? resolveFixedSchemeLines(schemeCode, standardIntents, customItems, removedItemCodes)
			: resolveDynamicSchemeLines(schemeCode, standardIntents, customItems, removedItemCodes);

		// 步驟 2：只彙總具有程式計算複價的列，空白數量不視為零。
		BigDecimal subtotal = lines.stream()
			.map(QuotationCalculationResult.QuotationLine::lineAmount)
			.filter(amount -> amount != null)
			.reduce(money(BigDecimal.ZERO), BigDecimal::add)
			.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		BigDecimal tax = money(subtotal.multiply(businessRules.taxRate()));
		BigDecimal total = money(subtotal.add(tax));

		// 步驟 3：船用只提供合計，其餘方案依各列客戶可見狀態產生明細。
		boolean summaryOnly = "MARINE".equals(schemeCode);
		List<QuotationCalculationResult.QuotationLine> customerLines = summaryOnly
			? List.of()
			: lines.stream().filter(QuotationCalculationResult.QuotationLine::customerVisible).toList();

		return new QuotationCalculationResult(
			schemeCode,
			List.copyOf(lines),
			List.copyOf(customerLines),
			subtotal,
			tax,
			total,
			summaryOnly
				? QuotationCalculationResult.CustomerPresentation.SUMMARY_ONLY
				: QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	// 方法：展開 CNS 或一般架的全部啟用品項，套用數量、明確刪除與臨時品項。
	private List<QuotationCalculationResult.QuotationLine> resolveFixedSchemeLines(
		String schemeCode,
		List<QuotationCalculationRequest.StandardItemIntent> standardIntents,
		List<QuotationCalculationRequest.CustomItem> customItems,
		Set<String> removedItemCodes
	) {
		// 外部 API：從資料庫取得當下全部啟用中的鎖價品項。
		List<QuotationAdminRepository.SchemeItem> catalogItems = repository.findActiveSchemeItems(schemeCode);
		Map<String, QuotationAdminRepository.SchemeItem> catalog = new LinkedHashMap<>();
		for (QuotationAdminRepository.SchemeItem item : catalogItems) {
			catalog.put(normalizedItemCode(item.itemCode()), item);
		}

		Map<String, BigDecimal> quantities = resolvedStandardQuantities(standardIntents, catalog.keySet());
		ensureKnownRemovedItems(removedItemCodes, catalog.keySet());

		List<QuotationCalculationResult.QuotationLine> lines = new ArrayList<>();
		for (QuotationAdminRepository.SchemeItem master : catalogItems) {
			String itemCode = normalizedItemCode(master.itemCode());
			if (removedItemCodes.contains(itemCode)) continue;

			BigDecimal quantity = quantities.get(itemCode);
			lines.add(
				new QuotationCalculationResult.QuotationLine(
					itemCode,
					master.itemName(),
					safeText(master.specification()),
					master.unit(),
					money(master.unitPrice()),
					quantity,
					quantity == null ? null : lineAmount(quantity, master.unitPrice()),
					safeText(master.remark()),
					master.displayOrder(),
					master.calculationMode(),
					QuotationCalculationResult.LineOrigin.STANDARD,
					master.isCustomerVisible()
				)
			);
		}

		int nextDisplayOrder = catalogItems.stream()
			.mapToInt(QuotationAdminRepository.SchemeItem::displayOrder)
			.max()
			.orElse(0) + 1;
		for (QuotationCalculationRequest.CustomItem customItem : customItems) {
			lines.add(customLine(customItem, nextDisplayOrder++, QuotationCalculationResult.LineOrigin.TEMPORARY, true));
		}

		return lines;
	}

	// 方法：建立船用、空白或銷售的動態列，維持這三種格式的既有上限。
	private List<QuotationCalculationResult.QuotationLine> resolveDynamicSchemeLines(
		String schemeCode,
		List<QuotationCalculationRequest.StandardItemIntent> standardIntents,
		List<QuotationCalculationRequest.CustomItem> customItems,
		Set<String> removedItemCodes
	) {
		if (!DYNAMIC_SCHEMES.contains(schemeCode)) throw validation("不支援的報價方案：" + schemeCode);

		if (!standardIntents.isEmpty() || !removedItemCodes.isEmpty()) {
			throw validation(schemeCode + " 不使用固定品項意圖或固定品項刪除清單");
		}

		if (customItems.size() > MAXIMUM_DYNAMIC_ITEMS) {
			throw validation(schemeCode + " 動態品項不可超過 " + MAXIMUM_DYNAMIC_ITEMS + " 筆");
		}

		boolean customerVisible = !"MARINE".equals(schemeCode);
		List<QuotationCalculationResult.QuotationLine> lines = new ArrayList<>();
		for (int index = 0; index < customItems.size(); index++) {
			lines.add(
				customLine(
					customItems.get(index),
					index + 1,
					QuotationCalculationResult.LineOrigin.DYNAMIC,
					customerVisible
				)
			);
		}
		return lines;
	}

	// 方法：驗證非主檔品項完整性並由數量與單價重算複價。
	private QuotationCalculationResult.QuotationLine customLine(
		QuotationCalculationRequest.CustomItem item,
		int displayOrder,
		QuotationCalculationResult.LineOrigin origin,
		boolean customerVisible
	) {
		if (item == null) throw validation("臨時或動態品項不可為 null");

		String itemName = requiredText(item.itemName(), "品項名稱");
		String unit = requiredText(item.unit(), "品項單位");
		if (item.specification() == null) throw validation("品項規格不可為 null");

		if (item.remark() == null) throw validation("品項備註不可為 null");

		if (origin == QuotationCalculationResult.LineOrigin.TEMPORARY && item.specification().isBlank()) {
			throw validation("臨時品項規格不可留空");
		}

		if (origin == QuotationCalculationResult.LineOrigin.TEMPORARY && item.remark().isBlank()) {
			throw validation("臨時品項備註不可留空");
		}

		if (origin == QuotationCalculationResult.LineOrigin.TEMPORARY && !item.confirmed()) {
			throw validation("品項「" + itemName + "」尚未經使用者確認");
		}

		BigDecimal unitPrice = nonNegative(item.unitPrice(), "品項單價");
		BigDecimal quantity = positive(item.quantity(), "品項數量");
		return new QuotationCalculationResult.QuotationLine(
			null,
			itemName,
			item.specification().trim(),
			unit,
			money(unitPrice),
			quantity,
			lineAmount(quantity, unitPrice),
			item.remark().trim(),
			displayOrder,
			"DIRECT",
			origin,
			customerVisible
		);
	}

	// 方法：解析標準品項數量並拒絕重複或不存在的品項代碼。
	private Map<String, BigDecimal> resolvedStandardQuantities(
		List<QuotationCalculationRequest.StandardItemIntent> intents,
		Set<String> catalogCodes
	) {
		Map<String, BigDecimal> quantities = new LinkedHashMap<>();
		for (QuotationCalculationRequest.StandardItemIntent intent : intents) {
			if (intent == null) throw validation("標準品項意圖不可為 null");

			String itemCode = normalizedItemCode(intent.itemCode());
			if (!catalogCodes.contains(itemCode)) throw validation("品項不屬於啟用中的方案主檔：" + itemCode);

			if (quantities.containsKey(itemCode)) throw validation("標準品項不可重複：" + itemCode);

			quantities.put(itemCode, positive(intent.quantity(), "品項 " + itemCode + " 數量"));
		}
		return quantities;
	}

	// 方法：確認刪除清單只含目前啟用中的固定品項。
	private void ensureKnownRemovedItems(Set<String> removedItemCodes, Set<String> catalogCodes) {
		Set<String> unknownCodes = new LinkedHashSet<>(removedItemCodes);
		unknownCodes.removeAll(catalogCodes);
		if (!unknownCodes.isEmpty()) throw validation("刪除清單含有非啟用品項：" + String.join("、", unknownCodes));
	}

	// 方法：正規化報價方案代碼並套用白名單。
	private String normalizedSchemeCode(String schemeCode) {
		String normalized = requiredText(schemeCode, "報價方案").toUpperCase(Locale.ROOT);
		if (!SUPPORTED_SCHEMES.contains(normalized)) throw validation("不支援的報價方案：" + normalized);

		return normalized;
	}

	// 方法：正規化固定品項代碼。
	private String normalizedItemCode(String itemCode) {
		return requiredText(itemCode, "品項代碼").toUpperCase(Locale.ROOT);
	}

	// 方法：正規化可空刪除清單並拒絕重複或空白代碼。
	private Set<String> normalizedCodes(Set<String> codes) {
		if (codes == null) return Set.of();

		Set<String> normalized = new LinkedHashSet<>();
		for (String code : codes) {
			String itemCode = normalizedItemCode(code);
			if (!normalized.add(itemCode)) throw validation("刪除品項代碼不可重複：" + itemCode);
		}
		return Set.copyOf(normalized);
	}

	// 方法：複製可空清單以避免計算期間被外部修改。
	private <T> List<T> safeList(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	// 方法：驗證必要文字並移除頭尾空白。
	private String requiredText(String value, String label) {
		if (value == null || value.isBlank()) throw validation(label + "不可留空");

		return value.trim();
	}

	// 方法：將可空固定文字轉成安全空字串。
	private String safeText(String value) {
		return value == null ? "" : value;
	}

	// 方法：驗證十進位數值必須大於零。
	private BigDecimal positive(BigDecimal value, String label) {
		if (value == null || value.signum() <= 0) throw validation(label + "必須大於 0");

		return value;
	}

	// 方法：驗證十進位數值不可小於零。
	private BigDecimal nonNegative(BigDecimal value, String label) {
		if (value == null || value.signum() < 0) throw validation(label + "不可小於 0");

		return value;
	}

	// 方法：以數量與單價計算複價，並依既有商業規則四捨五入至兩位小數。
	private BigDecimal lineAmount(BigDecimal quantity, BigDecimal unitPrice) {
		return money(quantity.multiply(unitPrice));
	}

	// 方法：以 HALF_UP 將金額統一保存至兩位小數。
	private BigDecimal money(BigDecimal value) {
		if (value == null) throw validation("金額不可為 null");

		return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	// 方法：建立報價計價規則錯誤。
	private QuotationCalculationException validation(String message) {
		return new QuotationCalculationException(message);
	}
}
