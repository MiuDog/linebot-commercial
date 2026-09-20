package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationEventReceiptRepository;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuotationLineWorkflowService {

	private final QuotationDraftWorkflowPort port;
	private final QuotationEventReceiptRepository receipts;
	private final QuotationConversationService conversation;
	private final QuotationPostbackSigner signer;
	private final QuotationLineMessageBuilder messages;
	private final QuotationConfirmationService confirmations;
	private final QuotationPendingImagePreviewService imagePreviews;
	private final QuotationGenerationLauncher generationLauncher;
	private final QuotationReplyOutboxService replyOutbox;
	private final TransactionTemplate transactions;

	// 方法：初始化 LINE 報價應用流程。
	@Autowired
	public QuotationLineWorkflowService(
		QuotationDraftWorkflowPort port,
		QuotationEventReceiptRepository receipts,
		QuotationConversationService conversation,
		QuotationPostbackSigner signer,
		QuotationLineMessageBuilder messages,
		QuotationConfirmationService confirmations,
		QuotationPendingImagePreviewService imagePreviews,
		QuotationGenerationLauncher generationLauncher,
		QuotationReplyOutboxService replyOutbox,
		PlatformTransactionManager transactionManager
	) {
		this.port = port;
		this.receipts = receipts;
		this.conversation = conversation;
		this.signer = signer;
		this.messages = messages;
		this.confirmations = confirmations;
		this.imagePreviews = imagePreviews;
		this.generationLauncher = generationLauncher;
		this.replyOutbox = replyOutbox;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	// 方法：保留不啟用 durable reply 交易的既有整合建構介面。
	public QuotationLineWorkflowService(
		QuotationDraftWorkflowPort port,
		QuotationEventReceiptRepository receipts,
		QuotationConversationService conversation,
		QuotationPostbackSigner signer,
		QuotationLineMessageBuilder messages,
		QuotationConfirmationService confirmations,
		QuotationPendingImagePreviewService imagePreviews,
		QuotationGenerationLauncher generationLauncher
	) {
		this.port = port;
		this.receipts = receipts;
		this.conversation = conversation;
		this.signer = signer;
		this.messages = messages;
		this.confirmations = confirmations;
		this.imagePreviews = imagePreviews;
		this.generationLauncher = generationLauncher;
		this.replyOutbox = null;
		this.transactions = null;
	}

	// 方法：保留不需要圖片網址的單元測試建構介面。
	QuotationLineWorkflowService(
		QuotationDraftWorkflowPort port,
		QuotationEventReceiptRepository receipts,
		QuotationConversationService conversation,
		QuotationPostbackSigner signer,
		QuotationLineMessageBuilder messages,
		QuotationConfirmationService confirmations
	) {
		this(port, receipts, conversation, signer, messages, confirmations, null, null);
	}

	// 方法：提供 LINE 確認背景產生整合的聚焦測試建構介面。
	QuotationLineWorkflowService(
		QuotationDraftWorkflowPort port,
		QuotationEventReceiptRepository receipts,
		QuotationConversationService conversation,
		QuotationPostbackSigner signer,
		QuotationLineMessageBuilder messages,
		QuotationConfirmationService confirmations,
		QuotationGenerationLauncher generationLauncher
	) {
		this(port, receipts, conversation, signer, messages, confirmations, null, generationLauncher);
	}

	// 方法：判斷文字是否應交由一對一報價流程處理。
	public boolean isQuotationText(String ownerId, String text) {
		if (text == null) return false;

		return QuotationSchemeKeywords.hasDirective(text) || port.hasActiveDraft(ownerId);
	}

	// 方法：以事件冪等保護文字補件並產生 LINE 回覆。
	public List<QuotationLineMessage> handleText(String eventId, String messageId, String ownerId, String text) {
		return handleText(eventId, messageId, ownerId, text, null);
	}

	// 方法：處理文字與引用圖片，讓 AI 可執行名片 OCR 或候選圖評分。
	public List<QuotationLineMessage> handleText(
		String eventId,
		String messageId,
		String ownerId,
		String text,
		String quotedImageMessageId
	) {
		String receiptId = requiredEventId(eventId, messageId);
		try {
			return handleTextInTransaction(
				receiptId,
				messageId,
				ownerId,
				text,
				quotedImageMessageId
			);
		}
		catch (RuntimeException exception) {
			receipts.fail(receiptId, null);
			throw exception;
		}
	}

	// 方法：在 durable reply 交易內完成文字 mutation、outbox 與 receipt。
	private List<QuotationLineMessage> handleTextInTransaction(
		String receiptId,
		String messageId,
		String ownerId,
		String text,
		String quotedImageMessageId
	) {
		if (!receipts.claim(receiptId, null, "MESSAGE")) return List.of();

		QuotationDraftWork work = port.applyText(ownerId, messageId, text, quotedImageMessageId);
		return completeWorkReply(receiptId, ownerId, work);
	}

	// 方法：在 LINE 五則 reply 限制內附上已選候選圖的 HTTPS 預覽。
	private List<QuotationLineMessage> withImagePreview(
		List<QuotationLineMessage> current,
		QuotationDraftWork work,
		String ownerId
	) {
		if (imagePreviews == null || current.size() >= 5) return current;

		QuotationImagePreview preview = imagePreviews.issueSelected(work.draft().draftId(), ownerId);
		if (preview == null) return current;

		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("type", "image");
		payload.put("originalContentUrl", preview.originalUrl());
		payload.put("previewImageUrl", preview.thumbnailUrl());
		List<QuotationLineMessage> result = new ArrayList<>();
		result.add(new QuotationLineMessage(payload));
		result.addAll(current);
		return List.copyOf(result);
	}

	// 方法：以事件冪等保護圖片補件並產生 LINE 回覆。
	public List<QuotationLineMessage> handleImage(String eventId, String messageId, String ownerId) {
		return handleImages(eventId, List.of(messageId), ownerId);
	}

	// 方法：以單一事件冪等保護已收齊的 LINE imageSet，並一次評估全部候選圖。
	public List<QuotationLineMessage> handleImages(String eventId, List<String> messageIds, String ownerId) {
		if (messageIds == null || messageIds.isEmpty()) return List.of();

		String receiptId = requiredEventId(eventId, messageIds.getLast());
		try {
			return handleImagesInTransaction(receiptId, messageIds, ownerId);
		}
		catch (RuntimeException exception) {
			receipts.fail(receiptId, null);
			throw exception;
		}
	}

	// 方法：在 durable reply 交易內完成圖片 mutation、outbox 與 receipt。
	private List<QuotationLineMessage> handleImagesInTransaction(
		String receiptId,
		List<String> messageIds,
		String ownerId
	) {
		if (!receipts.claim(receiptId, null, "MESSAGE")) return List.of();

		QuotationDraftWork work = port.attachImages(ownerId, messageIds);
		return completeWorkReply(receiptId, ownerId, work);
	}

	// 方法：驗證簽章、使用者、期限與 revision 後執行允許的 postback 動作。
	public List<QuotationLineMessage> handlePostback(String eventId, String ownerId, String data) {
		String receiptId = requiredEventId(eventId, null);
		try {
			return executeDurably(() -> handlePostbackInTransaction(receiptId, ownerId, data));
		}
		catch (RuntimeException exception) {
			receipts.fail(receiptId, null);
			throw exception;
		}
	}

	// 方法：在 durable reply 交易內完成 postback mutation、outbox 與 receipt。
	private List<QuotationLineMessage> handlePostbackInTransaction(
		String receiptId,
		String ownerId,
		String data
	) {
		if (receipts.blocksReplay(receiptId)) return List.of();

		QuotationVerifiedPostback verified = signer.verify(
			data,
			ownerId,
			draftId -> port.currentRevision(draftId, ownerId)
		);

		if (verified.action() != QuotationPostbackAction.CONFIRM
			&& !receipts.claim(receiptId, verified.draftId(), "POSTBACK")) {
			return List.of();
		}

		QuotationDraftWork work = port.load(verified.draftId(), ownerId);
		return applyPostback(receiptId, ownerId, verified, work);
	}

	// 方法：執行經驗證且由應用層允許的 postback 動作。
	private List<QuotationLineMessage> applyPostback(
		String eventId,
		String ownerId,
		QuotationVerifiedPostback verified,
		QuotationDraftWork work
	) {
		if (verified.action() == QuotationPostbackAction.CONFIRM) {
			QuotationConfirmationIntent intent = conversation.confirm(work.draft(), eventId);
			// 應用服務：只有通過簽章與狀態機的確認動作可以建立正式報價。
			QuotationConfirmationResult confirmation = confirmations.confirm(
				new QuotationConfirmationCommand(intent, work.calculation())
			);
			if (generationLauncher != null) {
				generationLauncher.launch(new QuotationConfirmedGenerationCommand(
					confirmation,
					work.calculation(),
					header(intent.draft()),
					ownerId
				));
			}
			List<QuotationLineMessage> result = List.of(messages.generationAccepted(confirmation));
			return completeReply(eventId, ownerId, verified.draftId(), result);
		}
		if (verified.action() == QuotationPostbackAction.MODIFY) {
			QuotationDraftWork changed = port.requestModification(work);
			port.save(changed.draft());
			return completeReply(
				eventId,
				ownerId,
				verified.draftId(),
				List.of(messages.modificationRequest(changed.draft(), ownerId))
			);
		}
		if (verified.action() == QuotationPostbackAction.IMAGE_OPTIONS_PAGE) {
			QuotationLineMessage page = messages.imageOptionsPage(
				work.draft(),
				ownerId,
				imageOptionsPage(verified.resourceId())
			);
			return completeReply(eventId, ownerId, verified.draftId(), List.of(page));
		}

		QuotationDraftWork changedWork = switch (verified.action()) {
			case CANCEL -> new QuotationDraftWork(conversation.cancel(work.draft()), work.calculation());
			case DECLINE_IMAGE -> persistPatch(
				conversation.applyPatch(
					work.draft(),
					new QuotationDraftPatch(Map.of(), Map.of(), List.of(), true, null)
				),
				work.calculation()
			);
			case SELECT_IMAGE -> port.selectImage(
				verified.draftId(),
				ownerId,
				verified.resourceId()
			);
			case REMOVE_IMAGE -> port.removeSelectedImage(verified.draftId(), ownerId);
			case SELECT_SCHEME -> port.applyScheme(
				verified.draftId(),
				ownerId,
				verified.resourceId()
			);
			case MODIFY, CONFIRM, IMAGE_OPTIONS_PAGE -> throw new IllegalStateException("動作已於前段處理。");

		};
		List<QuotationLineMessage> result = buildAndPersist(changedWork, ownerId);
		return completeReply(eventId, ownerId, verified.draftId(), result);
	}

	// 方法：先保存 postback 造成的草稿修改，再由狀態機建立下一個可見步驟。
	private QuotationDraftWork persistPatch(
		QuotationConversationDecision decision,
		QuotationCalculationResult calculation
	) {
		port.save(decision.draft());
		return new QuotationDraftWork(decision.draft(), calculation);
	}

	// 方法：將簽章保護的圖片候選頁碼轉成非負整數。
	private int imageOptionsPage(String resourceId) {
		try {
			int page = Integer.parseInt(resourceId);
			if (page < 0) throw new NumberFormatException("negative page");

			return page;
		}
		catch (RuntimeException exception) {
			throw error("INVALID_IMAGE_PAGE", "圖片候選頁碼已失效");
		}
	}

	// 方法：將已確認草稿的可信頂部欄位轉成報表抬頭，流水號與日期由協調器覆蓋。
	private QuotationWorkbookService.Header header(QuotationDraftSnapshot draft) {
		Map<String, String> fields = draft.baseFields();
		String phoneFax = value(fields, "phone");
		String fax = value(fields, "fax");
		if (!fax.isBlank()) phoneFax = phoneFax.isBlank() ? fax : phoneFax + " / " + fax;

		return new QuotationWorkbookService.Header(
			"",
			"",
			value(fields, "companyName"),
			phoneFax,
			value(fields, "email"),
			value(fields, "contactName"),
			value(fields, "projectLocation"),
			value(fields, "salesRepresentative"),
			""
		);
	}

	// 方法：安全取得可選報表抬頭文字。
	private String value(Map<String, String> fields, String key) {
		String value = fields.get(key);
		return value == null ? "" : value;
	}

	// 方法：由程式狀態機決定下一步，忽略 AI 回傳的任何動作提示。
	private List<QuotationLineMessage> buildAndPersist(QuotationDraftWork work, String ownerId) {
		QuotationConversationDecision decision = conversation.review(work.draft());
		if (decision.nextAction() == QuotationNextAction.REQUEST_IMAGE) {
			persistTransition(work.draft(), decision.draft());
			QuotationDraftSnapshot asked = conversation.markImageQuestionAsked(decision.draft());
			persistTransition(decision.draft(), asked);
			return build(
				new QuotationConversationDecision(asked, List.of(), List.of(), QuotationNextAction.REQUEST_IMAGE),
				ownerId,
				work.calculation()
			);
		}
		if (decision.nextAction() == QuotationNextAction.SHOW_PREVIEW) {
			persistTransition(work.draft(), decision.draft());
			QuotationDraftSnapshot presented = conversation.markPreviewPresented(decision.draft());
			persistTransition(decision.draft(), presented);
			return build(
				new QuotationConversationDecision(presented, List.of(), List.of(), QuotationNextAction.SHOW_PREVIEW),
				ownerId,
				work.calculation()
			);
		}

		port.save(decision.draft());
		return build(decision, ownerId, work.calculation());
	}

	// 方法：依序保存對話狀態轉換，避免一次跳過兩個 revision 導致 SQLite CAS 失敗。
	private void persistTransition(QuotationDraftSnapshot before, QuotationDraftSnapshot after) {
		if (after.revision() == before.revision()) return;

		port.save(after);
	}

	// 方法：把訊息模型轉交 builder，不直接呼叫 LINE 外部 API。
	private List<QuotationLineMessage> build(
		QuotationConversationDecision decision,
		String ownerId,
		QuotationCalculationResult calculation
	) {
		return messages.build(decision, ownerId, calculation);
	}

	// 方法：在同一 SQLite 交易中執行 mutation、outbox 與 receipt；測試建構介面則直接執行。
	private List<QuotationLineMessage> executeDurably(Supplier<List<QuotationLineMessage>> operation) {
		if (transactions == null) return operation.get();

		List<QuotationLineMessage> result = transactions.execute(status -> operation.get());
		if (result == null) throw error("DURABLE_REPLY_FAILED", "報價回覆交易沒有產生結果");

		return result;
	}

	// 方法：以短交易先持久化完整回覆，再將事件 receipt 原子標記完成。
	private List<QuotationLineMessage> completeReply(
		String eventId,
		String ownerId,
		long draftId,
		List<QuotationLineMessage> result
	) {
		if (transactions != null) {
			List<QuotationLineMessage> completed = transactions.execute(
				status -> completeReplyInTransaction(eventId, ownerId, draftId, result)
			);
			if (completed == null) throw error("DURABLE_REPLY_FAILED", "報價回覆交易沒有產生結果");

			return completed;
		}

		return completeReplyInTransaction(eventId, ownerId, draftId, result);
	}

	// 方法：AI/OCR 完成後，以短交易原子保存對話轉態、outbox 與 receipt。
	private List<QuotationLineMessage> completeWorkReply(
		String eventId,
		String ownerId,
		QuotationDraftWork work
	) {
		if (transactions != null) {
			List<QuotationLineMessage> completed = transactions.execute(status -> {
				List<QuotationLineMessage> result = withImagePreview(
					buildAndPersist(work, ownerId),
					work,
					ownerId
				);
				return completeReplyInTransaction(
					eventId,
					ownerId,
					work.draft().draftId(),
					result
				);
			});
			if (completed == null) throw error("DURABLE_REPLY_FAILED", "報價回覆交易未完成");

			return completed;
		}

		List<QuotationLineMessage> result = withImagePreview(buildAndPersist(work, ownerId), work, ownerId);
		return completeReplyInTransaction(eventId, ownerId, work.draft().draftId(), result);
	}

	// 方法：在目前交易中保存 outbox 並完成 receipt。
	private List<QuotationLineMessage> completeReplyInTransaction(
		String eventId,
		String ownerId,
		long draftId,
		List<QuotationLineMessage> result
	) {
		if (replyOutbox != null && result != null && !result.isEmpty()) {
			replyOutbox.stage(
				eventId,
				ownerId,
				result.stream().map(QuotationLineMessage::payload).toList()
			);
		}
		receipts.complete(eventId, draftId, false);
		return result == null ? List.of() : result;
	}

	// 方法：優先使用 webhookEventId，舊事件才退回 message id。
	private String requiredEventId(String eventId, String messageId) {
		String resolved = eventId == null || eventId.isBlank() ? messageId : eventId;
		if (resolved == null || resolved.isBlank()) {
			throw error("MISSING_EVENT_ID", "無法辨識 LINE 事件，請重新傳送。");
		}
		if (resolved.length() > 256) throw error("INVALID_EVENT_ID", "無法辨識 LINE 事件，請重新傳送。");

		return resolved;
	}

	// 方法：建立不洩漏內部狀態的流程例外。
	private QuotationLineWorkflowException error(String code, String message) {
		return new QuotationLineWorkflowException(code, message);
	}
}
