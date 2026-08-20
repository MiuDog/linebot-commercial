package dev.miudog.linebotcommercial.service.quotation;

import java.util.List;

public record QuotationMissingItemFields(String itemKey, List<String> fields) {

	// 方法：固定品項缺漏清單，維持回覆順序且避免外部竄改。
	public QuotationMissingItemFields {
		fields = fields == null ? List.of() : List.copyOf(fields);
	}
}
