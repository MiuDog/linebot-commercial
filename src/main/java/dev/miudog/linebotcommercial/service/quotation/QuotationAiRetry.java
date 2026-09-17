package dev.miudog.linebotcommercial.service.quotation;

import java.util.Set;

/** 共用重試判斷；總嘗試包含首次呼叫，永久設定錯誤不重送。 */
final class QuotationAiRetry {
	static final int MAX_ATTEMPTS = 5;
	private static final Set<String> RECOVERABLE = Set.of(
		"AI_RESPONSE_INVALID", "AI_IMAGE_RESPONSE_INVALID", "AI_MASTER_DATA_VALIDATION_FAILED",
		"AI_TIMEOUT", "AI_RATE_LIMITED", "AI_SERVICE_UNAVAILABLE"
	);

	// 方法：工具類不建立實例。
	private QuotationAiRetry() {}

	// 方法：只有明確可恢復錯誤可重試；中斷、拒絕、截斷及權限問題立即停止。
	static boolean allowed(QuotationAiException failure) {
		return !Thread.currentThread().isInterrupted() && RECOVERABLE.contains(failure.code());
	}

	// 方法：逐次延遲避免連續撞擊供應商，取消時保留中斷旗標。
	static void pause(int attempt) {
		try {
			Thread.sleep(Math.min(1600L, 200L << Math.min(attempt - 1, 3)));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new QuotationAiException("AI_TIMEOUT", "AI 重試已取消", exception);
		}
	}
}
