package dev.miudog.linebotcommercial.service.ai;

import dev.miudog.linebotcommercial.observability.AiUsageAuditService;
import dev.miudog.linebotcommercial.observability.AiUsageCostCalculator;
import dev.miudog.linebotcommercial.observability.AiAttemptStatus;
import dev.miudog.linebotcommercial.observability.NetworkObservationLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 【職責】把規格圖／資訊圖送給 AI 模型，並把回應整理成結構化欄位。
 *
 * <p>只做兩件事：呼叫模型、處理結果。它不知道報價公式，也不知道 PDF 長什麼樣，
 * 那些屬於 {@code QuotationCalculator} 與 {@code QuotationPdfService}。
 *
 * <p><b>設定全部留空，需由使用者填入：</b>
 * <pre>
 *   AI_API_URL         OpenAI 相容 API 的共同基底網址
 *   AI_API_KEY         金鑰
 *   AI_MODEL           模型名稱
 *   AI_REQUIRED_FIELDS 必要欄位，以逗號分隔；缺任何一項就報錯
 * </pre>
 * 請求格式採用 OpenAI 相容的 chat completions（圖片以 base64 data URL 內嵌），
 * 這是目前相容性最廣的一種；若最終選用的服務格式不同，只需要改
 * {@link #buildRequestBody} 與 {@link #extractContent} 兩個方法。
 *
 * <p><b>事件呼叫鏈：</b>
 * {@code #報價 → CommandService.replyQuotation → AssetService.contentOf
 * → QuotationService.quote → extract → callModel
 * → extractContent → parseJsonObject → validateRequiredFields}。
 *
 * <p>本服務只負責模型呼叫與資料品質邊界，不讀取資產檔案、不計算價格，
 * 也不決定 LINE 回覆文案。失敗統一轉成 {@link AiExtractionException}。
 */
@Service
public class AiExtractionService implements AiJsonCompletionClient {

	private static final Logger log = LoggerFactory.getLogger(AiExtractionService.class);
	private static final int DEFAULT_TIMEOUT_SECONDS = 60;
	private static final int MAXIMUM_COMPLETION_IMAGES = 20;
	private static final int MAXIMUM_IMAGE_BYTES = 10 * 1024 * 1024;
	private static final int MAXIMUM_TOTAL_IMAGE_BYTES = 30 * 1024 * 1024;
	private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final int MAXIMUM_SYSTEM_PROMPT_LENGTH = 1_000_000;
	private static final int MAXIMUM_USER_PROMPT_LENGTH = 50_000;
	private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
		"image/jpeg",
		"image/png",
		"image/webp",
		"image/gif"
	);

	/**
	 * 提示詞。要求模型只輸出 JSON，後續解析才不必處理自然語言。
	 * 實際要提取哪些欄位由 {@code app.ai.required-fields} 帶入。
	 */
	private static final String PROMPT_TEMPLATE = """
            你是一個規格資料擷取工具。請閱讀這張規格圖／資訊圖，
            擷取出下列欄位並「只」回傳一個 JSON 物件，不要任何說明文字或程式碼區塊標記。

            需要擷取的欄位：%s

            規則：
            1. 找不到的欄位，值請填 null，不要自行推測或編造。
            2. 數字請保留原始單位文字，例如 "1200 mm"。
            3. 回傳格式範例：{"欄位A": "值", "欄位B": null}
            """;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient httpClient;
	private NetworkObservationLogger networkLogger = new NetworkObservationLogger();
	private AiUsageAuditService usageAuditService = new AiUsageAuditService(
		new AiUsageCostCalculator("USD", "", "", "")
	);

	@Value("${app.ai.api-url:}")
	private String apiUrl;

	@Value("${app.ai.api-key:}")
	private String apiKey;

	@Value("${app.ai.model:}")
	private String model;

	/** 必要欄位；留空代表不做欄位檢查，任何回應都算成功。 */
	@Value("${app.ai.required-fields:}")
	private String requiredFieldsRaw;

	private final int timeoutSeconds;

	/**
	 * 建立 HTTP 用戶端。連線逾時固定 15 秒，讀取逾時另由每個請求指定。
	 */
	//#region 初始化與設定

	// 方法：初始化 AiExtractionService。
	public AiExtractionService(@Value("${app.ai.timeout-seconds:}") String timeoutSecondsRaw) {
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
		this.timeoutSeconds = parseTimeoutSeconds(timeoutSecondsRaw);
	}

	// 方法：在完整 Spring 容器中接入外部網路 RED 與 AI usage 成本稽核。
	@Autowired(required = false)
	public void configureObservability(
		NetworkObservationLogger networkLogger,
		AiUsageAuditService usageAuditService
	) {
		this.networkLogger = networkLogger;
		this.usageAuditService = usageAuditService;
	}

	// 方法：執行 parseTimeoutSeconds 方法的處理流程。
	private static int parseTimeoutSeconds(String timeoutSecondsRaw) {
		if (timeoutSecondsRaw == null || timeoutSecondsRaw.isBlank()) return DEFAULT_TIMEOUT_SECONDS;

		try {
			int timeoutSeconds = Integer.parseInt(timeoutSecondsRaw.trim());
			if (timeoutSeconds <= 0) {
				throw new IllegalArgumentException("AI_TIMEOUT_SECONDS 必須大於 0");
			}
			return timeoutSeconds;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("AI_TIMEOUT_SECONDS 必須是正整數", e);
		}
	}

	/**
	 * 設定是否齊全。指令入口應先問過這個方法，才不會讓使用者等到逾時才知道沒設定。
	 *
	 * @return 端點、金鑰、模型三者都有值時為 true
	 */
	//#endregion

	//#region 提取流程

	// 方法：執行 isConfigured 方法的處理流程。
	@Override
	public boolean isConfigured() {
		return notBlank(apiUrl) && notBlank(apiKey) && notBlank(model);
	}

	// 方法：以固定 system/user 邊界與候選圖片取得模型輸出的 JSON 文字。
	@Override
	public String completeJson(String systemPrompt, String userPrompt, List<AiImageInput> images) {
		long startedAt = System.nanoTime();
		List<AiImageInput> safeImages = validateCompletionInput(systemPrompt, userPrompt, images);

		// 日誌：記錄結構化 JSON 模型呼叫開始，不記錄提示詞或圖片內容。
		log.info(
			"event=ai_json_completion_started requestId={} modelConfigured={} imageCount={}",
			currentRequestId(),
			notBlank(model),
			safeImages.size()
		);
		try {
			if (!isConfigured()) {
				usageAuditService.auditFailure(
					model,
					"quotation_json_completion",
					AiAttemptStatus.NOT_CONFIGURED,
					"NotConfigured"
				);
				throw new AiExtractionException("AI 服務尚未設定（AI_API_URL／AI_API_KEY／AI_MODEL）", (Throwable) null);
			}

			String responseBody = callModel(
				buildJsonCompletionRequestBody(systemPrompt, userPrompt, safeImages),
				"quotation_json_completion"
			);
			String content = extractContent(responseBody);

			// 日誌：記錄 JSON 模型呼叫完成與耗時，不記錄模型輸出。
			log.info(
				"event=ai_json_completion_completed requestId={} durationMs={}",
				currentRequestId(),
				elapsedMilliseconds(startedAt)
			);
			return content;
		}
		catch (RuntimeException exception) {
			// 日誌：記錄 JSON 模型呼叫失敗的安全摘要。
			log.warn(
				"event=ai_json_completion_failed requestId={} durationMs={} errorType={}",
				currentRequestId(),
				elapsedMilliseconds(startedAt),
				exception.getClass().getSimpleName()
			);
			throw exception;
		}
	}

	/**
	 * 把圖片送給模型並取回結構化欄位。
	 *
	 * <p>流程：組請求 → 呼叫 → 取出回應文字 → 解析 JSON → 檢查必要欄位。
	 * 任何一步失敗都拋出 {@link AiExtractionException}，由呼叫端轉成群組訊息。
	 *
	 * @param imageBytes  圖片位元組
	 * @param contentType 圖片 MIME 型態，例如 image/jpeg
	 * @return 擷取結果
	 * @throws AiExtractionException 未設定、呼叫失敗、回應無法解析、或必要欄位缺漏
	 */
	// 方法：執行 extract 方法的處理流程。
	public ExtractedSpec extract(byte[] imageBytes, String contentType) {
		// 步驟 1：使用系統單調時鐘記錄起始時間，供流程追蹤計算耗時。
		long startedAt = System.nanoTime();

		// 日誌：記錄 AI 圖片資料提取開始。
		log.info(
			"event=ai_extraction_started requestId={} modelConfigured={} imageBytes={}",
			currentRequestId(),
			notBlank(model),
			imageBytes == null ? 0 : imageBytes.length
		);
		try {
			if (!isConfigured()) {
				usageAuditService.auditFailure(
					model,
					"image_spec_extraction",
					AiAttemptStatus.NOT_CONFIGURED,
					"NotConfigured"
				);
				throw new AiExtractionException("AI 服務尚未設定（AI_API_URL／AI_API_KEY／AI_MODEL）", (Throwable) null);
			}

			// 步驟 2：依序組建請求、呼叫模型並解析模型回應。
			List<String> requiredFields = requiredFields();
			String responseBody = callModel(imageBytes, contentType, requiredFields, "image_spec_extraction");
			String content = extractContent(responseBody);
			Map<String, Object> fields = parseJsonObject(content);

			// 步驟 3：驗證必要欄位後建立可供報價流程使用的結果。
			validateRequiredFields(fields, requiredFields);

			// 日誌：記錄 AI 圖片資料提取完成與耗時。
			log.info(
				"event=ai_extraction_completed requestId={} fieldCount={} durationMs={}",
				currentRequestId(),
				fields.size(),
				elapsedMilliseconds(startedAt)
			);
			return new ExtractedSpec(fields, content);
		}
		catch (RuntimeException e) {
			// 日誌：記錄 AI 圖片資料提取失敗。
			log.warn(
				"event=ai_extraction_failed requestId={} durationMs={} errorType={}",
				currentRequestId(),
				elapsedMilliseconds(startedAt),
				e.getClass().getSimpleName()
			);
			throw e;
		}
	}

	// 方法：執行 elapsedMilliseconds 方法的處理流程。
	private static long elapsedMilliseconds(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}

	// 方法：執行 currentRequestId 方法的處理流程。
	private static String currentRequestId() {
		// 外部呼叫：從 SLF4J MDC 取得目前請求識別碼，串接同一次流程的日誌。
		String requestId = MDC.get("requestId");
		return requestId == null ? "background" : requestId;
	}

	/**
	 * 解析設定字串成必要欄位清單。
	 *
	 * @return 欄位名稱；未設定時為空集合，代表不檢查
	 */
	// 方法：執行 requiredFields 方法的處理流程。
	private List<String> requiredFields() {
		if (!notBlank(requiredFieldsRaw)) return List.of();

		List<String> fields = new ArrayList<>();
		for (String part : requiredFieldsRaw.split("[,，]")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				fields.add(trimmed);
			}
		}
		return fields;
	}

	/**
	 * 實際發出 HTTP 請求。
	 *
	 * @param imageBytes     圖片位元組
	 * @param contentType    圖片 MIME 型態
	 * @param requiredFields 要擷取的欄位，寫進提示詞
	 * @return 回應本文
	 * @throws AiExtractionException 連線失敗或狀態碼非 2xx
	 */
	//#endregion

	//#region 模型請求

	// 方法：執行 callModel 方法的處理流程。
	private String callModel(
		byte[] imageBytes,
		String contentType,
		List<String> requiredFields,
		String operation
	) {
		return callModel(buildRequestBody(imageBytes, contentType, requiredFields), operation);
	}

	// 方法：將已建立的 OpenAI 相容請求送往設定端點並限制回應大小。
	private String callModel(Map<String, Object> requestBody, String operation) {
		long networkStartedAt = -1;
		boolean networkFinished = false;
		boolean attemptAudited = false;
		try {
			// 步驟 1：使用 Jackson 將模型請求資料序列化成 JSON。
			String payload = objectMapper.writeValueAsString(requestBody);

			// 步驟 2：使用 Java HTTP API 建立含驗證資訊、逾時與 JSON 本文的請求。
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(chatCompletionsUrl()))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

			// 步驟 3：透過 Java HTTP 用戶端送出請求並等待文字回應。
			networkStartedAt = networkLogger.started("AI", "chat_completion");
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			networkLogger.completed("AI", "chat_completion", networkStartedAt, response.statusCode());
			networkFinished = true;
			String responseBody;
			try (InputStream body = response.body()) {
				responseBody = readLimitedResponseBody(body);
			}
			if (response.statusCode() / 100 != 2) {
				usageAuditService.auditFailure(
					model,
					operation,
					AiAttemptStatus.HTTP_ERROR,
					"HttpStatus" + response.statusCode()
				);
				attemptAudited = true;
				throw new AiExtractionException("模型回應狀態碼 " + response.statusCode(), (Throwable) null);
			}
			usageAuditService.auditSuccess(responseBody, model, operation);
			attemptAudited = true;

			return responseBody;
		}
		catch (AiExtractionException e) {
			if (networkStartedAt >= 0 && !networkFinished) {
				networkLogger.failed("AI", "chat_completion", networkStartedAt, e);
			}
			if (!attemptAudited) {
				usageAuditService.auditFailure(
					model,
					operation,
					AiAttemptStatus.NETWORK_ERROR,
					e.getClass().getSimpleName()
				);
			}
			throw e;
		}
		catch (InterruptedException e) {
			if (networkStartedAt >= 0 && !networkFinished) {
				networkLogger.failed("AI", "chat_completion", networkStartedAt, e);
			}
			// 外部呼叫：恢復執行緒中斷旗標，讓上層仍能辨識取消訊號。
			Thread.currentThread().interrupt();
			usageAuditService.auditFailure(
				model,
				operation,
				AiAttemptStatus.NETWORK_ERROR,
				e.getClass().getSimpleName()
			);
			throw new AiExtractionException("呼叫模型時被中斷", e);
		}
		catch (Exception e) {
			if (networkStartedAt >= 0 && !networkFinished) {
				networkLogger.failed("AI", "chat_completion", networkStartedAt, e);
			}
			usageAuditService.auditFailure(
				model,
				operation,
				e instanceof HttpTimeoutException ? AiAttemptStatus.TIMEOUT : AiAttemptStatus.NETWORK_ERROR,
				e.getClass().getSimpleName()
			);
			throw new AiExtractionException("呼叫模型失敗：" + e.getMessage(), e);
		}
	}

	// 方法：由共同 AI API 基底網址推導 Chat Completions 端點，並相容既有完整端點設定。
	private String chatCompletionsUrl() {
		String normalized = apiUrl.replaceFirst("/+$", "");
		if (normalized.endsWith("/chat/completions")) return normalized;

		return normalized + "/chat/completions";
	}

	// 方法：限制模型 HTTP 回應大小，避免錯誤端點耗盡記憶體。
	private String readLimitedResponseBody(InputStream body) throws IOException {
		// 外部 API：最多讀取上限再多一個位元組，以辨識超量回應。
		byte[] bytes = body.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
		if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
			throw new AiExtractionException("模型回應超過大小上限", (Throwable) null);
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * 組出 OpenAI 相容的 chat completions 請求本文。
	 *
	 * <p>圖片以 base64 data URL 內嵌，避免還要先把圖片上傳到某個公開位置。
	 * 換成其他廠商的 API 時，改這個方法即可。
	 *
	 * @param imageBytes     圖片位元組
	 * @param contentType    圖片 MIME 型態
	 * @param requiredFields 要擷取的欄位
	 * @return 可直接序列化成 JSON 的請求本文
	 */
	// 方法：執行 buildRequestBody 方法的處理流程。
	private Map<String, Object> buildRequestBody(byte[] imageBytes, String contentType, List<String> requiredFields) {
		// 步驟 1：整理提示詞需要的欄位清單。
		String fieldList = requiredFields.isEmpty()
			? "（未設定，請擷取圖片中所有可辨識的規格欄位）"
			: String.join("、", requiredFields);

		// 步驟 2：使用 Base64 API 將圖片轉成模型可接收的 data URL。
		String dataUrl = "data:" + (notBlank(contentType) ? contentType : "image/jpeg") + ";base64,"
			+ Base64.getEncoder().encodeToString(imageBytes);

		// 步驟 3：依 OpenAI 相容格式組合文字、圖片與模型設定。
		Map<String, Object> textPart = new LinkedHashMap<>();
		textPart.put("type", "text");
		textPart.put("text", PROMPT_TEMPLATE.formatted(fieldList));

		Map<String, Object> imageUrl = new LinkedHashMap<>();
		imageUrl.put("url", dataUrl);

		Map<String, Object> imagePart = new LinkedHashMap<>();
		imagePart.put("type", "image_url");
		imagePart.put("image_url", imageUrl);

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("role", "user");
		message.put("content", List.of(textPart, imagePart));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", List.of(message));

		return body;
	}

	// 方法：建立具有 system/user 隔離、圖片代碼標籤及輸出上限的 JSON 完成請求。
	private Map<String, Object> buildJsonCompletionRequestBody(
		String systemPrompt,
		String userPrompt,
		List<AiImageInput> images
	) {
		List<Map<String, Object>> userContent = new ArrayList<>();
		userContent.add(textPart(userPrompt));
		for (AiImageInput image : images) {
			userContent.add(textPart("候選圖片 messageId：" + image.messageId()));
			userContent.add(imagePart(image.bytes(), normalizedImageContentType(image.contentType())));
		}

		Map<String, Object> systemMessage = new LinkedHashMap<>();
		systemMessage.put("role", "system");
		systemMessage.put("content", systemPrompt);

		Map<String, Object> userMessage = new LinkedHashMap<>();
		userMessage.put("role", "user");
		userMessage.put("content", userContent);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", List.of(systemMessage, userMessage));
		body.put("max_completion_tokens", 4000);
		return body;
	}

	// 方法：建立 OpenAI 多模態訊息中的文字區塊。
	private Map<String, Object> textPart(String text) {
		Map<String, Object> part = new LinkedHashMap<>();
		part.put("type", "text");
		part.put("text", text);
		return part;
	}

	// 方法：建立 OpenAI 多模態訊息中的圖片 data URL 區塊。
	private Map<String, Object> imagePart(byte[] bytes, String contentType) {
		String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
		Map<String, Object> imageUrl = new LinkedHashMap<>();
		imageUrl.put("url", dataUrl);

		Map<String, Object> part = new LinkedHashMap<>();
		part.put("type", "image_url");
		part.put("image_url", imageUrl);
		return part;
	}

	// 方法：在任何網路呼叫前限制提示詞、圖片數量及總位元組。
	private List<AiImageInput> validateCompletionInput(
		String systemPrompt,
		String userPrompt,
		List<AiImageInput> images
	) {
		requiredPrompt(systemPrompt, "系統提示詞", MAXIMUM_SYSTEM_PROMPT_LENGTH);
		requiredPrompt(userPrompt, "使用者提示詞", MAXIMUM_USER_PROMPT_LENGTH);
		List<AiImageInput> safeImages = images == null ? List.of() : List.copyOf(images);
		if (safeImages.size() > MAXIMUM_COMPLETION_IMAGES) {
			throw new AiExtractionException("候選圖片不可超過 " + MAXIMUM_COMPLETION_IMAGES + " 張", (Throwable) null);
		}

		Set<String> messageIds = new HashSet<>();
		long totalBytes = 0;
		for (AiImageInput image : safeImages) {
			if (image == null || !notBlank(image.messageId())) {
				throw new AiExtractionException("候選圖片代碼不可留空", (Throwable) null);
			}
			if (!messageIds.add(image.messageId())) {
				throw new AiExtractionException("候選圖片代碼不可重複", (Throwable) null);
			}

			byte[] bytes = image.bytes();
			if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_IMAGE_BYTES) {
				throw new AiExtractionException("單張候選圖片大小不正確或超過上限", (Throwable) null);
			}
			normalizedImageContentType(image.contentType());
			totalBytes += bytes.length;
		}
		if (totalBytes > MAXIMUM_TOTAL_IMAGE_BYTES) {
			throw new AiExtractionException("候選圖片總大小超過上限", (Throwable) null);
		}
		return safeImages;
	}

	// 方法：驗證提示詞具有內容且大小受限。
	private void requiredPrompt(String value, String label, int maximumLength) {
		if (!notBlank(value)) throw new AiExtractionException(label + "不可留空", (Throwable) null);

		if (value.length() > maximumLength) {
			throw new AiExtractionException(label + "長度超過上限", (Throwable) null);
		}
	}

	// 方法：將圖片 MIME 正規化為明確允許的格式。
	private String normalizedImageContentType(String contentType) {
		if (!notBlank(contentType)) {
			throw new AiExtractionException("候選圖片格式不可留空", (Throwable) null);
		}

		String normalized = contentType.trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(normalized)) {
			throw new AiExtractionException("不支援的候選圖片格式", (Throwable) null);
		}
		return normalized;
	}

	/**
	 * 從模型回應中取出真正的文字內容。
	 *
	 * @param responseBody 回應本文
	 * @return 模型輸出的文字
	 * @throws AiExtractionException 回應結構不符預期
	 */
	//#endregion

	//#region 回應解析與驗證

	// 方法：執行 extractContent 方法的處理流程。
	private String extractContent(String responseBody) {
		try {
			// 外部呼叫：使用 Jackson 解析模型回應並沿固定路徑取得 assistant 文字。
			JsonNode root = objectMapper.readTree(responseBody);
			JsonNode content = root.path("choices").path(0).path("message").path("content");
			if (!content.isString()) {
				throw new AiExtractionException("模型回應結構不符預期：" + truncate(responseBody), (Throwable) null);
			}
			return content.stringValue();
		}
		catch (AiExtractionException e) {
			throw e;
		}
		catch (Exception e) {
			throw new AiExtractionException("模型回應不是合法 JSON：" + truncate(responseBody), e);
		}
	}

	/**
	 * 把模型輸出的文字解析成欄位對應。
	 *
	 * <p>即使提示詞要求只回 JSON，模型仍常常包上 ```json 區塊或加一句開場白，
	 * 因此這裡先剝掉程式碼區塊標記，再擷取第一個大括號到最後一個大括號之間的內容。
	 *
	 * @param content 模型輸出文字
	 * @return 欄位對應
	 * @throws AiExtractionException 內容不含合法的 JSON 物件
	 */
	// 方法：執行 parseJsonObject 方法的處理流程。
	private Map<String, Object> parseJsonObject(String content) {
		// 步驟 1：移除模型可能附加的 Markdown 程式碼區塊標記。
		String cleaned = content.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();

		// 步驟 2：定位第一個完整 JSON 物件的範圍。
		int start = cleaned.indexOf('{');
		int end = cleaned.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new AiExtractionException("模型沒有回傳 JSON 物件：" + truncate(content), (Throwable) null);
		}

		try {
			// 步驟 3：使用 Jackson 解析 JSON，並轉成報價流程可直接使用的 Java 值。
			JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
			Map<String, Object> fields = new LinkedHashMap<>();
			node.propertyNames().forEach(name -> {
					JsonNode value = node.get(name);
					fields.put(name, value == null || value.isNull() ? null : toPlainValue(value));
				});
			return fields;
		}
		catch (Exception e) {
			throw new AiExtractionException("解析模型輸出失敗：" + truncate(content), e);
		}
	}

	/**
	 * 把 JSON 節點轉成單純的 Java 值，方便公式端直接使用。
	 *
	 * @param value JSON 節點
	 * @return 字串、數字或原始文字表示
	 */
	// 方法：執行 toPlainValue 方法的處理流程。
	private Object toPlainValue(JsonNode value) {
		if (value.isNumber()) return value.decimalValue();

		if (value.isBoolean()) return value.booleanValue();

		return value.isString() ? value.stringValue() : value.toString();
	}

	/**
	 * 檢查必要欄位是否都有值。
	 *
	 * <p>「欄位不存在」與「欄位存在但值是 null／空字串」都算缺漏——
	 * 模型被要求找不到就填 null，所以後者才是常見情況。
	 *
	 * @param fields         解析出的欄位
	 * @param requiredFields 必要欄位
	 * @throws AiExtractionException 任何必要欄位缺漏
	 */
	// 方法：執行 validateRequiredFields 方法的處理流程。
	private void validateRequiredFields(Map<String, Object> fields, List<String> requiredFields) {
		if (requiredFields.isEmpty()) return;

		List<String> missing = new ArrayList<>();
		for (String field : requiredFields) {
			Object value = fields.get(field);
			if (value == null || String.valueOf(value).isBlank()) {
				missing.add(field);
			}
		}
		if (!missing.isEmpty()) {
			throw new AiExtractionException("必要欄位缺漏", missing);
		}
	}

	/**
	 * 截短過長的內容，避免把整包回應塞進日誌或群組訊息。
	 *
	 * @param text 原始文字，可為 null
	 * @return 最多 300 字的片段
	 */
	// 方法：執行 truncate 方法的處理流程。
	private static String truncate(String text) {
		if (text == null) return "(空)";

		return text.length() <= 300 ? text : text.substring(0, 300) + "…";
	}

	/**
	 * 字串是否有實質內容。
	 *
	 * @param value 待檢查字串，可為 null
	 * @return 非 null 且非空白時為 true
	 */
	// 方法：執行 notBlank 方法的處理流程。
	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	//#endregion
}
