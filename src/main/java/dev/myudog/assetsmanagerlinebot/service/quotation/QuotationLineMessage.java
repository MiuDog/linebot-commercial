package dev.myudog.assetsmanagerlinebot.service.quotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record QuotationLineMessage(Map<String, Object> payload) {

	// 方法：固定最外層 LINE 訊息資料，避免送出前被呼叫端改寫。
	public QuotationLineMessage {
		payload = payload == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
