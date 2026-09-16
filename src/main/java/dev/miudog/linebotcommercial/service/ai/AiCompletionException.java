package dev.miudog.linebotcommercial.service.ai;

/** 將模型終止狀態轉成受控代碼，不洩漏模型回應內容。 */
public class AiCompletionException extends AiExtractionException {

	private final String code;

	// 方法：保存可分類的完成錯誤，避免呼叫端猜測外部錯誤文字。
	public AiCompletionException(String code) {
		super(code, (Throwable) null);
		this.code = code;
	}

	// 方法：取得安全且穩定的錯誤代碼。
	public String code() {
		return code;
	}
}
