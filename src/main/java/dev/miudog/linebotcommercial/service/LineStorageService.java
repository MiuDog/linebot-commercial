package dev.miudog.linebotcommercial.service;

import dev.miudog.linebotcommercial.observability.NetworkObservationLogger;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 與 LINE Messaging API 的所有往來：下載訊息內容、回覆訊息。
 *
 * <p><b>下載鏈：</b>
 * {@code LineWebhookController.handleImage → downloadContent
 * → LINE content API → ImageArchiveService.stage}。
 *
 * <p><b>回覆鏈：</b>
 * {@code CommandService → replyText／reply → post → LINE reply API}。
 * 所有外部 LINE HTTP 呼叫集中於此，其他 Service 不需要持有 channel token。
 */
@Service
public class LineStorageService {

	private static final Logger log = LoggerFactory.getLogger(LineStorageService.class);

	@Value("${line.bot.channel-token}")
	private String channelToken;

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final NetworkObservationLogger networkLogger;

	/** LINE 回傳的原始內容與其 Content-Type，副檔名要靠後者決定。 */
	public record LineContent(InputStream stream, String contentType) {}

	/** LINE push 成功後可供稽核保存的供應商訊息識別碼。 */
	public record LinePushReceipt(String providerMessageId) {}

	/** LINE Messaging API 發送失敗的穩定錯誤契約，不攜帶原始回應內容。 */
	public static class LineMessagingException extends RuntimeException {

		private final String code;

		// 方法：建立不包含權杖或外部回應本文的 LINE 發送例外。
		public LineMessagingException(String code, String message) {
			super(message);
			this.code = code;
		}

		// 方法：取得可安全保存與判斷重試的穩定錯誤碼。
		public String code() {
			return code;
		}
	}

	// 方法：注入外部網路 RED 觀測器，且不讓 LINE 憑證或訊息內容進入日誌。
	@Autowired
	public LineStorageService(NetworkObservationLogger networkLogger) {
		this(networkLogger, HttpClient.newHttpClient(), new ObjectMapper(), null);
	}

	// 方法：提供測試使用的可替換 HTTP 邊界，避免實際呼叫 LINE。
	LineStorageService(
		NetworkObservationLogger networkLogger,
		HttpClient httpClient,
		ObjectMapper objectMapper,
		String channelToken
	) {
		this.networkLogger = networkLogger;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.channelToken = channelToken;
	}

	// 方法：執行 downloadContent 方法的處理流程。
	public LineContent downloadContent(String messageId) {
		String url = "https://api-data.line.me/v2/bot/message/" + messageId + "/content";
		long startedAt = networkLogger.started("LINE", "download_content");

		try {
			// 步驟 1：使用 Java HTTP API 建立帶有 LINE 權杖的圖片下載請求。
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Authorization", "Bearer " + channelToken)
				.GET()
				.build();

			// 步驟 2：透過 Java HTTP 用戶端下載圖片串流並驗證回應狀態。
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			networkLogger.completed("LINE", "download_content", startedAt, response.statusCode());
			if (response.statusCode() != 200) {
				// 日誌：記錄 LINE 內容下載遭拒。
				log.warn("event=line_content_download_rejected status={}", response.statusCode());
				response.body().close();
				return null;
			}

			// 步驟 3：從 HTTP 標頭取得圖片格式，連同串流交回儲存流程。
			String contentType = response.headers().firstValue("content-type").orElse("image/jpeg");
			return new LineContent(response.body(), contentType);
		}
		catch (Exception e) {
			networkLogger.failed("LINE", "download_content", startedAt, e);
			// 日誌：記錄 LINE 內容下載發生例外。
			log.error("event=line_content_download_failed errorType={}", e.getClass().getSimpleName());
			return null;
		}
	}

	// 方法：執行 replyText 方法的處理流程。
	public void replyText(String replyToken, String text) {
		reply(replyToken, List.of(textMessage(text)));
	}

	/**
	 * 回覆一組訊息。LINE 單次 reply 最多 5 則，超過的部分會被官方直接退回。
	 */
	// 方法：執行 reply 方法的處理流程。
	public void reply(String replyToken, List<Map<String, Object>> messages) {
		if (replyToken == null || replyToken.isBlank()) {
			throw new LineMessagingException("INVALID_REPLY_TOKEN", "LINE reply token 不可留空");
		}
		if (messages == null || messages.isEmpty()) {
			throw new LineMessagingException("INVALID_MESSAGES", "LINE reply 訊息不可留空");
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("replyToken", replyToken);
		body.put("messages", messages.size() > 5 ? messages.subList(0, 5) : messages);
		post("https://api.line.me/v2/bot/message/reply", body);
	}

	// 方法：以 LINE push API 交付正式報價 Flex，回傳可保存的供應商訊息識別碼。
	public LinePushReceipt push(String destinationId, List<Map<String, Object>> messages) {
		return push(destinationId, messages, null);
	}

	// 方法：以穩定 UUID 作為 LINE push retry key，讓逾時或程序重啟後可由供應商去重。
	public LinePushReceipt push(
		String destinationId,
		List<Map<String, Object>> messages,
		UUID retryKey
	) {
		if (destinationId == null || destinationId.isBlank() || destinationId.length() > 128) {
			throw new LineMessagingException("INVALID_DESTINATION", "LINE push 目的地不可留空");
		}

		if (messages == null || messages.isEmpty() || messages.size() > 5) {
			throw new LineMessagingException("INVALID_MESSAGES", "LINE push 訊息數量必須介於 1 到 5");
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("to", destinationId);
		body.put("messages", messages);
		return postPush("https://api.line.me/v2/bot/message/push", body, retryKey);
	}

	// 方法：執行 textMessage 方法的處理流程。
	public static Map<String, Object> textMessage(String text) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "text");
		message.put("text", text);
		return message;
	}

	// 方法：執行 imageMessage 方法的處理流程。
	public static Map<String, Object> imageMessage(String originalUrl, String previewUrl) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "image");
		message.put("originalContentUrl", originalUrl);
		message.put("previewImageUrl", previewUrl);
		return message;
	}

	/**
	 * 訊息內容含中文與使用者自由輸入，一律交給 Jackson 序列化，
	 * 不用字串拼接，避免引號或換行把 JSON 打壞。
	 */
	// 方法：執行 post 方法的處理流程。
	private void post(String url, Map<String, Object> body) {
		long startedAt = networkLogger.started("LINE", "reply_message");
		try {
			// 步驟 1：使用 Jackson 將 LINE 回覆內容安全序列化成 JSON。
			String payload = objectMapper.writeValueAsString(body);

			// 步驟 2：使用 Java HTTP API 建立帶有 LINE 權杖的回覆請求。
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + channelToken)
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

			// 步驟 3：透過 Java HTTP 用戶端送出回覆並檢查 LINE 回應狀態。
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			networkLogger.completed("LINE", "reply_message", startedAt, response.statusCode());
			if (response.statusCode() != 200) {
				// 日誌：記錄 LINE 訊息發送遭拒。
				log.warn("event=line_message_send_rejected status={}", response.statusCode());
				throw new LineMessagingException("LINE_REPLY_HTTP_ERROR", "LINE reply rejected");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			networkLogger.failed("LINE", "reply_message", startedAt, exception);
			throw new LineMessagingException("LINE_REPLY_INTERRUPTED", "LINE reply interrupted");
		}
		catch (LineMessagingException exception) {
			throw exception;
		}
		catch (Exception exception) {
			networkLogger.failed("LINE", "reply_message", startedAt, exception);
			// 日誌：記錄 LINE 訊息發送發生例外。
			log.error("event=line_message_send_failed errorType={}", exception.getClass().getSimpleName());
			throw new LineMessagingException("LINE_REPLY_NETWORK_ERROR", "LINE reply failed");
		}
	}

	// 方法：送出需要明確成功結果的 LINE push，失敗時以穩定錯誤碼交由上層保存並重試。
	private LinePushReceipt postPush(String url, Map<String, Object> body, UUID retryKey) {
		long startedAt = networkLogger.started("LINE", "push_message");
		try {
			String payload = objectMapper.writeValueAsString(body);
			HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + channelToken)
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
			if (retryKey != null) requestBuilder.header("X-Line-Retry-Key", retryKey.toString());

			HttpRequest request = requestBuilder.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			networkLogger.completed("LINE", "push_message", startedAt, response.statusCode());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new LineMessagingException("LINE_HTTP_ERROR", "LINE push rejected");
			}

			return new LinePushReceipt(providerMessageId(response.body()));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			networkLogger.failed("LINE", "push_message", startedAt, exception);
			throw new LineMessagingException("LINE_INTERRUPTED", "LINE push interrupted");
		}
		catch (LineMessagingException exception) {
			throw exception;
		}
		catch (Exception exception) {
			networkLogger.failed("LINE", "push_message", startedAt, exception);
			throw new LineMessagingException("LINE_NETWORK_ERROR", "LINE push failed");
		}
	}

	// 方法：從不可信任的 LINE 成功回應安全提取第一個訊息識別碼，缺少時允許為空。
	private String providerMessageId(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) return null;

		try {
			var root = objectMapper.readTree(responseBody);
			var sentMessages = root.path("sentMessages");
			if (!sentMessages.isArray() || sentMessages.isEmpty()) return null;

			var id = sentMessages.get(0).path("id");
			return id.isString() && !id.stringValue().isBlank() ? id.stringValue() : null;
		}
		catch (RuntimeException exception) {
			return null;
		}
	}
}
