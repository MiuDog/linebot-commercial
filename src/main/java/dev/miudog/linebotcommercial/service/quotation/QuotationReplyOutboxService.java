package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.service.LineStorageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 將報價回覆持久化，使 mutation 完成後的 LINE reply 失敗可由重送事件改走 push。 */
@Service
public class QuotationReplyOutboxService {

	private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST = new TypeReference<>() {};
	private static final Logger log = LoggerFactory.getLogger(QuotationReplyOutboxService.class);

	private final JdbcTemplate jdbc;
	private final LineStorageService line;
	private final ObjectMapper objectMapper;

	// 方法：注入本機 outbox、LINE 邊界及 JSON 序列化器。
	public QuotationReplyOutboxService(JdbcTemplate jdbc, LineStorageService line, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.line = line;
		this.objectMapper = objectMapper;
	}

	// 方法：先持久化回覆，再嘗試 reply；reply 失敗時立即以穩定 retry key 改走 push。
	public void deliver(
		String eventId,
		String destinationId,
		String replyToken,
		List<Map<String, Object>> messages
	) {
		if (eventId == null || eventId.isBlank() || destinationId == null || destinationId.isBlank()) {
			throw new IllegalArgumentException("LINE 報價回覆缺少事件或目的地");
		}
		if (messages == null || messages.isEmpty()) return;

		stage(eventId, destinationId, messages);
		try {
			line.reply(replyToken, messages);
			markSent(eventId);
		}
		catch (LineStorageService.LineMessagingException exception) {
			recordFailure(eventId, exception.code());
			pushPending(eventId);
		}
	}

	// 方法：只持久化待送回覆，供工作流程與 mutation、receipt 在同一交易提交。
	public void stage(
		String eventId,
		String destinationId,
		List<Map<String, Object>> messages
	) {
		if (eventId == null || eventId.isBlank() || destinationId == null || destinationId.isBlank()) {
			throw new IllegalArgumentException("LINE 報價回覆缺少事件或目的地");
		}
		if (messages == null || messages.isEmpty()) return;

		String payload = writeMessages(messages);
		// 資料庫 API：事件主鍵確保 mutation 重複送達時不覆寫原始回覆內容。
		jdbc.update("""
			INSERT INTO quotation_reply_outbox (
				event_id, destination_id, messages_json, status
			)
			VALUES (?, ?, ?, 'PENDING')
			ON CONFLICT (event_id) DO NOTHING
			""", eventId, destinationId, payload);
	}

	// 方法：LINE 重送同一事件而 mutation 被冪等略過時，只重送尚未完成的 outbox 回覆。
	public void retryPending(String eventId) {
		if (eventId == null || eventId.isBlank()) return;

		pushPending(eventId);
	}

	// 方法：啟動時及固定週期重新推送未完成回覆，讓程序重啟後不依賴 webhook 再投遞。
	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(fixedDelayString = "${quotation.reply-outbox.retry-delay-ms:30000}")
	public void drainPending() {
		// 資料庫：每輪限制待處理筆數，避免長時間占用排程執行緒。
		List<String> eventIds = jdbc.queryForList("""
			SELECT event_id
			FROM quotation_reply_outbox
			WHERE status = 'PENDING'
			ORDER BY updated_at, event_id
			LIMIT 100
			""", String.class);
		for (String eventId : eventIds) {
			try {
				pushPending(eventId);
			}
			catch (RuntimeException exception) {
				// 日誌：只記錄錯誤類型，待下一輪以相同 retry key 重送。
				log.warn("event=quotation_reply_outbox_retry_failed errorType={}", exception.getClass().getSimpleName());
			}
		}
	}

	// 方法：讀取單筆 pending 回覆並使用跨程序穩定的 LINE retry key 推播。
	private void pushPending(String eventId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT destination_id, messages_json
			FROM quotation_reply_outbox
			WHERE event_id = ? AND status = 'PENDING'
			""", eventId);
		if (rows.isEmpty()) return;

		Map<String, Object> row = rows.getFirst();
		String destinationId = row.get("destination_id").toString();
		List<Map<String, Object>> messages = readMessages(row.get("messages_json").toString());
		try {
			line.push(destinationId, messages, retryKey(eventId, destinationId));
			markSent(eventId);
		}
		catch (LineStorageService.LineMessagingException exception) {
			recordFailure(eventId, exception.code());
			throw exception;
		}
	}

	// 方法：將 outbox 設為完成並清除穩定錯誤碼。
	private void markSent(String eventId) {
		jdbc.update("""
			UPDATE quotation_reply_outbox
			SET status = 'SENT', attempt_count = attempt_count + 1,
				last_error_code = NULL, updated_at = CURRENT_TIMESTAMP,
				completed_at = CURRENT_TIMESTAMP
			WHERE event_id = ? AND status = 'PENDING'
			""", eventId);
	}

	// 方法：保存不含目的地、回覆權杖或訊息本文的穩定失敗代碼。
	private void recordFailure(String eventId, String errorCode) {
		jdbc.update("""
			UPDATE quotation_reply_outbox
			SET attempt_count = attempt_count + 1, last_error_code = ?,
				updated_at = CURRENT_TIMESTAMP
			WHERE event_id = ? AND status = 'PENDING'
			""", errorCode, eventId);
	}

	// 方法：安全序列化最多五則 LINE payload 供持久重試。
	private String writeMessages(List<Map<String, Object>> messages) {
		try {
			return objectMapper.writeValueAsString(messages.size() > 5 ? messages.subList(0, 5) : messages);
		}
		catch (Exception exception) {
			throw new IllegalStateException("LINE 報價回覆無法序列化", exception);
		}
	}

	// 方法：將資料庫 JSON 還原為 LINE 訊息列表，不執行其中任何文字內容。
	private List<Map<String, Object>> readMessages(String payload) {
		try {
			return objectMapper.readValue(payload, MESSAGE_LIST);
		}
		catch (Exception exception) {
			throw new IllegalStateException("LINE 報價回覆資料損壞", exception);
		}
	}

	// 方法：由事件及目的地建立不揭露原文且重啟後一致的 UUID。
	private UUID retryKey(String eventId, String destinationId) {
		String material = "quotation-reply:" + eventId + ":" + destinationId;
		return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
	}
}
