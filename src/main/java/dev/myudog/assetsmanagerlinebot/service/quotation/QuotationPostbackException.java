package dev.myudog.assetsmanagerlinebot.service.quotation;

public class QuotationPostbackException extends RuntimeException {

	private final String code;

	// 方法：建立不暴露簽章細節的 postback 驗證錯誤。
	public QuotationPostbackException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：取得上層路由可穩定判斷的錯誤代碼。
	public String code() {
		return code;
	}
}
