package dev.myudog.assetsmanagerlinebot.observability;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 依環境設定的每百萬 token 費率，以 BigDecimal 在本機估算單次 AI 成本。 */
@Component
public class AiUsageCostCalculator {

	private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

	private final String currency;
	private final BigDecimal inputRatePerMillion;
	private final BigDecimal cachedInputRatePerMillion;
	private final BigDecimal outputRatePerMillion;

	// 方法：解析環境費率；空白代表該模型費率尚未設定，不以零元代替。
	public AiUsageCostCalculator(
		@Value("${app.ai.pricing.currency:USD}") String currency,
		@Value("${app.ai.pricing.input-per-million:}") String inputRatePerMillion,
		@Value("${app.ai.pricing.cached-input-per-million:}") String cachedInputRatePerMillion,
		@Value("${app.ai.pricing.output-per-million:}") String outputRatePerMillion
	) {
		this.currency = currency == null || currency.isBlank() ? "USD" : currency.trim();
		this.inputRatePerMillion = parseOptionalRate(inputRatePerMillion);
		this.cachedInputRatePerMillion = parseOptionalRate(cachedInputRatePerMillion);
		this.outputRatePerMillion = parseOptionalRate(outputRatePerMillion);
	}

	// 方法：套用核准公式，並將當次使用的費率一併保存為不可變稽核快照。
	public AiUsageAudit calculate(String model, long inputTokens, long cachedInputTokens, long outputTokens) {
		validateTokens(inputTokens, cachedInputTokens, outputTokens);
		boolean configured = inputRatePerMillion != null
			&& cachedInputRatePerMillion != null
			&& outputRatePerMillion != null;
		BigDecimal totalCost = configured
			? tokenCost(inputTokens, inputRatePerMillion)
				.add(tokenCost(cachedInputTokens, cachedInputRatePerMillion))
				.add(tokenCost(outputTokens, outputRatePerMillion))
			: null;
		return new AiUsageAudit(
			safeModel(model),
			inputTokens,
			cachedInputTokens,
			outputTokens,
			inputRatePerMillion,
			cachedInputRatePerMillion,
			outputRatePerMillion,
			totalCost,
			currency,
			configured ? AiPriceStatus.CONFIGURED : AiPriceStatus.UNCONFIGURED,
			AiUsageStatus.AVAILABLE
		);
	}

	// 方法：依 nullable token 建立部分或不可用的成本稽核，不以零代替未知值。
	public AiUsageAudit incomplete(String model, Long inputTokens, Long cachedInputTokens, Long outputTokens) {
		validateOptionalToken(inputTokens);
		validateOptionalToken(cachedInputTokens);
		validateOptionalToken(outputTokens);
		AiUsageStatus usageStatus = inputTokens == null && cachedInputTokens == null && outputTokens == null
			? AiUsageStatus.UNAVAILABLE
			: AiUsageStatus.PARTIAL;
		return new AiUsageAudit(
			safeModel(model),
			inputTokens,
			cachedInputTokens,
			outputTokens,
			inputRatePerMillion,
			cachedInputRatePerMillion,
			outputRatePerMillion,
			null,
			currency,
			priceStatus(),
			usageStatus
		);
	}

	// 方法：建立供應商未回傳 usage 時的明確稽核結果，不臆測 token 數。
	public AiUsageAudit unavailable(String model) {
		return incomplete(model, null, null, null);
	}

	// 方法：將單類 token 數換算為每百萬 token 的成本。
	private static BigDecimal tokenCost(long tokens, BigDecimal ratePerMillion) {
		return BigDecimal.valueOf(tokens).multiply(ratePerMillion).divide(ONE_MILLION);
	}

	// 方法：驗證供應商 usage 不含負數，避免錯誤資料污染成本紀錄。
	private static void validateTokens(long inputTokens, long cachedInputTokens, long outputTokens) {
		if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0) {
			throw new IllegalArgumentException("AI token 數不可為負數");
		}
	}

	// 方法：只驗證供應商實際提供的 token，未知值允許保留為空。
	private static void validateOptionalToken(Long token) {
		if (token != null && token < 0) throw new IllegalArgumentException("AI token 數不可為負數");
	}

	// 方法：判斷三種本地費率是否完整設定。
	private AiPriceStatus priceStatus() {
		return inputRatePerMillion != null && cachedInputRatePerMillion != null && outputRatePerMillion != null
			? AiPriceStatus.CONFIGURED
			: AiPriceStatus.UNCONFIGURED;
	}

	// 方法：將空白費率保留為未設定，並拒絕負費率。
	private static BigDecimal parseOptionalRate(String rawRate) {
		if (rawRate == null || rawRate.isBlank()) return null;

		BigDecimal rate = new BigDecimal(rawRate.trim());
		if (rate.signum() < 0) throw new IllegalArgumentException("AI token 費率不可為負數");

		return rate;
	}

	// 方法：保留可查詢的模型名稱，同時避免空字串破壞稽核欄位。
	private static String safeModel(String model) {
		return model == null || model.isBlank() ? "unknown" : model.trim();
	}
}
