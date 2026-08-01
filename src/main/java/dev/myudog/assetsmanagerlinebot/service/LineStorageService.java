package dev.myudog.assetsmanagerlinebot.service;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	/** LINE 回傳的原始內容與其 Content-Type，副檔名要靠後者決定。 */
	public record LineContent(InputStream stream, String contentType) {}

	// 方法：執行 downloadContent 方法的處理流程。
	public LineContent downloadContent(String messageId) {
		String url = "https://api-data.line.me/v2/bot/message/" + messageId + "/content";

		try {
			// 步驟 1：使用 Java HTTP API 建立帶有 LINE 權杖的圖片下載請求。
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Authorization", "Bearer " + channelToken)
				.GET()
				.build();

			// 步驟 2：透過 Java HTTP 用戶端下載圖片串流並驗證回應狀態。
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
		if (replyToken == null || messages.isEmpty()) return;

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("replyToken", replyToken);
		body.put("messages", messages.size() > 5 ? messages.subList(0, 5) : messages);
		post("https://api.line.me/v2/bot/message/reply", body);
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
			if (response.statusCode() != 200) {
				// 日誌：記錄 LINE 訊息發送遭拒。
				log.warn("event=line_message_send_rejected status={}", response.statusCode());
			}
		}
		catch (Exception e) {
			// 日誌：記錄 LINE 訊息發送發生例外。
			log.error("event=line_message_send_failed errorType={}", e.getClass().getSimpleName());
		}
	}
}
