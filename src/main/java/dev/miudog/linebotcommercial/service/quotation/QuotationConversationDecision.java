package dev.miudog.linebotcommercial.service.quotation;

import java.util.List;

public record QuotationConversationDecision(
	QuotationDraftSnapshot draft,
	List<String> missingBaseFields,
	List<QuotationMissingItemFields> missingItemFields,
	QuotationNextAction nextAction
) {

	// 方法：固定缺漏清單，確保訊息產生器取得一致內容。
	public QuotationConversationDecision {
		missingBaseFields = missingBaseFields == null ? List.of() : List.copyOf(missingBaseFields);
		missingItemFields = missingItemFields == null ? List.of() : List.copyOf(missingItemFields);
	}
}
