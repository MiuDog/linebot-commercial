package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AiUsageAuditServiceTest {

	// 驗證 OpenAI usage 會轉為本地成本稽核，且不記錄回應內文。
	@Test
	void parsesUsageAndLogsOneSafeAuditEvent(CapturedOutput output) {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "2", "0.5", "8");
		AiUsageAuditService service = new AiUsageAuditService(calculator);
		String response = """
			{"model":"gpt-test-2026","usage":{"prompt_tokens":1000,"completion_tokens":500,
			"prompt_tokens_details":{"cached_tokens":250}},"secret":"message-body-must-not-leak"}
			""";

		MDC.put("requestId", "corr-ai-success");
		AiUsageAudit audit;
		try {
			audit = service.auditSuccess(response, "configured-model", "quotation_parse");
		}
		finally {
			MDC.remove("requestId");
		}

		assertThat(audit.model()).isEqualTo("gpt-test-2026");
		assertThat(audit.inputTokens()).isEqualTo(1000);
		assertThat(audit.cachedInputTokens()).isEqualTo(250);
		assertThat(audit.outputTokens()).isEqualTo(500);
		assertThat(audit.totalCost()).isEqualByComparingTo("0.006125");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("requestId=corr-ai-success")
			.contains("operation=quotation_parse")
			.contains("status=SUCCESS")
			.contains("model=gpt-test-2026")
			.contains("priceStatus=CONFIGURED")
			.doesNotContain("message-body-must-not-leak");
	}

	// 驗證供應商未回傳 usage 時只記錄不可用狀態，不中斷主要 AI 回應。
	@Test
	void toleratesMissingUsageWithoutLoggingResponseBody(CapturedOutput output) {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "2", "0.5", "8");
		AiUsageAuditService service = new AiUsageAuditService(calculator);

		AiUsageAudit audit = service.auditSuccess(
			"{\"model\":\"gpt-test\",\"secret\":\"full-private-message\"}",
			"configured-model",
			"quotation_parse"
		);

		assertThat(audit.usageAvailable()).isFalse();
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("usageStatus=UNAVAILABLE")
			.contains("inputTokens=null")
			.contains("cachedInputTokens=null")
			.contains("outputTokens=null")
			.doesNotContain("full-private-message");
	}

	// 驗證供應商只回傳部分 token 時保留未知值，不用零推測成本。
	@Test
	void preservesUnknownTokensAsNullAndMarksPartialUsage(CapturedOutput output) {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "2", "0.5", "8");
		AiUsageAuditService service = new AiUsageAuditService(calculator);

		AiUsageAudit audit = service.auditSuccess(
			"{\"model\":\"gpt-partial\",\"usage\":{\"input_tokens\":120}}",
			"configured-model",
			"voice_transcription"
		);

		assertThat(audit.inputTokens()).isEqualTo(120L);
		assertThat(audit.cachedInputTokens()).isNull();
		assertThat(audit.outputTokens()).isNull();
		assertThat(audit.totalCost()).isNull();
		assertThat(audit.usageStatus()).isEqualTo(AiUsageStatus.PARTIAL);
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("operation=voice_transcription")
			.contains("usageStatus=PARTIAL")
			.contains("cachedInputTokens=null")
			.contains("outputTokens=null");
	}

	// 驗證失敗嘗試仍以同一關聯碼留下模型、狀態、未知 token 與價格狀態。
	@Test
	void auditsFailedAttemptWithoutSensitiveErrorText(CapturedOutput output) {
		AiUsageCostCalculator calculator = new AiUsageCostCalculator("USD", "", "", "");
		AiUsageAuditService service = new AiUsageAuditService(calculator);
		MDC.put("requestId", "corr-ai-failed");
		try {
			service.auditFailure(
				"gpt-failed",
				"quotation_parse",
				AiAttemptStatus.HTTP_ERROR,
				"HttpStatus500 Bearer secret-private"
			);
		}
		finally {
			MDC.remove("requestId");
		}

		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("requestId=corr-ai-failed")
			.contains("model=gpt-failed")
			.contains("status=HTTP_ERROR")
			.contains("inputTokens=null")
			.contains("priceStatus=UNCONFIGURED")
			.contains("errorType=HttpStatus500")
			.doesNotContain("secret-private")
			.doesNotContain("Bearer");
	}
}
