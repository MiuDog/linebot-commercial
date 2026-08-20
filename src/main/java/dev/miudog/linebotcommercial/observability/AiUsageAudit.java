package dev.miudog.linebotcommercial.observability;

import java.math.BigDecimal;

/** 單次 AI 呼叫的 token、費率快照與本地估算成本。 */
public record AiUsageAudit(
	String model,
	Long inputTokens,
	Long cachedInputTokens,
	Long outputTokens,
	BigDecimal inputRatePerMillion,
	BigDecimal cachedInputRatePerMillion,
	BigDecimal outputRatePerMillion,
	BigDecimal totalCost,
	String currency,
	AiPriceStatus priceStatus,
	AiUsageStatus usageStatus
) {

	// 方法：提供既有呼叫端所需的完整 usage 判斷。
	public boolean usageAvailable() {
		return usageStatus == AiUsageStatus.AVAILABLE;
	}
}
