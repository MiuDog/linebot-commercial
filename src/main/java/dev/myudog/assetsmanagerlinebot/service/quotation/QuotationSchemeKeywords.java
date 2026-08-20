package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 以固定關鍵字由應用程式判定報價格式；報價格式只能由使用者明確指定，不交給 AI 推論。
 */
public final class QuotationSchemeKeywords {

	private static final List<String> SCHEME_CODES = List.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
	private static final Map<String, List<String>> KEYWORDS = buildKeywords();
	private static final Map<String, String> DISPLAY_NAMES = Map.of(
		"CNS", "CNS 架",
		"GENERAL", "一般架",
		"MARINE", "船用",
		"BLANK", "空白",
		"SALES", "銷售"
	);

	// 方法：禁止建立只有靜態關鍵字對照的工具類別。
	private QuotationSchemeKeywords() {
	}

	// 方法：建立由窄到寬的格式關鍵字對照，避免以工程性質或品項名稱猜測格式。
	private static Map<String, List<String>> buildKeywords() {
		Map<String, List<String>> keywords = new LinkedHashMap<>();
		keywords.put("CNS", List.of("CNS架", "CNS 架", "CNS"));
		keywords.put("GENERAL", List.of("一般架", "一般格式", "一般報價", "一般"));
		keywords.put("MARINE", List.of("船用", "船舶"));
		keywords.put("BLANK", List.of("空白"));
		keywords.put("SALES", List.of("銷售"));
		return Map.copyOf(keywords);
	}

	// 方法：取得五種報價格式代碼，供按鈕與驗證共用。
	public static List<String> schemeCodes() {
		return SCHEME_CODES;
	}

	// 方法：取得供 LINE 按鈕顯示的格式名稱。
	public static String displayName(String schemeCode) {
		return DISPLAY_NAMES.getOrDefault(schemeCode, schemeCode);
	}

	// 方法：確認代碼是五種報價格式之一。
	public static boolean isSupported(String schemeCode) {
		return schemeCode != null && KEYWORDS.containsKey(schemeCode.trim().toUpperCase(Locale.ROOT));
	}

	// 方法：只在使用者原文明確指名單一格式時回傳代碼；未提及或同時提及多種時回傳 null 以便回問。
	public static String parse(String text) {
		if (text == null || text.isBlank()) return null;

		String normalized = text.toUpperCase(Locale.ROOT);
		Set<String> matched = new LinkedHashSet<>();
		for (String schemeCode : SCHEME_CODES) {
			for (String keyword : KEYWORDS.get(schemeCode)) {
				if (normalized.contains(keyword.toUpperCase(Locale.ROOT))) {
					matched.add(schemeCode);
					break;
				}
			}
		}
		return matched.size() == 1 ? matched.iterator().next() : null;
	}
}
