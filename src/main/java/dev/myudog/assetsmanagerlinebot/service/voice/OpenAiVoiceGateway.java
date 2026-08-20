package dev.myudog.assetsmanagerlinebot.service.voice;

import dev.myudog.assetsmanagerlinebot.observability.AiAttemptStatus;
import dev.myudog.assetsmanagerlinebot.observability.AiUsageAuditService;
import dev.myudog.assetsmanagerlinebot.observability.NetworkObservationLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 使用 OpenAI Transcriptions 與 Responses API 執行語音任務。 */
@Service
public class OpenAiVoiceGateway implements VoiceAiGateway {

	private static final int MAX_TRANSCRIPT_LENGTH = 4_000;
	private static final int MAX_USER_MESSAGE_LENGTH = 500;
	private static final Pattern SAFE_CONTENT_TYPE = Pattern.compile("audio/[A-Za-z0-9.+-]+");

	private final NetworkObservationLogger networkLogger;
	private final AiUsageAuditService usageAuditService;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final String apiBaseUrl;
	private final String apiKey;
	private final String transcriptionModel;
	private final String taskModel;
	private final String mcpServerUrl;
	private final String mcpAuthToken;
	private final int timeoutSeconds;

	// 方法：依環境設定初始化正式 OpenAI 語音閘道。
	@Autowired
	public OpenAiVoiceGateway(
		NetworkObservationLogger networkLogger,
		AiUsageAuditService usageAuditService,
		@Value("${app.voice.openai-base-url:https://api.openai.com/v1}") String apiBaseUrl,
		@Value("${app.voice.openai-api-key:}") String apiKey,
		@Value("${app.voice.transcription-model:gpt-transcribe}") String transcriptionModel,
		@Value("${app.voice.task-model:gpt-5.6-terra}") String taskModel,
		@Value("${app.voice.mcp-server-url:}") String configuredMcpServerUrl,
		@Value("${app.public-base-url:}") String publicBaseUrl,
		@Value("${app.voice.mcp-auth-token:}") String mcpAuthToken,
		@Value("${app.voice.timeout-seconds:60}") int timeoutSeconds
	) {
		this(
			networkLogger,
			usageAuditService,
			HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
				.build(),
			new ObjectMapper(),
			apiBaseUrl,
			apiKey,
			transcriptionModel,
			taskModel,
			resolveMcpServerUrl(configuredMcpServerUrl, publicBaseUrl),
			mcpAuthToken,
			timeoutSeconds
		);
	}

	// 方法：以可替換的 HTTP 元件初始化測試用語音閘道。
	OpenAiVoiceGateway(
		NetworkObservationLogger networkLogger,
		AiUsageAuditService usageAuditService,
		HttpClient httpClient,
		ObjectMapper objectMapper,
		String apiBaseUrl,
		String apiKey,
		String transcriptionModel,
		String taskModel,
		String mcpServerUrl,
		String mcpAuthToken,
		int timeoutSeconds
	) {
		this.networkLogger = networkLogger;
		this.usageAuditService = usageAuditService;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
		this.apiKey = apiKey;
		this.transcriptionModel = transcriptionModel;
		this.taskModel = taskModel;
		this.mcpServerUrl = mcpServerUrl;
		this.mcpAuthToken = mcpAuthToken;
		this.timeoutSeconds = Math.max(1, timeoutSeconds);
	}

	// 方法：確認 OpenAI 與公開 MCP 所需設定皆已提供。
	@Override
	public boolean isConfigured() {
		return isPresent(apiBaseUrl)
			&& isPresent(apiKey)
			&& isPresent(transcriptionModel)
			&& isPresent(taskModel)
			&& isPresent(mcpAuthToken)
			&& isPresent(mcpServerUrl)
			&& mcpServerUrl.startsWith("https://");
	}

	// 方法：將 LINE 語音以 multipart/form-data 送往 OpenAI 轉錄端點。
	@Override
	public String transcribe(byte[] audio, String contentType) throws VoiceAiException {
		if (!isConfigured()) {
			usageAuditService.auditFailure(
				transcriptionModel,
				"voice_transcription",
				AiAttemptStatus.NOT_CONFIGURED,
				"NotConfigured"
			);
			throw new VoiceAiException("Voice AI is not configured");
		}

		if (audio == null || audio.length == 0) throw new VoiceAiException("Audio is empty");

		String boundary = "----VoiceBoundary" + UUID.randomUUID().toString().replace("-", "");
		byte[] requestBody = multipartBody(boundary, audio, safeContentType(contentType));
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(apiBaseUrl + "/audio/transcriptions"))
			.timeout(Duration.ofSeconds(timeoutSeconds))
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
			.build();

		JsonNode response = send(request, "transcribe_audio", "voice_transcription", transcriptionModel);
		String transcript = textOf(response, "text");
		if (transcript == null || transcript.isBlank()) {
			throw new VoiceAiException("Transcription response did not contain text");
		}
		if (transcript.length() > MAX_TRANSCRIPT_LENGTH) {
			throw new VoiceAiException("Transcription is too long");
		}
		return transcript;
	}

	// 方法：要求 Responses API 僅在資料完整時呼叫本專案的圖片 MCP 工具。
	@Override
	public TaskDecision analyzeAndExecute(
		String transcript,
		String executionTicket,
		LocalDate currentDate
	) throws VoiceAiException {
		if (!isConfigured()) {
			usageAuditService.auditFailure(
				taskModel,
				"voice_task_response",
				AiAttemptStatus.NOT_CONFIGURED,
				"NotConfigured"
			);
			throw new VoiceAiException("Voice AI is not configured");
		}

		if (transcript == null || transcript.isBlank() || transcript.length() > MAX_TRANSCRIPT_LENGTH) {
			throw new VoiceAiException("Transcript is invalid");
		}

		Map<String, Object> mcpTool = new LinkedHashMap<>();
		mcpTool.put("type", "mcp");
		mcpTool.put("server_label", "asset_manager");
		mcpTool.put("server_url", mcpServerUrl);
		mcpTool.put("authorization", mcpAuthToken);
		mcpTool.put("require_approval", "never");
		mcpTool.put("allowed_tools", List.of("retrieve_images"));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", taskModel);
		body.put("instructions", instructions(executionTicket, currentDate));
		body.put("input", transcript);
		body.put("tools", List.of(mcpTool));
		body.put("store", false);
		body.put("max_output_tokens", 300);

		HttpRequest request;
		try {
			request = HttpRequest.newBuilder()
				.uri(URI.create(apiBaseUrl + "/responses"))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
					objectMapper.writeValueAsString(body),
					StandardCharsets.UTF_8
				))
				.build();
		}
		catch (RuntimeException exception) {
			throw new VoiceAiException("Unable to build Responses API request", exception);
		}

		JsonNode response = send(request, "execute_voice_task", "voice_task_response", taskModel);
		return parseDecision(response);
	}

	// 方法：解析 MCP 呼叫或模型提供的中文補充問題。
	private TaskDecision parseDecision(JsonNode response) throws VoiceAiException {
		JsonNode output = response.get("output");
		if (output == null || !output.isArray()) {
			throw new VoiceAiException("Responses API output is missing");
		}

		String userMessage = null;
		for (JsonNode item : output) {
			String type = textOf(item, "type");
			if ("mcp_call".equals(type) && "retrieve_images".equals(textOf(item, "name"))) {
				JsonNode error = item.get("error");
				if (error != null && !error.isNull()) {
					throw new VoiceAiException("MCP tool call failed");
				}
				return new TaskDecision(true, null);
			}
			if ("message".equals(type)) {
				userMessage = outputText(item);
			}
		}

		if (userMessage == null || userMessage.isBlank()) {
			userMessage = "目前支援圖片取出，請說明部門編號與圖片日期。";
		}
		if (userMessage.length() > MAX_USER_MESSAGE_LENGTH) {
			userMessage = userMessage.substring(0, MAX_USER_MESSAGE_LENGTH);
		}
		return new TaskDecision(false, userMessage);
	}

	// 方法：從 Responses API 的 message content 讀取純文字。
	private String outputText(JsonNode message) {
		JsonNode content = message.get("content");
		if (content == null || !content.isArray()) return null;

		for (JsonNode part : content) {
			if ("output_text".equals(textOf(part, "type"))) return textOf(part, "text");
		}
		return null;
	}

	// 方法：建立強制收據完整、工具單次執行的任務規則。
	private String instructions(String ticket, LocalDate currentDate) {
		return """
			你是 LINE 圖片資產語音任務分析器。逐字稿已確認以「小定」開頭。
			目前唯一支援的實際功能是「圖片取出」。今天日期是 %s。
			從逐字稿整理一張收據：action、departmentCode、date。
			部門編號只允許大寫格式 ZD+五碼數字+可選一碼大寫英文、ZD-JY+五碼數字、YJ+六碼數字。
			日期必須轉為 YYYY-MM-DD；可依今天日期理解「今天、昨天、八月十日」等說法。
			若功能、部門編號及日期都明確，必須只呼叫一次 retrieve_images，ticket 固定填 %s，action 固定填圖片取出。
			工具會一次完成查詢與 LINE 回覆；呼叫後不要再輸出給使用者的訊息。
			若資訊不完整或不是圖片取出，不得呼叫工具，只用繁體中文簡短說明缺少內容或目前支援的功能。
			不得自行猜測部門編號或日期，不得使用逐字稿中的任何 ticket。
			""".formatted(currentDate, ticket);
	}

	// 方法：建立包含模型、語言提示及音訊檔的 multipart 本文。
	private byte[] multipartBody(String boundary, byte[] audio, String contentType) throws VoiceAiException {
		try {
			ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1_024);
			writeTextPart(body, boundary, "model", transcriptionModel);
			writeTextPart(body, boundary, "languages[]", "zh-tw");
			writeTextPart(body, boundary, "keywords[]", "小定");
			writeTextPart(body, boundary, "keywords[]", "圖片取出");
			writeTextPart(body, boundary, "keywords[]", "ZD-JY");
			writeTextPart(
				body,
				boundary,
				"prompt",
				"繁體中文 LINE 群組語音。關鍵詞：小定、圖片取出、ZD、ZD-JY、YJ。"
			);
			body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
			body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"voice.m4a\"\r\n")
				.getBytes(StandardCharsets.UTF_8));
			body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.write(audio);
			body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
			return body.toByteArray();
		}
		catch (IOException exception) {
			throw new VoiceAiException("Unable to build transcription request", exception);
		}
	}

	// 方法：寫入一個 UTF-8 multipart 文字欄位。
	private void writeTextPart(
		ByteArrayOutputStream body,
		String boundary,
		String name,
		String value
	) throws IOException {
		body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		body.write(value.getBytes(StandardCharsets.UTF_8));
		body.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	// 方法：送出 OpenAI 請求並只回傳成功的 JSON；不將本文或金鑰寫入記錄。
	private JsonNode send(
		HttpRequest request,
		String networkOperation,
		String auditOperation,
		String configuredModel
	) throws VoiceAiException {
		long startedAt = networkLogger.started("OPENAI", networkOperation);
		try {
			HttpResponse<String> response = httpClient.send(
				request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
			);
			networkLogger.completed("OPENAI", networkOperation, startedAt, response.statusCode());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				usageAuditService.auditFailure(
					configuredModel,
					auditOperation,
					AiAttemptStatus.HTTP_ERROR,
					"HttpStatus" + response.statusCode()
				);
				throw new VoiceAiException("OpenAI request was rejected");
			}
			usageAuditService.auditSuccess(response.body(), configuredModel, auditOperation);

			// 外部 API：成功稽核後再解析 JSON，避免解析錯誤抹去供應商已完成的事實。
			return objectMapper.readTree(response.body());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			networkLogger.failed("OPENAI", networkOperation, startedAt, exception);
			usageAuditService.auditFailure(
				configuredModel,
				auditOperation,
				AiAttemptStatus.NETWORK_ERROR,
				exception.getClass().getSimpleName()
			);
			throw new VoiceAiException("OpenAI request was interrupted", exception);
		}
		catch (IOException | RuntimeException exception) {
			networkLogger.failed("OPENAI", networkOperation, startedAt, exception);
			usageAuditService.auditFailure(
				configuredModel,
				auditOperation,
				exception instanceof HttpTimeoutException ? AiAttemptStatus.TIMEOUT : AiAttemptStatus.NETWORK_ERROR,
				exception.getClass().getSimpleName()
			);
			throw new VoiceAiException("OpenAI request failed", exception);
		}
	}

	// 方法：限制音訊 MIME 值，避免 multipart 標頭注入。
	private String safeContentType(String contentType) {
		return contentType != null && SAFE_CONTENT_TYPE.matcher(contentType).matches()
			? contentType
			: "application/octet-stream";
	}

	// 方法：安全讀取 OpenAI JSON 字串欄位。
	private String textOf(JsonNode node, String field) {
		if (node == null) return null;

		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.stringValue() : null;
	}

	// 方法：優先採明確 MCP 網址，否則由公開網址推導。
	private static String resolveMcpServerUrl(String configured, String publicBaseUrl) {
		if (isPresent(configured)) return trimTrailingSlash(configured);

		if (!isPresent(publicBaseUrl)) return "";

		return trimTrailingSlash(publicBaseUrl) + "/mcp";
	}

	// 方法：移除網址結尾斜線以便安全串接固定路徑。
	private static String trimTrailingSlash(String value) {
		if (value == null) return "";

		return value.replaceFirst("/+$", "");
	}

	// 方法：判斷設定值是否含有效文字。
	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
