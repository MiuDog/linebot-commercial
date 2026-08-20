package dev.miudog.linebotcommercial.service.quotation;

/**
 * 正式確認與交易配號不符合規則時的可預期錯誤。
 */
public class QuotationConfirmationException extends RuntimeException {

	// 方法：建立可顯示給流程層處理的確認錯誤。
	public QuotationConfirmationException(String message) {
		super(message);
	}
}
