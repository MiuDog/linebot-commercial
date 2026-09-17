package dev.miudog.linebotcommercial.service.quotation;

import org.springframework.dao.DataAccessException;

/**
 * 將 LINE 報價流程例外轉成可行動、不洩漏內部資訊的穩定錯誤回覆。
 */
public final class QuotationLineFailureMessageResolver {

	// 方法：工具類不允許建立實例。
	private QuotationLineFailureMessageResolver() {}

	// 方法：依例外類型與穩定代碼選擇使用者可以採取的下一步。
	public static Failure resolve(RuntimeException exception, Operation operation) {
		if (exception instanceof QuotationAiException aiException) return aiFailure(aiException);

		if (exception instanceof QuotationLineWorkflowException workflowException) return new Failure(workflowException.code(), withCode(workflowException.getMessage(), workflowException.code()));

		if (exception instanceof QuotationPostbackException postbackException) {
			return new Failure(
				postbackException.code(),
				withCode("這個操作已失效，請使用最新的報價預覽按鈕。", postbackException.code())
			);
		}

		if (exception instanceof DataAccessException) {
			return new Failure(
				"QUOTATION_DATABASE_BUSY",
				withCode("報價資料庫目前忙碌，資料尚未遺失，請稍後重試。", "QUOTATION_DATABASE_BUSY")
			);
		}

		String code = switch (operation) {
			case IMAGE -> "QUOTATION_IMAGE_INTERNAL_ERROR";
			case POSTBACK -> "QUOTATION_ACTION_INTERNAL_ERROR";
			case TEXT -> "QUOTATION_INTERNAL_ERROR";
		};
		String message = switch (operation) {
			case IMAGE -> "報價圖片處理發生未預期錯誤，請重新上傳或將錯誤代碼提供給管理員。";
			case POSTBACK -> "報價操作發生未預期錯誤，請重新開啟最新預覽或將錯誤代碼提供給管理員。";
			case TEXT -> "報價處理發生未預期錯誤，請將錯誤代碼提供給管理員。";
		};
		return new Failure(code, withCode(message, code));
	}

	// 方法：將 AI 錯誤分成設定、逾時、驗證、限流、格式與主檔問題。
	private static Failure aiFailure(QuotationAiException exception) {
		String code = exception.code();
		String message = switch (code) {
			case "AI_WORKFLOW_CONFIG" -> "報價 workflow 設定不完整，請管理員檢查角色模型、端點、金鑰與配額。";
			case "AI_WORKFLOW_BUDGET" -> "本輪 AI 解析已達預算或供應商缺少用量資料，請拆分輸入或使用 CSV。";
			case "AI_REFUSED" -> "AI 無法處理本次資料，請修改輸入或使用 CSV。";
			case "AI_OUTPUT_TRUNCATED" -> "AI 輸出被截斷，請拆分輸入或請管理員調整輸出預算。";
			case "AI_NOT_CONFIGURED" -> "報價 AI 尚未設定，請管理員檢查 AI_API_URL、AI_API_KEY 與 AI_MODEL。";
			case "AI_TIMEOUT" -> "AI 解析超過等待時間，請稍後重送同一段報價指令。";
			case "AI_AUTH_FAILED" -> "AI API 驗證失敗（401／403），已停止重試。請管理員檢查 API 金鑰、端點及模型權限；重送相同設定無法修正。";
			case "AI_RATE_LIMITED" -> "AI 服務目前請求過多，請稍後再試。";
			case "AI_REQUEST_REJECTED" -> "AI 服務拒絕請求，請管理員檢查 API 網址與模型名稱。";
			case "AI_SERVICE_UNAVAILABLE" -> "AI 服務暫時無法連線，請稍後重送報價指令。";
			case "AI_RESPONSE_INVALID" -> "AI 回傳格式不完整，自動修復未成功（每輪最多5次，仍受預算與期限限制）。請改用CSV或縮小本次輸入。";
			case "AI_IMAGE_RESPONSE_INVALID" -> "AI 沒有完整評估候選圖片，請重新上傳圖片。";
			case "AI_MASTER_DATA_VALIDATION_FAILED" -> "AI 辨識結果無法套用品項主檔：" + safeDetail(exception.getMessage())
				+ "\n請改用品項主檔中的名稱，或補充報價格式與數量。";
			default -> "報價 AI 處理失敗，請重送一次；若仍失敗請將錯誤代碼提供給管理員。";
		};
		return new Failure(code, withCode(message, code));
	}

	// 方法：限制只顯示程式內部驗證建立的短說明，不允許換行或過長內容。
	private static String safeDetail(String detail) {
		if (detail == null || detail.isBlank()) return "資料不符主檔規則";

		String safe = detail.replace('\r', ' ').replace('\n', ' ').trim();
		return safe.length() <= 300 ? safe : safe.substring(0, 300);
	}

	// 方法：每種 LINE 錯誤回覆都附上可供查詢日誌的穩定代碼。
	private static String withCode(String message, String code) {
		return message + "\n錯誤代碼：" + code;
	}

	public enum Operation {
		TEXT,
		IMAGE,
		POSTBACK
	}

	public record Failure(String code, String message) {}
}
