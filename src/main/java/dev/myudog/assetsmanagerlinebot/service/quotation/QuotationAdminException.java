package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 表示管理 API 可安全回傳給使用者的資料驗證或查無資料錯誤。
 */
public class QuotationAdminException extends RuntimeException {

	private final String code;

	// 方法：建立具有穩定錯誤代碼的管理功能例外。
	public QuotationAdminException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：保留內部根因供日誌診斷，同時維持穩定的對外錯誤代碼與訊息。
	public QuotationAdminException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	// 方法：取得 API 使用的機器可讀錯誤代碼。
	public String code() {
		return code;
	}
}
