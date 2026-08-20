package dev.miudog.linebotcommercial.service.quotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QuotationDraftPatch(
	Map<String, String> baseFieldPatch,
	Map<String, Map<String, String>> itemFieldPatches,
	List<String> imageMessageIdsToAdd,
	Boolean imageDeclined,
	String selectedImageMessageId
) {

	// 方法：固定局部補件資料並保留呼叫端提供的品項順序。
	public QuotationDraftPatch {
		baseFieldPatch = baseFieldPatch == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(baseFieldPatch));
		itemFieldPatches = copyItemPatches(itemFieldPatches);
		imageMessageIdsToAdd = imageMessageIdsToAdd == null ? List.of() : List.copyOf(imageMessageIdsToAdd);
	}

	// 方法：複製每一筆品項補件欄位，防止巢狀集合被外部修改。
	private static Map<String, Map<String, String>> copyItemPatches(Map<String, Map<String, String>> source) {
		if (source == null) return Map.of();

		Map<String, Map<String, String>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
			Map<String, String> fields = entry.getValue() == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue()));
			copy.put(entry.getKey(), fields);
		}
		return Collections.unmodifiableMap(copy);
	}
}
