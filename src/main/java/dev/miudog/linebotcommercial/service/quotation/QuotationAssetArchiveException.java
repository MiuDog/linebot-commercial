package dev.miudog.linebotcommercial.service.quotation;

/**
 * 正式報價圖片歸檔失敗，代碼可安全提供給後續狀態紀錄。
 */
public class QuotationAssetArchiveException extends RuntimeException {

	private final String code;

	// 方法：建立不包含內部路徑的圖片歸檔錯誤。
	public QuotationAssetArchiveException(String code, String message) {
		super(message);
		this.code = code;
	}

	// 方法：保留原始例外供伺服器診斷。
	public QuotationAssetArchiveException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	// 方法：取得穩定錯誤代碼。
	public String code() {
		return code;
	}
}
