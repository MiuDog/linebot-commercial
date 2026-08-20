package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record QuotationDraftItem(
	String itemKey,
	QuotationDraftItemKind kind,
	Map<String, String> fields
) {

	// 方法：固定品項快照欄位，避免呼叫端後續修改草稿內容。
	public QuotationDraftItem {
		fields = fields == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}
}
