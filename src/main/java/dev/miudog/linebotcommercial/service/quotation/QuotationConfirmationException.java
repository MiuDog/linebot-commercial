package dev.miudog.linebotcommercial.service.quotation;

/**
 * 正式確認與交易配號不符合規則時的可預期錯誤。
 */
public class QuotationConfirmationException extends RuntimeException {

	private final String code;

	// 方法：建立可顯示給流程層處理的確認錯誤。
	public QuotationConfirmationException(String message) {
		this("QUOTATION_CONFIRMATION_FAILED", message);
	}

	// 方法：以穩定代碼區分設定缺漏與草稿確認失敗。
	public QuotationConfirmationException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：取得可提供給使用者的錯誤分類。
	public String code() {
		return code;
	}
}
