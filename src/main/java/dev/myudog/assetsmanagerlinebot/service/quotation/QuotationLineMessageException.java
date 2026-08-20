package dev.myudog.assetsmanagerlinebot.service.quotation;

public class QuotationLineMessageException extends RuntimeException {

	private final String code;

	// 方法：建立可由上層轉換為安全 LINE 提示的訊息錯誤。
	public QuotationLineMessageException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：取得固定訊息錯誤代碼。
	public String code() {
		return code;
	}
}
