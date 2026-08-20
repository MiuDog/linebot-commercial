package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.CommandService;
import dev.miudog.linebotcommercial.service.ImageArchiveService;
import dev.miudog.linebotcommercial.service.LineStorageService;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineMessage;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineFailureMessageResolver;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineWorkflowException;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineWorkflowService;
import dev.miudog.linebotcommercial.service.quotation.QuotationPostbackException;
import dev.miudog.linebotcommercial.service.quotation.QuotationReplyOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

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
	private final QuotationLineWorkflowService quotationWorkflow;
	private final QuotationReplyOutboxService replyOutbox;
	private final ObjectMapper objectMapper = new ObjectMapper();

	//#region 初始化與 Webhook 入口

	// 方法：初始化 LineWebhookController。
	@Autowired
	public LineWebhookController(
		CommandService commandService,
		ImageArchiveService imageArchiveService,
		LineStorageService lineService,
		QuotationLineWorkflowService quotationWorkflow,
		QuotationReplyOutboxService replyOutbox
	) {
		this.commandService = commandService;
		this.imageArchiveService = imageArchiveService;
		this.lineService = lineService;
		this.quotationWorkflow = quotationWorkflow;
		this.replyOutbox = replyOutbox;
	}

	// 方法：保留控制器聚焦測試使用的無 outbox 建構介面。
	LineWebhookController(
		CommandService commandService,
		ImageArchiveService imageArchiveService,
		LineStorageService lineService,
		QuotationLineWorkflowService quotationWorkflow
	) {
		this(commandService, imageArchiveService, lineService, quotationWorkflow, null);
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
		String eventType = getSafeText(event, "type");
		if ("postback".equals(eventType)) {
			handlePostback(event);
			return;
		}
		if (!"message".equals(eventType)) return;

		// 步驟 1：從 Jackson 節點取出訊息與來源資料，供後續路由使用。
		JsonNode message = event.get("message");
		if (message == null) return;

		String replyToken = getSafeText(event, "replyToken");
		JsonNode source = event.get("source");
		String sourceType = getSafeText(source, "type");
		String sourceId = resolveSourceId(source);
		String uploaderId = getSafeText(source, "userId");
		String messageType = getSafeText(message, "type");
		String eventId = getSafeText(event, "webhookEventId");

		// 步驟 2：依 LINE 訊息類型分派至圖片歸檔或文字指令流程。
		switch (messageType) {
			case "image" -> {
				JsonNode imageSet = message.get("imageSet");
				String messageId = getSafeText(message, "id");
				String imageSetId = getSafeText(imageSet, "id");
				int imageIndex = getSafeInt(imageSet, "index", 1);
				int imageTotal = getSafeInt(imageSet, "total", 1);
				handleImage(
					messageId,
					imageSetId,
					imageIndex,
					imageTotal,
					sourceType,
					sourceId,
					uploaderId,
					replyToken);
				if ("user".equals(sourceType) && quotationWorkflow.isQuotationText(uploaderId, "")) {
					List<String> completedSet = imageArchiveService.completedSetMessageIds(
						sourceId,
						imageSetId,
						messageId,
						imageTotal
					);
					if (completedSet.isEmpty()) return;

					try {
						replyQuotationMessages(
							eventId,
							uploaderId,
							replyToken,
							quotationWorkflow.handleImages(eventId, completedSet, uploaderId)
						);
					}
					catch (QuotationLineWorkflowException exception) {
						replyQuotationFailure(
							replyToken,
							QuotationLineFailureMessageResolver.Operation.IMAGE,
							exception
						);
					}
					catch (RuntimeException exception) {
						replyQuotationFailure(
							replyToken,
							QuotationLineFailureMessageResolver.Operation.IMAGE,
							exception
						);
					}
				}
			}
			case "text" -> handleTextMessage(
				eventId,
				getSafeText(message, "id"),
				getSafeText(message, "text"),
				getSafeText(message, "quotedMessageId"),
				resolveSelfMentionText(message),
				getSafeLong(event, "timestamp", 0L),
				sourceType,
				sourceId,
				uploaderId,
				replyToken
			);
			default -> { /* 語音、貼圖、影片、位置等目前不收錄 */
			}
		}
	}

	// 方法：限制報價文字只在一對一來源建立或修改草稿。
	private void handleTextMessage(
		String eventId,
		String messageId,
		String text,
		String quotedMessageId,
		String selfMentionText,
		long eventTimestamp,
		String sourceType,
		String sourceId,
		String uploaderId,
		String replyToken
	) {
		// 步驟 0：標記機器人的 ping 是連線自我檢查，優先於其他文字流程處理。
		if (selfMentionText != null && commandService.handleMentionPing(selfMentionText, eventTimestamp, replyToken)) return;

		if (!"user".equals(sourceType) && isQuotationCommand(text)) {
			lineService.replyText(replyToken, "報價建立僅支援一對一私訊，請私訊機器人後重新輸入。");
			return;
		}
		if ("user".equals(sourceType) && quotationWorkflow.isQuotationText(uploaderId, text)) {
			try {
				replyQuotationMessages(
					eventId,
					uploaderId,
					replyToken,
					quotationWorkflow.handleText(eventId, messageId, uploaderId, text, quotedMessageId)
				);
			}
			catch (QuotationLineWorkflowException exception) {
				replyQuotationFailure(
					replyToken,
					QuotationLineFailureMessageResolver.Operation.TEXT,
					exception
				);
			}
			catch (RuntimeException exception) {
				replyQuotationFailure(
					replyToken,
					QuotationLineFailureMessageResolver.Operation.TEXT,
					exception
				);
			}
			return;
		}

		commandService.handleText(
			text,
			quotedMessageId,
			sourceId,
			uploaderId != null ? uploaderId : sourceId,
			replyToken
		);
	}

	// 方法：只接受一對一來源的已簽章報價 postback。
	private void handlePostback(JsonNode event) {
		JsonNode source = event.get("source");
		if (!"user".equals(getSafeText(source, "type"))) return;

		String replyToken = getSafeText(event, "replyToken");
		String userId = getSafeText(source, "userId");
		JsonNode postback = event.get("postback");
		try {
			replyQuotationMessages(
				getSafeText(event, "webhookEventId"),
				userId,
				replyToken,
				quotationWorkflow.handlePostback(
					getSafeText(event, "webhookEventId"),
					userId,
					getSafeText(postback, "data")
				)
			);
		}
		catch (QuotationPostbackException exception) {
			replyQuotationFailure(
				replyToken,
				QuotationLineFailureMessageResolver.Operation.POSTBACK,
				exception
			);
		}
		catch (QuotationLineWorkflowException exception) {
			replyQuotationFailure(
				replyToken,
				QuotationLineFailureMessageResolver.Operation.POSTBACK,
				exception
			);
		}
		catch (RuntimeException exception) {
			replyQuotationFailure(
				replyToken,
				QuotationLineFailureMessageResolver.Operation.POSTBACK,
				exception
			);
		}
	}

	// 方法：以穩定代碼記錄不含個資的錯誤摘要，並回覆可行動的 LINE 說明。
	private void replyQuotationFailure(
		String replyToken,
		QuotationLineFailureMessageResolver.Operation operation,
		RuntimeException exception
	) {
		QuotationLineFailureMessageResolver.Failure failure = QuotationLineFailureMessageResolver.resolve(
			exception,
			operation
		);
		// 日誌：只記錄操作、穩定代碼與例外類型，不記錄 LINE 內容或外部錯誤本文。
		log.atWarn()
			.addKeyValue("event", "quotation_line_request_failed")
			.addKeyValue("operation", operation.name())
			.addKeyValue("errorCode", failure.code())
			.addKeyValue("errorType", exception.getClass().getSimpleName())
			.log(
				"event={} operation={} errorCode={} errorType={}",
				"quotation_line_request_failed",
				operation.name(),
				failure.code(),
				exception.getClass().getSimpleName()
			);
		lineService.replyText(replyToken, failure.message());
	}

	// 方法：將內部訊息模型轉成既有 LINE 發送介面需要的 payload。
	private void replyQuotationMessages(
		String eventId,
		String destinationId,
		String replyToken,
		List<QuotationLineMessage> messages
	) {
		if (replyOutbox == null) {
			if (messages == null || messages.isEmpty()) return;

			lineService.reply(replyToken, messages.stream().map(QuotationLineMessage::payload).toList());
			return;
		}

		if (messages == null || messages.isEmpty()) {
			replyOutbox.retryPending(eventId);
			return;
		}

		replyOutbox.deliver(
			eventId,
			destinationId,
			replyToken,
			messages.stream().map(QuotationLineMessage::payload).toList()
		);
	}

	// 方法：辨識群組中必須明確忽略的報價指令。
	private boolean isQuotationCommand(String text) {
		return text != null && text.strip().startsWith("#報價");
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

	/**
	 * 取出「標記本機器人」之後真正輸入的內容，讓 ping 之類的呼叫指令不受顯示名稱影響。
	 *
	 * <p>LINE 會在 {@code message.mention.mentionees} 標出每個標記在文字中的位置，
	 * 其中 {@code isSelf} 為真者即為本機器人；把這些片段從原文剪掉後剩下的就是指令本身。
	 *
	 * @return 去掉自身標記後的文字；本機器人未被標記時回傳 null
	 */
	// 方法：取出標記本機器人後剩下的指令文字。
	private String resolveSelfMentionText(JsonNode message) {
		JsonNode mentionees = message == null ? null : message.path("mention").get("mentionees");
		if (mentionees == null || !mentionees.isArray()) return null;

		String text = getSafeText(message, "text");
		if (text == null) return null;

		StringBuilder remaining = new StringBuilder(text);
		boolean mentionsSelf = false;
		// 步驟 1：由後往前剪掉自身標記，避免前面的刪除影響後面的索引位置。
		List<JsonNode> selfMentions = new ArrayList<>();
		for (JsonNode mentionee : mentionees) {
			JsonNode isSelf = mentionee.get("isSelf");
			if (isSelf != null && isSelf.booleanValue()) selfMentions.add(mentionee);
		}
		selfMentions.sort(Comparator.comparingInt(node -> -getSafeInt(node, "index", -1)));
		for (JsonNode mentionee : selfMentions) {
			int index = getSafeInt(mentionee, "index", -1);
			int length = getSafeInt(mentionee, "length", 0);
			mentionsSelf = true;
			if (index < 0 || length <= 0 || index >= remaining.length()) continue;

			remaining.delete(index, Math.min(index + length, remaining.length()));
		}
		return mentionsSelf ? remaining.toString().trim() : null;
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

	// 方法：執行 getSafeLong 方法的處理流程。
	private long getSafeLong(JsonNode parentNode, String fieldName, long defaultValue) {
		if (parentNode == null) return defaultValue;

		// 外部呼叫：透過 Jackson 安全取得指定欄位，再確認它能以長整數型態讀取。
		JsonNode childNode = parentNode.get(fieldName);
		return childNode != null && childNode.isNumber() ? childNode.longValue() : defaultValue;
	}

	// 方法：執行 verifySignature 方法的處理流程。
	private boolean verifySignature(String payload, String headerSignature) {
		if (channelSecret == null || channelSecret.isBlank()) return false;

		if (payload == null || headerSignature == null || headerSignature.isBlank()) return false;

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
