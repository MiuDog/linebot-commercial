package dev.miudog.linebotcommercial.service.quotation;

public class QuotationLineWorkflowException extends RuntimeException {

	private final String code;

	// 方法：建立可安全回覆使用者的報價流程例外。
	public QuotationLineWorkflowException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：取得穩定錯誤代碼。
	public String code() {
		return code;
	}
}
