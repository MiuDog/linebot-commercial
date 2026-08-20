package dev.miudog.linebotcommercial.service.quotation;

public class QuotationConversationException extends RuntimeException {

	private final String code;

	// 方法：建立可供上層穩定判斷的對話狀態錯誤。
	public QuotationConversationException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：取得固定錯誤代碼。
	public String code() {
		return code;
	}
}
