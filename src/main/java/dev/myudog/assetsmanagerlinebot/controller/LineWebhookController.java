package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.service.CommandService;
import dev.myudog.assetsmanagerlinebot.service.ImageArchiveService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 【事件起點】接收 LINE webhook，驗證來源後把訊息分派到圖片或文字流程。
 *
 * <p><b>共同入口：</b>
 * {@code LINE → RequestCorrelationFilter → POST /callback → handleWebhook
 * → verifySignature → handleEvent}。
 *
 * <p><b>圖片事件：</b>
 * {@code handleEvent → handleImage → LineStorageService.downloadContent
 * → ImageArchiveService.stage → .pending + pending_image}。
 *
 * <p><b>文字事件：</b>
 * {@code handleEvent → CommandService.handleText}，後續再依
 * 將大寫資料夾代碼、查詢、標籤或報價指令分派給對應服務。
 *
 * <p>單一事件失敗不會讓整批 webhook 回傳 500，避免 LINE 重送同批事件，
 * 造成已成功事件被重複處理。完整分支見
 * {@code docs/06-event-call-chains.md}。
 */
@RestController
@RequestMapping("/callback")
public class LineWebhookController {

	private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

	@Value("${line.bot.channel-secret}")
	private String channelSecret;

	private final CommandService commandService;
	private final ImageArchiveService imageArchiveService;
	private final LineStorageService lineService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	//#region 初始化與 Webhook 入口

	// 方法：初始化 LineWebhookController。
	public LineWebhookController(
		CommandService commandService,
		ImageArchiveService imageArchiveService,
		LineStorageService lineService
	) {
		this.commandService = commandService;
		this.imageArchiveService = imageArchiveService;
		this.lineService = lineService;
	}

	// 方法：執行 handleWebhook 方法的處理流程。
	@PostMapping
	public ResponseEntity<String> handleWebhook(
		@RequestHeader("X-Line-Signature") String signature,
		@RequestBody String payload
	) {
		// 步驟 1：先驗證 LINE 簽章，避免處理未授權的 Webhook。
		if (!verifySignature(payload, signature)) {
			// 日誌：記錄 LINE Webhook 簽章驗證失敗。
			log.warn("event=line_signature_rejected");

			// 外部呼叫：透過 Spring HTTP 回應 API 拒絕未授權的 Webhook。
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Signature");
		}

		try {
			// 步驟 2：使用 Jackson 將 Webhook 本文解析成可逐筆讀取的事件樹。
			JsonNode root = objectMapper.readTree(payload);
			JsonNode events = root.get("events");

			// 步驟 3：依序處理事件，並隔離單一事件失敗，避免 LINE 重送整批資料。
			if (events != null && events.isArray()) {
				for (JsonNode event : events) {
					try {
						handleEvent(event);
					}
					catch (Exception e) {
						// 日誌：記錄單一 LINE 事件處理失敗。
						log.error("event=line_event_failed errorType={}", e.getClass().getSimpleName());
					}
				}
			}

			// 步驟 4：透過 Spring HTTP 回應 API 告知 LINE 整批事件已接收。
			return ResponseEntity.ok("OK");
		}
		catch (Exception e) {
			// 日誌：記錄 LINE Webhook 解析失敗。
			log.error("event=line_webhook_parse_failed errorType={}", e.getClass().getSimpleName());

			// 外部呼叫：透過 Spring HTTP 回應 API 回報伺服器處理失敗。
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
		}
	}

	//#endregion

	//#region 事件處理

	// 方法：執行 handleEvent 方法的處理流程。
	private void handleEvent(JsonNode event) throws Exception {
		if (!"message".equals(getSafeText(event, "type"))) return;

		// 步驟 1：從 Jackson 節點取出訊息與來源資料，供後續路由使用。
		JsonNode message = event.get("message");
		if (message == null) return;

		String replyToken = getSafeText(event, "replyToken");
		JsonNode source = event.get("source");
		String sourceType = getSafeText(source, "type");
		String sourceId = resolveSourceId(source);
		String uploaderId = getSafeText(source, "userId");
		String messageType = getSafeText(message, "type");

		// 步驟 2：依 LINE 訊息類型分派至圖片歸檔或文字指令流程。
		switch (messageType) {
			case "image" -> {
				JsonNode imageSet = message.get("imageSet");
				handleImage(
					getSafeText(message, "id"),
					getSafeText(imageSet, "id"),
					getSafeInt(imageSet, "index", 1),
					getSafeInt(imageSet, "total", 1),
					sourceType,
					sourceId,
					uploaderId,
					replyToken);
			}
			case "text" -> commandService.handleText(
				getSafeText(message, "text"),
				getSafeText(message, "quotedMessageId"),
				sourceId,
				uploaderId != null ? uploaderId : sourceId,
				replyToken);
			default -> { /* 貼圖、影片、位置等目前不收錄 */
			}
		}
	}

	// 方法：執行 handleImage 方法的處理流程。
	private void handleImage(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String sourceType,
		String sourceId,
		String uploaderId,
		String replyToken
	) throws Exception {
		if (messageId == null) return;

		// 步驟 1：透過 LINE API 下載圖片內容。
		LineStorageService.LineContent content = lineService.downloadContent(messageId);
		if (content == null) {
			imageArchiveService.recordFetchFailure(
				messageId,
				imageSetId,
				imageIndex,
				imageTotal,
				sourceId
			);
			return;
		}

		// 步驟 2：將下載結果交給待歸檔服務保存，等待使用者確認。
		imageArchiveService.stage(
			messageId,
			imageSetId,
			imageIndex,
			imageTotal,
			sourceType,
			sourceId,
			uploaderId,
			content.stream(),
			content.contentType()
		);
	}

	//#endregion

	//#region 欄位解析與簽章

	/** 群組、多人聊天室、一對一各有不同的識別欄位，統一成一個 sourceId 供查詢時分隔資料。 */
	// 方法：執行 resolveSourceId 方法的處理流程。
	private String resolveSourceId(JsonNode source) {
		if (source == null) return null;

		String groupId = getSafeText(source, "groupId");
		if (groupId != null) return groupId;

		String roomId = getSafeText(source, "roomId");
		return roomId != null ? roomId : getSafeText(source, "userId");
	}

	// 方法：執行 getSafeText 方法的處理流程。
	private String getSafeText(JsonNode parentNode, String fieldName) {
		if (parentNode == null) return null;

		// 外部呼叫：透過 Jackson 安全取得指定欄位，再確認它能以文字型態讀取。
		JsonNode childNode = parentNode.get(fieldName);
		return childNode != null && childNode.isString() ? childNode.stringValue() : null;
	}

	// 方法：執行 getSafeInt 方法的處理流程。
	private int getSafeInt(JsonNode parentNode, String fieldName, int defaultValue) {
		if (parentNode == null) return defaultValue;

		// 外部呼叫：透過 Jackson 安全取得指定欄位，再確認它能以整數型態讀取。
		JsonNode childNode = parentNode.get(fieldName);
		return childNode != null && childNode.isNumber() ? childNode.intValue() : defaultValue;
	}

	// 方法：執行 verifySignature 方法的處理流程。
	private boolean verifySignature(String payload, String headerSignature) {
		try {
			// 步驟 1：使用 JCA 建立 LINE 指定的 HMAC-SHA256 驗證器。
			SecretKeySpec keySpec = new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(keySpec);

			// 步驟 2：計算本文摘要，並透過 Base64 轉成 LINE 簽章格式。
			byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			String expected = Base64.getEncoder().encodeToString(rawHmac);

			// 步驟 3：使用 JCA 常數時間比較，避免攻擊者由回應時間推敲簽章。
			return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				headerSignature.getBytes(StandardCharsets.UTF_8)
			);
		}
		catch (Exception e) {
			return false;
		}
	}

	//#endregion
}
