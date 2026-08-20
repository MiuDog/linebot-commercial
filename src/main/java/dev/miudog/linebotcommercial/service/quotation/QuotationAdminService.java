package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 驗證管理頁輸入並協調報價主檔的讀寫與稽核紀錄。
 */
@Service
public class QuotationAdminService {

	private static final Pattern ITEM_CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
	private static final Set<String> CALCULATION_MODES = Set.of("DIRECT", "DERIVED", "MANUAL");

	private final QuotationAdminRepository repository;
	private final ObjectMapper objectMapper;

	// 方法：建立報價主檔管理服務。
	public QuotationAdminService(QuotationAdminRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	// 方法：列出報價類型與範本狀態。
	public List<QuotationAdminRepository.SchemeSummary> listSchemes() {
		return repository.findSchemes();
	}

	// 方法：列出全部品項主檔。
	public List<QuotationAdminRepository.Item> listItems() {
		return repository.findItems();
	}

	// 方法：建立經驗證的品項主檔並留下稽核紀錄。
	@Transactional
	public QuotationAdminRepository.Item createItem(CreateItemCommand command) {
		String code = validateCode(command.code());
		String name = requiredText(command.name(), "品項名稱", 100);
		List<String> aliases = validateAliases(command.aliases());

		QuotationAdminRepository.Item item = repository.createItem(code, name, aliases, command.isActive());
		writeAudit("CREATE", "QUOTATION_ITEM", Long.toString(item.id()), Map.of("code", code, "name", name));
		return item;
	}

	// 方法：以部分欄位更新品項並保留未指定資料。
	@Transactional
	public QuotationAdminRepository.Item updateItem(long itemId, UpdateItemCommand command) {
		QuotationAdminRepository.Item current = repository.findItem(itemId)
			.orElseThrow(() -> notFound("找不到指定品項"));
		String code = command.code() == null ? current.code() : validateCode(command.code());
		String name = command.name() == null ? current.name() : requiredText(command.name(), "品項名稱", 100);
		List<String> aliases = command.aliases() == null ? current.aliases() : validateAliases(command.aliases());
		boolean isActive = command.isActive() == null ? current.isActive() : command.isActive();

		QuotationAdminRepository.Item item = repository.updateItem(itemId, code, name, aliases, isActive);
		writeAudit("UPDATE", "QUOTATION_ITEM", Long.toString(item.id()), Map.of("code", code, "name", name));
		return item;
	}

	// 方法：列出指定報價類型所使用的固定品項組合。
	public List<QuotationAdminRepository.SchemeItem> listSchemeItems(String schemeCode) {
		String normalizedSchemeCode = normalizeSchemeCode(schemeCode);
		ensureSchemeExists(normalizedSchemeCode);
		return repository.findSchemeItems(normalizedSchemeCode);
	}

	// 方法：新增或更新報價類型中的規格、單位、單價與備註。
	@Transactional
	public QuotationAdminRepository.SchemeItem upsertSchemeItem(
		String schemeCode,
		long itemId,
		UpsertSchemeItemCommand command
	) {
		String normalizedSchemeCode = normalizeSchemeCode(schemeCode);
		long schemeId = repository.findSchemeId(normalizedSchemeCode)
			.orElseThrow(() -> notFound("找不到指定報價類型"));
		repository.findItem(itemId).orElseThrow(() -> notFound("找不到指定品項"));

		String specification = optionalText(command.specification(), "規格／說明", 200);
		String unit = requiredText(command.unit(), "單位", 20);
		BigDecimal unitPrice = validatePrice(command.unitPrice());
		String remark = optionalText(command.remark(), "備註", 500);
		int displayOrder = validateDisplayOrder(command.displayOrder());
		String calculationMode = validateCalculationMode(command.calculationMode());

		QuotationAdminRepository.SchemeItem schemeItem = repository.upsertSchemeItem(
			schemeId,
			itemId,
			specification,
			unit,
			unitPrice,
			remark,
			displayOrder,
			calculationMode,
			command.isCustomerVisible(),
			command.isActive()
		);
		writeAudit(
			"UPSERT",
			"QUOTATION_SCHEME_ITEM",
			Long.toString(schemeItem.id()),
			Map.of("schemeCode", normalizedSchemeCode, "itemId", itemId)
		);
		return schemeItem;
	}

	// 方法：驗證並標準化品項代碼。
	private String validateCode(String value) {
		String code = requiredText(value, "品項代碼", 64).toUpperCase(Locale.ROOT);
		if (!ITEM_CODE_PATTERN.matcher(code).matches()) {
			throw validation("品項代碼只能使用大寫英文字母、數字與底線，且必須以字母開頭");
		}
		return code;
	}

	// 方法：驗證 AI 辨識別名的數量與長度。
	private List<String> validateAliases(List<String> values) {
		if (values == null) return List.of();

		if (values.size() > 20) throw validation("品項別名最多 20 個");

		LinkedHashSet<String> aliases = new LinkedHashSet<>();
		for (String value : values) aliases.add(requiredText(value, "品項別名", 50));
		return List.copyOf(aliases);
	}

	// 方法：驗證單價必須為非負數。
	private BigDecimal validatePrice(BigDecimal value) {
		if (value == null || value.signum() < 0) throw validation("單價必須大於或等於 0");

		return value;
	}

	// 方法：驗證顯示順序在可控範圍內。
	private int validateDisplayOrder(Integer value) {
		if (value == null || value < 0 || value > 10000) throw validation("顯示順序必須介於 0 到 10000");

		return value;
	}

	// 方法：驗證計價模式只能使用系統允許值。
	private String validateCalculationMode(String value) {
		String mode = requiredText(value, "計價模式", 20).toUpperCase(Locale.ROOT);
		if (!CALCULATION_MODES.contains(mode)) throw validation("不支援的計價模式");

		return mode;
	}

	// 方法：驗證必填文字並移除頭尾空白。
	private String requiredText(String value, String label, int maximumLength) {
		if (value == null || value.isBlank()) throw validation(label + "不可留空");

		String normalized = value.trim();
		if (normalized.length() > maximumLength) throw validation(label + "長度不可超過 " + maximumLength);

		return normalized;
	}

	// 方法：驗證可選文字並將空字串轉成空值。
	private String optionalText(String value, String label, int maximumLength) {
		if (value == null || value.isBlank()) return null;

		return requiredText(value, label, maximumLength);
	}

	// 方法：標準化 URL 中的報價類型代碼。
	private String normalizeSchemeCode(String schemeCode) {
		return requiredText(schemeCode, "報價類型", 20).toUpperCase(Locale.ROOT);
	}

	// 方法：確認報價類型存在後才繼續查詢。
	private void ensureSchemeExists(String schemeCode) {
		if (repository.findSchemeId(schemeCode).isEmpty()) throw notFound("找不到指定報價類型");
	}

	// 方法：將允許的修改摘要序列化後寫入稽核紀錄。
	private void writeAudit(String action, String entityType, String entityId, Map<String, Object> summary) {
		// 外部 API：使用 Jackson 將白名單摘要轉成 JSON，再交由資料庫保存。
		repository.writeAudit(action, entityType, entityId, objectMapper.writeValueAsString(summary));
	}

	// 方法：建立資料驗證錯誤。
	private QuotationAdminException validation(String message) {
		return new QuotationAdminException("VALIDATION_ERROR", message);
	}

	// 方法：建立查無資料錯誤。
	private QuotationAdminException notFound(String message) {
		return new QuotationAdminException("NOT_FOUND", message);
	}

	public record CreateItemCommand(String code, String name, List<String> aliases, boolean isActive) {}

	public record UpdateItemCommand(String code, String name, List<String> aliases, Boolean isActive) {}

	public record UpsertSchemeItemCommand(
		String specification,
		String unit,
		BigDecimal unitPrice,
		String remark,
		Integer displayOrder,
		String calculationMode,
		boolean isCustomerVisible,
		boolean isActive
	) {}
}
