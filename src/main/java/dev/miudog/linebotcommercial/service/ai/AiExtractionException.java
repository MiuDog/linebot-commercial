package dev.miudog.linebotcommercial.service.ai;

import java.util.List;

/**
 * 【職責】表達「AI 資料提取失敗」這件事，並帶出可以直接講給使用者聽的原因。
 *
 * <p>失敗分兩種，兩種都用這個例外表達，差別只在 {@link #missingFields}：
 * <ul>
 *   <li>呼叫或解析失敗（連線錯誤、回應不是 JSON）→ missingFields 為空</li>
 *   <li>回應解析成功但缺少必要欄位 → missingFields 列出缺哪些</li>
 * </ul>
 * 需求指定「若無法提出特定資料則報錯」，第二種就是那個情境。
 *
 * <p><b>錯誤回傳鏈：</b>
 * {@code AiExtractionService → AiExtractionException
 * → CommandService.replyQuotation → userMessage → LineStorageService.replyText}。
 */
public class AiExtractionException extends RuntimeException {

	private final List<String> missingFields;

	/**
	 * 建立一個非欄位缺漏的失敗（呼叫失敗、回應格式錯誤等）。
	 *
	 * @param message 失敗原因
	 * @param cause   底層例外，可為 null
	 */
	// 方法：初始化 AiExtractionException。
	public AiExtractionException(String message, Throwable cause) {
		super(message, cause);
		this.missingFields = List.of();
	}

	/**
	 * 建立一個「必要欄位缺漏」的失敗。
	 *
	 * @param message       失敗原因
	 * @param missingFields 缺少的欄位名稱
	 */
	// 方法：初始化 AiExtractionException。
	public AiExtractionException(String message, List<String> missingFields) {
		super(message);
		this.missingFields = List.copyOf(missingFields);
	}

	/**
	 * 缺少的必要欄位。
	 *
	 * @return 欄位名稱清單；非欄位缺漏的失敗回傳空集合
	 */
	// 方法：執行 missingFields 方法的處理流程。
	public List<String> missingFields() {
		return missingFields;
	}

	/**
	 * 組出可直接回覆給群組的說明文字。
	 *
	 * @return 使用者看得懂的錯誤描述
	 */
	// 方法：執行 userMessage 方法的處理流程。
	public String userMessage() {
		if (missingFields.isEmpty()) return "資料提取失敗：" + getMessage();

		return "無法從圖片辨識出必要資料：" + String.join("、", missingFields) + "\n請確認圖片清晰或改用其他角度重拍。";
	}
}
