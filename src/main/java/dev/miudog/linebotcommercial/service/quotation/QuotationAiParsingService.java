package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.ai.AiExtractionException;
import dev.miudog.linebotcommercial.service.ai.AiCompletionException;
import dev.miudog.linebotcommercial.service.ai.AiImageInput;
import dev.miudog.linebotcommercial.service.ai.AiJsonCompletionClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 將使用者報價指令交由 AI 正規化，再以資料庫主檔驗證與補齊可信欄位。
 */
@Service
public class QuotationAiParsingService {

	private static final int MAXIMUM_RESPONSE_LENGTH = 2_000_000;
	private static final Logger log = LoggerFactory.getLogger(QuotationAiParsingService.class);

	private final AiJsonCompletionClient completionClient;
	private final QuotationAiPromptService promptService;
	private final QuotationRequestValidationService validationService;
	private final ObjectMapper objectMapper;
	private QuotationModelWorkflow workflow;
	private QuotationProgressService progress = new QuotationProgressService();

	// 方法：共用查詢進度，不改變既有建構介面。
	@org.springframework.beans.factory.annotation.Autowired
	public void configureProgress(QuotationProgressService progress) {
		this.progress = progress;
	}

	// 方法：選用新 workflow，保留既有建構介面供舊呼叫者與回歸測試使用。
	@org.springframework.beans.factory.annotation.Autowired
	public void configureWorkflow(QuotationModelWorkflow workflow) {
		this.workflow = workflow;
	}

	// 方法：舊部署與獨立測試預設維持原單模型路徑。
	public boolean workflowEnabled() {
		return workflow != null && workflow.enabled();
	}

	// 方法：傳遞使用者及草稿事件版本作為持久檢查點隔離邊界。
	public ParseResult parseScoped(String instruction, List<AiImageInput> images, String scheme, QuotationModelWorkflow.Scope scope) {
		if (!workflowEnabled()) return parse(instruction, images, scheme);

		List<AiImageInput> safeImages = images == null ? List.of() : List.copyOf(images);
		try {
			return workflow.parse(instruction, safeImages, scheme, scope,
				raw -> validateResponse(raw, scheme, safeImages.stream().map(AiImageInput::messageId).toList()));
		}
		catch (AiExtractionException exception) {
			throw classifiedCallFailure(exception);
		}
	}

	// 方法：建立報價 AI 解析服務並注入模型、提示詞及主檔驗證元件。
	public QuotationAiParsingService(
		AiJsonCompletionClient completionClient,
		QuotationAiPromptService promptService,
		QuotationRequestValidationService validationService,
		ObjectMapper objectMapper
	) {
		this.completionClient = completionClient;
		this.promptService = promptService;
		this.validationService = validationService;
		this.objectMapper = objectMapper;
	}

	// 方法：判斷目前是否已設定可用的 AI 模型連線。
	public boolean isConfigured() {
		return workflowEnabled() ? workflow.isConfigured() : completionClient.isConfigured();
	}

	// 方法：CSV 直接由程式解析，AI 未設定時也可建立待確認草稿。
	public ParseResult parseCsv(String csv) {
		progress.update("正在讀取CSV並驗證品項，不使用AI。");
		return new QuotationInputCsvService(validationService, objectMapper).parse(csv);
	}

	// 方法：解析報價指令、核對候選圖片，並從資料庫解析固定品項欄位與直接計價結果。
	public ParseResult parse(String instruction, List<AiImageInput> images) {
		return parse(instruction, images, null);
	}

	// 方法：以使用者已指定的報價格式解析報價指令；格式由應用程式鎖定，AI 只負責提取資料。
	public ParseResult parse(String instruction, List<AiImageInput> images, String lockedSchemeCode) {
		if (workflowEnabled()) {
			String isolated = java.util.UUID.randomUUID().toString();
			return parseScoped(instruction, images, lockedSchemeCode, new QuotationModelWorkflow.Scope(isolated, isolated));
		}
		if (!completionClient.isConfigured()) {
			throw new QuotationAiException(
				"AI_NOT_CONFIGURED",
				"AI 尚未設定，請先填寫 AI_API_URL、AI_API_KEY 與 AI_MODEL"
			);
		}

		List<AiImageInput> safeImages = images == null ? List.of() : List.copyOf(images);
		List<String> imageMessageIds = safeImages.stream()
			.map(AiImageInput::messageId)
			.toList();
		QuotationAiPromptService.Prompt prompt = promptService.build(
			instruction,
			imageMessageIds,
			lockedSchemeCode
		);

		String userPrompt = prompt.userPrompt();
		for (int attempt = 1; attempt <= QuotationAiRetry.MAX_ATTEMPTS; attempt++) {
			progress.update("正在辨識報價資料（第 " + attempt + "/5 次嘗試）。");
			String rawJson;
			try {
				rawJson = completionClient.completeJson(
					prompt.apiSystemPrompt(),
					userPrompt,
					safeImages,
					prompt.responseSchema()
				);
			}
			catch (AiExtractionException exception) {
				QuotationAiException failure = classifiedCallFailure(exception);
				if (attempt == QuotationAiRetry.MAX_ATTEMPTS || !QuotationAiRetry.allowed(failure)) throw failure;

				// 日誌：只記錄代碼與次數，不記錄供應商本文或金鑰。
				log.warn("event=quotation_transport_retry attemptCount={} code={}", attempt, failure.code());
				progress.update("AI 暫時失敗，準備第 " + (attempt + 1) + "/5 次嘗試。");
				QuotationAiRetry.pause(attempt);
				continue;
			}

			try {
				progress.update("正在驗證欄位與品項主檔。");
				ParseResult result = validateResponse(rawJson, lockedSchemeCode, prompt.imageMessageIds());

				// 日誌：記錄完成驗證的嘗試次數，不記錄使用者資料。
				log.info("event=quotation_parse_validated attemptCount={} imageCount={}", attempt, safeImages.size());
				return result;
			}
			catch (QuotationAiException exception) {
				// 日誌：只記錄穩定錯誤代碼，供統計首次成功率及修復率。
				log.warn("event=quotation_parse_rejected attemptCount={} code={}", attempt, exception.code());
				if (attempt == QuotationAiRetry.MAX_ATTEMPTS || !QuotationAiRetry.allowed(exception)) throw exception;

				// 序列化：修復資料保持有界；大型或空回應重新提取，不重複堆積歷次輸出。
				userPrompt = prompt.userPrompt() + "\n僅依原始輸入修復下列回應；缺值不可猜測。<REPAIR_DATA>"
					+ objectMapper.writeValueAsString(Map.of(
						"code", exception.code(),
						"error", exception.getMessage(),
						"response", rawJson == null || rawJson.length() > 16000 ? "" : rawJson
					)) + "</REPAIR_DATA>";
				progress.update("格式驗證未通過，準備第 " + (attempt + 1) + "/5 次修復。");
				QuotationAiRetry.pause(attempt);
			}
		}
		throw new IllegalStateException("報價解析重試次數不正確");
	}

	// 方法：所有嘗試共用相同圖片與主檔驗證，修復後不降低標準。
	private ParseResult validateResponse(String rawJson, String lockedSchemeCode, List<String> imageMessageIds) {
		JsonNode root = parseStrictJsonObject(rawJson);
		ensureExactImageAssessments(root, imageMessageIds);
		try {
			QuotationRequestValidationService.ValidatedQuotationRequest request = validationService.validate(
				root,
				lockedSchemeCode
			);
			return new ParseResult(request, rawJson);
		}
		catch (QuotationAdminException exception) {
			throw new QuotationAiException(
				"AI_MASTER_DATA_VALIDATION_FAILED",
				exception.getMessage(),
				exception
			);
		}
	}

	// 方法：只接受單一 JSON 物件，拒絕 Markdown、說明文字及過大的模型輸出。
	private JsonNode parseStrictJsonObject(String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			throw new QuotationAiException("AI_RESPONSE_INVALID", "AI 未回傳報價 JSON");
		}

		if (rawJson.length() > MAXIMUM_RESPONSE_LENGTH) {
			throw new QuotationAiException("AI_RESPONSE_INVALID", "AI 回傳資料過大");
		}

		String normalized = rawJson.trim();
		if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
			throw new QuotationAiException("AI_RESPONSE_INVALID", "AI 回傳內容只能包含一個 JSON 物件");
		}

		try {
			JsonNode root = objectMapper.readTree(normalized);
			if (root == null || !root.isObject()) {
				throw new QuotationAiException("AI_RESPONSE_INVALID", "AI 回傳內容只能包含一個 JSON 物件");
			}

			return root;
		}
		catch (QuotationAiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new QuotationAiException("AI_RESPONSE_INVALID", "AI 回傳的 JSON 格式無法解析", exception);
		}
	}

	// 方法：確認 AI 對每張輸入圖片恰好提供一次評估，且沒有虛構圖片識別碼。
	private void ensureExactImageAssessments(JsonNode root, List<String> expectedMessageIds) {
		JsonNode assessments = root.get("imageAssessments");
		if (assessments == null || !assessments.isArray()) {
			throw new QuotationAiException("AI_IMAGE_RESPONSE_INVALID", "AI 回傳的候選圖片評估格式錯誤");
		}

		Set<String> actualMessageIds = new LinkedHashSet<>();
		for (JsonNode assessment : assessments) {
			JsonNode messageId = assessment.get("messageId");
			if (messageId == null || !messageId.isString() || !actualMessageIds.add(messageId.stringValue().trim())) {
				throw new QuotationAiException("AI_IMAGE_RESPONSE_INVALID", "AI 回傳的候選圖片評估識別碼錯誤或重複");
			}
		}

		Set<String> expected = new LinkedHashSet<>(expectedMessageIds);
		if (!actualMessageIds.equals(expected) || assessments.size() != expectedMessageIds.size()) {
			throw new QuotationAiException(
				"AI_IMAGE_RESPONSE_INVALID",
				"AI 必須完整回傳所有候選圖片評估，且不可加入不存在的圖片"
			);
		}
	}

	// 方法：把 AI HTTP、連線、逾時與回應格式失敗分成可行動的穩定代碼。
	static QuotationAiException classifiedCallFailure(AiExtractionException exception) {
		if (exception instanceof AiCompletionException completion) {
			String detail = switch (completion.code()) {
				case "AI_OUTPUT_TRUNCATED" -> "AI 輸出超過 token 上限，請拆分品項或調整輸出預算";
				case "AI_REFUSED" -> "AI 無法處理本次資料，請修改輸入或使用 CSV";
				default -> "AI 回傳格式不符報價規格";
			};
			return new QuotationAiException(completion.code(), detail, exception);
		}
		String message = exception.getMessage() == null ? "" : exception.getMessage();
		if (message.contains("尚未設定")) return new QuotationAiException("AI_NOT_CONFIGURED", "AI 服務尚未設定", exception);

		if (causedBy(exception, HttpTimeoutException.class)) return new QuotationAiException("AI_TIMEOUT", "AI 解析逾時", exception);

		if (message.matches(".*狀態碼 (401|403).*")) return new QuotationAiException("AI_AUTH_FAILED", "AI API 驗證失敗", exception);

		if (message.matches(".*狀態碼 429.*")) return new QuotationAiException("AI_RATE_LIMITED", "AI 服務目前請求過多", exception);

		if (message.matches(".*狀態碼 4\\d\\d.*")) return new QuotationAiException("AI_REQUEST_REJECTED", "AI 服務拒絕報價解析請求", exception);

		if (message.matches(".*狀態碼 5\\d\\d.*")
			|| causedBy(exception, ConnectException.class)
			|| causedBy(exception, IOException.class)) return new QuotationAiException("AI_SERVICE_UNAVAILABLE", "AI 服務暫時無法連線", exception);

		if (message.contains("模型回應") || message.contains("JSON")) return new QuotationAiException("AI_RESPONSE_INVALID", "AI 回傳格式不符報價規格", exception);

		return new QuotationAiException("AI_SERVICE_UNAVAILABLE", "AI 服務呼叫失敗", exception);
	}

	// 方法：沿受控例外鏈查找指定原因類型，不解析或輸出外部錯誤內容。
	private static boolean causedBy(Throwable source, Class<? extends Throwable> type) {
		Throwable current = source;
		while (current != null) {
			if (type.isInstance(current)) return true;

			current = current.getCause();
		}
		return false;
	}

	public record ParseResult(
		QuotationRequestValidationService.ValidatedQuotationRequest request,
		String rawJson
	) {}
}
