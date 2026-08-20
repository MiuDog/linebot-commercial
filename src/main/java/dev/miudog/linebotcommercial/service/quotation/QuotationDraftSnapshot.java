package dev.miudog.linebotcommercial.service.quotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QuotationDraftSnapshot(
	long draftId,
	int revision,
	QuotationDraftStatus status,
	String schemeCode,
	Map<String, String> baseFields,
	List<QuotationDraftItem> items,
	List<String> imageMessageIds,
	String selectedImageMessageId,
	boolean imageQuestionAsked,
	boolean imageDeclined,
	boolean previewPresented,
	String confirmationEventId
) {

	// 方法：固定草稿集合欄位，避免狀態判斷期間被呼叫端修改。
	public QuotationDraftSnapshot {
		baseFields = baseFields == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(baseFields));
		items = items == null ? List.of() : List.copyOf(items);
		imageMessageIds = imageMessageIds == null ? List.of() : List.copyOf(imageMessageIds);
	}
}
