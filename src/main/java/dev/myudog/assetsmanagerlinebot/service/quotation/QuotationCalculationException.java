package dev.myudog.assetsmanagerlinebot.service.quotation;

/**
 * 報價列輸入違反確定性計價規則時的領域錯誤。
 */
public class QuotationCalculationException extends RuntimeException {

	// 方法：建立可安全呈現的報價計價錯誤。
	public QuotationCalculationException(String message) {
		super(message);
	}
}
