package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 正式報價產生流程失敗。
 */
public class QuotationGenerationException extends RuntimeException {

	private final String code;

	// 方法：建立具有穩定錯誤代碼的產生例外。
	public QuotationGenerationException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	// 方法：取得供狀態紀錄使用的錯誤代碼。
	public String code() {
		return code;
	}
}
