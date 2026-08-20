package dev.myudog.assetsmanagerlinebot.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 從 AI 供應商回應中擷取 usage，且只寫入允許清單內的成本欄位。 */
@Component
public class AiUsageAuditService {

	private static final Logger log = LoggerFactory.getLogger(AiUsageAuditService.class);

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AiUsageCostCalculator calculator;

	// 方法：注入本地成本計算器，AI 回應原文不會離開此服務。
	public AiUsageAuditService(AiUsageCostCalculator calculator) {
		this.calculator = calculator;
	}

	// 方法：解析成功回應的 usage，並寫入不含本文的單次 AI 嘗試稽核。
	public AiUsageAudit auditSuccess(String responseBody, String configuredModel, String operation) {
		try {
			// 使用 Jackson 只讀取模型與 usage，絕不記錄 prompt、content 或完整回應。
			JsonNode root = objectMapper.readTree(responseBody);
			String actualModel = textOrDefault(root.path("model"), configuredModel);
			JsonNode usage = root.path("usage");
			if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
				return logAttempt(
					calculator.unavailable(actualModel),
					operation,
					AiAttemptStatus.SUCCESS,
					"MissingUsage"
				);
			}

			Long inputTokens = optionalNonNegativeLong(usage, "prompt_tokens", "input_tokens");
			Long cachedInputTokens = optionalNonNegativeLong(
				firstObject(usage, "prompt_tokens_details", "input_tokens_details"),
				"cached_tokens"
			);
			Long outputTokens = optionalNonNegativeLong(usage, "completion_tokens", "output_tokens");
			AiUsageAudit audit = inputTokens != null && cachedInputTokens != null && outputTokens != null
				? calculator.calculate(actualModel, inputTokens, cachedInputTokens, outputTokens)
				: calculator.incomplete(actualModel, inputTokens, cachedInputTokens, outputTokens);
			return logAttempt(audit, operation, AiAttemptStatus.SUCCESS, null);
		}
		catch (RuntimeException exception) {
			return logAttempt(
				calculator.unavailable(configuredModel),
				operation,
				AiAttemptStatus.SUCCESS,
				exception.getClass().getSimpleName()
			);
		}
	}

	// 方法：記錄沒有成功回應本文的 AI 嘗試，所有未知 token 明確保留為空。
	public AiUsageAudit auditFailure(
		String configuredModel,
		String operation,
		AiAttemptStatus status,
		String errorType
	) {
		return logAttempt(calculator.unavailable(configuredModel), operation, status, safeLabel(errorType));
	}

	// 方法：寫入穩定且安全的共用稽核事件，供同一 correlation ID 查詢完整嘗試結果。
	private AiUsageAudit logAttempt(
		AiUsageAudit audit,
		String operation,
		AiAttemptStatus status,
		String errorType
	) {
		var event = status == AiAttemptStatus.SUCCESS ? log.atInfo() : log.atWarn();
		event
			.addKeyValue("event", "ai_attempt_audited")
			.addKeyValue("requestId", currentRequestId())
			.addKeyValue("operation", safeLabel(operation))
			.addKeyValue("model", audit.model())
			.addKeyValue("status", status)
			.addKeyValue("usageStatus", audit.usageStatus())
			.addKeyValue("inputTokens", audit.inputTokens())
			.addKeyValue("cachedInputTokens", audit.cachedInputTokens())
			.addKeyValue("outputTokens", audit.outputTokens())
			.addKeyValue("inputRatePerMillion", audit.inputRatePerMillion())
			.addKeyValue("cachedInputRatePerMillion", audit.cachedInputRatePerMillion())
			.addKeyValue("outputRatePerMillion", audit.outputRatePerMillion())
			.addKeyValue("totalCost", audit.totalCost())
			.addKeyValue("currency", audit.currency())
			.addKeyValue("priceStatus", audit.priceStatus())
			.addKeyValue("errorType", safeNullableLabel(errorType))
			.log(
				"event=ai_attempt_audited requestId={} operation={} model={} status={} usageStatus={} "
					+ "inputTokens={} cachedInputTokens={} outputTokens={} totalCost={} currency={} "
					+ "priceStatus={} errorType={}",
				currentRequestId(),
				safeLabel(operation),
				audit.model(),
				status,
				audit.usageStatus(),
				audit.inputTokens(),
				audit.cachedInputTokens(),
				audit.outputTokens(),
				audit.totalCost(),
				audit.currency(),
				audit.priceStatus(),
				safeNullableLabel(errorType)
			);
		return audit;
	}

	// 方法：依欄位優先序讀取非負 token；不存在時保留為空。
	private static Long optionalNonNegativeLong(JsonNode parent, String... fields) {
		if (parent == null || parent.isMissingNode() || parent.isNull()) return null;

		JsonNode node = null;
		for (String field : fields) {
			JsonNode candidate = parent.path(field);
			if (!candidate.isMissingNode() && !candidate.isNull()) {
				node = candidate;
				break;
			}
		}
		if (node == null) return null;

		long value = node.asLong(-1);
		if (value < 0) throw new IllegalArgumentException("AI usage token 格式無效");

		return value;
	}

	// 方法：依欄位優先序取得 usage 詳細資訊物件。
	private static JsonNode firstObject(JsonNode parent, String... fields) {
		for (String field : fields) {
			JsonNode candidate = parent.path(field);
			if (candidate.isObject()) return candidate;
		}
		return null;
	}

	// 方法：優先使用供應商實際模型版本，缺漏時回退至請求設定模型。
	private static String textOrDefault(JsonNode node, String fallback) {
		return node != null && node.isString() && !node.stringValue().isBlank()
			? node.stringValue()
			: fallback;
	}

	// 方法：取得同一 HTTP 或背景流程的 correlation ID。
	private static String currentRequestId() {
		String requestId = MDC.get("requestId");
		return requestId == null ? "background" : requestId;
	}

	// 方法：將操作與錯誤類型限制為程式定義的低基數安全標籤。
	private static String safeLabel(String value) {
		if (value == null || value.isBlank()) return "unknown";

		String firstToken = value.trim().split("[^A-Za-z0-9_.-]", 2)[0];
		return firstToken.matches("[A-Za-z0-9_.-]{1,64}") ? firstToken : "unknown";
	}

	// 方法：保留沒有錯誤的空值，避免將成功嘗試誤標為 unknown 錯誤。
	private static String safeNullableLabel(String value) {
		return value == null ? null : safeLabel(value);
	}
}
