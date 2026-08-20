package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 表示報價 AI 的輸入、模型回應或服務設定無法安全繼續處理。
 */
public class QuotationAiException extends RuntimeException {

	private final String code;

	// 方法：建立可安全顯示於本機管理頁的報價 AI 錯誤。
	public QuotationAiException(String message) {
		this("AI_PROCESSING_FAILED", message, null);
	}

	// 方法：建立保留底層原因但不直接外洩其內容的報價 AI 錯誤。
	public QuotationAiException(String message, Throwable cause) {
		this("AI_PROCESSING_FAILED", message, cause);
	}

	// 方法：建立具有穩定代碼與安全說明的報價 AI 錯誤。
	public QuotationAiException(String code, String message) {
		this(code, message, null);
	}

	// 方法：建立可追查根因且可依穩定代碼分類的報價 AI 錯誤。
	public QuotationAiException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code == null || !code.matches("[A-Z0-9_]{1,80}")
			? "AI_PROCESSING_FAILED"
			: code;
	}

	// 方法：取得供 LINE 回覆與日誌篩選使用的穩定錯誤代碼。
	public String code() {
		return code;
	}
}
