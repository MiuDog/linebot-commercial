package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.PendingImageRepository;
import dev.miudog.linebotcommercial.service.FileStorageService;
import dev.miudog.linebotcommercial.service.ai.AiImageInput;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

public class SqliteQuotationDraftWorkflowPort implements QuotationDraftWorkflowPort {

	private final JdbcTemplate jdbc;
	private final QuotationAiParsingService parser;
	private final QuotationCalculationService calculator;
	private final PendingImageRepository pendingImages;
	private final FileStorageService storage;
	private final TransactionTemplate transactions;

	// 方法：初始化 SQLite 報價草稿 adapter。
	public SqliteQuotationDraftWorkflowPort(
		JdbcTemplate jdbc,
		QuotationAiParsingService parser,
		QuotationCalculationService calculator,
		PendingImageRepository pendingImages,
		FileStorageService storage,
		PlatformTransactionManager transactionManager
	) {
		this.jdbc = jdbc;
		this.parser = parser;
		this.calculator = calculator;
		this.pendingImages = pendingImages;
		this.storage = storage;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	// 方法：查詢一對一使用者是否有活動草稿。
	@Override
	public boolean hasActiveDraft(String ownerId) {
		if (ownerId == null || ownerId.isBlank()) return false;

		// 資料庫：只計算 user source 的活動草稿，避免群組或他人草稿混入。
		Integer count = jdbc.queryForObject("""
			SELECT COUNT(*) FROM quotation_draft
			WHERE source_type = 'user' AND source_id = ?
				AND status IN (
					'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
					'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION'
				)
			""",
			Integer.class,
			ownerId
		);
		return count != null && count > 0;
	}

	// 方法：以 AI 解析文字，合併低信心缺漏後保存草稿。
	@Override
	public QuotationDraftWork applyText(String ownerId, String messageId, String text) {
		return applyText(ownerId, messageId, text, null);
	}

	// 方法：解析文字及引用圖片，讓名片 OCR 與報價補件在同一輪完成。
	@Override
	public QuotationDraftWork applyText(
		String ownerId,
		String messageId,
		String text,
		String quotedImageMessageId
	) {
		requireOwner(ownerId);
		if (messageId == null || messageId.isBlank()) throw error("MISSING_MESSAGE_ID", "無法辨識 LINE 訊息，請重新傳送。");

		Optional<QuotationDraftSnapshot> processedMessage = findCommittedMessage(ownerId, messageId);
		if (processedMessage.isPresent()) return work(processedMessage.get());

		QuotationAiParsingService.ParseResult csv = text != null && text.startsWith(QuotationInputCsvService.PREFIX)
			? parser.parseCsv(text.substring(QuotationInputCsvService.PREFIX.length()))
			: null;

		QuotationDraftSnapshot base = transactions.execute(status -> findOrCreateActiveDraft(ownerId));
		if (base == null) throw error("DRAFT_LOAD_FAILED", "無法載入報價草稿");

		QuotationDraftSnapshot inputDraft = includeQuotedImage(base, ownerId, quotedImageMessageId);
		// 應用服務：報價格式只由使用者指定；尚未指定時不呼叫 AI，改由狀態機要求先選格式。
		String lockedSchemeCode = inputDraft.schemeCode() == null
			? (csv == null ? QuotationSchemeKeywords.parse(text) : csv.request().schemeCode())
			: inputDraft.schemeCode();
		if (lockedSchemeCode == null) {
			// 資料庫：選格式前也保存原文，之後可續接解析而不要求使用者重貼。
			transactions.executeWithoutResult(status -> {
				QuotationDraftSnapshot current = loadSnapshot(inputDraft.draftId(), ownerId);
				requireRevision(current, inputDraft.revision());
				saveMessage(inputDraft.draftId(), messageId, "TEXT", text, null);
				saveCas(copyScheme(current, null), current.revision());
			});
			return work(inputDraft(inputDraft, ownerId));
		}

		// 本張格式已鎖定時，新的明確報價指令不可靜默併入舊格式。
		String requestedScheme = text != null && text.strip().startsWith("#報價") ? QuotationSchemeKeywords.parse(text) : null;
		if (requestedScheme != null && inputDraft.schemeCode() != null && !requestedScheme.equals(inputDraft.schemeCode())) {
			throw error("DRAFT_SCHEME_CONFLICT", "目前仍有「" + QuotationSchemeKeywords.displayName(inputDraft.schemeCode())
				+ "」草稿。請先按取消報價，再以 #報價 建立新的格式；既有資料已保留。");
		}

		if (csv != null && !lockedSchemeCode.equals(csv.request().schemeCode())) {
			throw error("CSV_SCHEME_CONFLICT", "CSV 格式與目前草稿不同，請先取消草稿再匯入。");
		}

		QuotationDraftSnapshot schemedDraft = inputDraft.schemeCode() == null
			? transactions.execute(status -> commitScheme(inputDraft, ownerId, lockedSchemeCode))
			: inputDraft;
		if (schemedDraft == null) throw error("DRAFT_SAVE_FAILED", "無法保存報價草稿");

		List<AiImageInput> images = csv != null || quotedImageMessageId == null ? List.of() : imageInputs(schemedDraft);
		QuotationAiParsingService.ParseResult parsed = csv;
		if (parsed == null) {
			parsed = parser.parseDraft(withPendingInput(schemedDraft.draftId(), text), images, schemedDraft, new QuotationModelWorkflow.Scope(ownerId,
				schemedDraft.draftId() + ":" + schemedDraft.revision() + ":" + messageId));
		}
		QuotationAiParsingService.ParseResult accepted = parsed;
		QuotationDraftSnapshot committed = transactions.execute(status -> commitParsedText(
			schemedDraft,
			ownerId,
			messageId,
			text,
			quotedImageMessageId,
			accepted
		));
		if (committed == null) throw error("DRAFT_SAVE_FAILED", "無法保存報價草稿");

		return work(committed);
	}

	// 方法：讀回尚未指定格式的草稿，讓狀態機以目前實際狀態要求使用者先選格式。
	private QuotationDraftSnapshot inputDraft(QuotationDraftSnapshot draft, String ownerId) {
		return loadSnapshot(draft.draftId(), ownerId);
	}

	// 方法：以 CAS 保存使用者本次指定的報價格式。
	private QuotationDraftSnapshot commitScheme(
		QuotationDraftSnapshot draft,
		String ownerId,
		String schemeCode
	) {
		QuotationDraftSnapshot current = loadSnapshot(draft.draftId(), ownerId);
		if (current.schemeCode() != null) return current;

		QuotationDraftSnapshot schemed = copyScheme(current, schemeCode);
		saveCas(schemed, current.revision());
		QuotationDraftSnapshot stored = loadSnapshot(draft.draftId(), ownerId);
		// 保留本輪尚未寫入資料庫的引用圖片，讓後續解析仍能連結名片或案場原圖。
		return copyImages(
			stored,
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			draft.imageDeclined(),
			stored.revision()
		);
	}

	// 方法：套用使用者以按鈕指定的報價格式；已指定過的草稿不可改格式。
	@Override
	public QuotationDraftWork applyScheme(long draftId, String ownerId, String schemeCode) {
		if (!QuotationSchemeKeywords.isSupported(schemeCode)) {
			throw error("INVALID_SCHEME", "不支援的報價格式。");
		}

		QuotationDraftSnapshot draft = loadSnapshot(draftId, ownerId);
		if (draft.schemeCode() != null) return work(draft);

		QuotationDraftSnapshot schemed = transactions.execute(status -> commitScheme(draft, ownerId, schemeCode.trim().toUpperCase(Locale.ROOT)));
		if (pendingInput(draftId).isEmpty()) return work(schemed);

		return applyText(ownerId, "pending-scheme-" + draftId + "-" + schemed.revision(), "格式已選擇，請處理尚未解析的報價內容。");
	}

	// 方法：只取本張草稿尚未解析的文字，避免把歷次已套用資料再當成新輸入。
	private List<String> pendingInput(long draftId) {
		// 資料庫：未選格式時的原文以空 AI 回應標示，成功合併才標為已消費。
		return jdbc.query("""
			SELECT raw_text FROM quotation_draft_message
			WHERE draft_id = ? AND message_type = 'TEXT' AND ai_response_json IS NULL AND raw_text IS NOT NULL
			ORDER BY id
			""", (row, index) -> row.getString(1), draftId);
	}

	// 方法：保留首次指令與最新補答順序，只有未解析原文會再次送入模型。
	private String withPendingInput(long draftId, String text) {
		List<String> pending = pendingInput(draftId);
		return pending.isEmpty() ? text : String.join("\n", pending) + "\n" + text;
	}

	// 方法：複製草稿並只更換報價格式與 revision。
	private QuotationDraftSnapshot copyScheme(QuotationDraftSnapshot draft, String schemeCode) {
		return new QuotationDraftSnapshot(
			draft.draftId(),
			draft.revision() + 1,
			draft.status(),
			schemeCode,
			draft.baseFields(),
			draft.items(),
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			draft.imageQuestionAsked(),
			draft.imageDeclined(),
			false,
			null
		);
	}

	// 方法：驗證引用圖片屬於同一使用者，並納入 AI OCR 與候選評分。
	private QuotationDraftSnapshot includeQuotedImage(
		QuotationDraftSnapshot draft,
		String ownerId,
		String quotedImageMessageId
	) {
		if (quotedImageMessageId == null || quotedImageMessageId.isBlank()) return draft;

		PendingImageRepository.PendingImage pending = pendingImages.findByMessageId(quotedImageMessageId)
			.filter(image -> ownerId.equals(image.sourceId()))
			.orElseThrow(() -> error("IMAGE_NOT_FOUND", "找不到被引用的名片或案場圖片，請重新上傳。"));
		List<String> imageIds = new ArrayList<>(draft.imageMessageIds());
		if (!imageIds.contains(pending.messageId())) imageIds.add(pending.messageId());

		return copyImages(draft, imageIds, draft.selectedImageMessageId(), false, draft.revision());
	}

	// 方法：以短交易 CAS 合併文字解析結果，避免慢速 AI 回覆覆蓋較新的草稿。
	private QuotationDraftSnapshot commitParsedText(
		QuotationDraftSnapshot inputDraft,
		String ownerId,
		String messageId,
		String text,
		String quotedImageMessageId,
		QuotationAiParsingService.ParseResult parsed
	) {
		QuotationDraftSnapshot current = loadSnapshot(inputDraft.draftId(), ownerId);
		requireRevision(current, inputDraft.revision());
		QuotationDraftSnapshot merged = merge(inputDraft, parsed.request());
		saveCas(merged, inputDraft.revision());
		saveHeaderEvidence(merged.draftId(), messageId, parsed.request().headerPatch());
		if (quotedImageMessageId != null && merged.imageMessageIds().contains(quotedImageMessageId)) {
			linkImage(merged.draftId(), quotedImageMessageId);
		}
		mergeItems(merged.draftId(), parsed.request());
		saveImageAssessments(merged.draftId(), parsed.request());
		saveMessage(merged.draftId(), messageId, "TEXT", text, parsed.rawJson());
		// 資料庫：與草稿合併同一交易標記已解析，失敗時原文仍可重試。
		jdbc.update("UPDATE quotation_draft_message SET ai_response_json = '{}' WHERE draft_id = ? AND message_type = 'TEXT' AND ai_response_json IS NULL", merged.draftId());
		return loadSnapshot(merged.draftId(), ownerId);
	}

	// 方法：以短交易 CAS 合併圖片評估結果並連結候選原圖。
	private QuotationDraftSnapshot commitParsedImages(
		QuotationDraftSnapshot inputDraft,
		String ownerId,
		List<String> messageIds,
		QuotationAiParsingService.ParseResult parsed
	) {
		QuotationDraftSnapshot current = loadSnapshot(inputDraft.draftId(), ownerId);
		requireRevision(current, inputDraft.revision());
		List<String> imageIds = new ArrayList<>(current.imageMessageIds());
		for (String messageId : messageIds) {
			if (!imageIds.contains(messageId)) imageIds.add(messageId);
		}

		QuotationDraftSnapshot withImage = copyImages(current, imageIds, null, false, current.revision());
		QuotationDraftSnapshot merged = merge(withImage, parsed.request());
		saveCas(merged, current.revision());
		saveHeaderEvidence(merged.draftId(), messageIds.getLast(), parsed.request().headerPatch());
		for (String messageId : messageIds) linkImage(merged.draftId(), messageId);

		saveImageAssessments(merged.draftId(), parsed.request());
		for (String messageId : messageIds) saveMessage(merged.draftId(), messageId, "IMAGE", null, parsed.rawJson());

		return loadSnapshot(merged.draftId(), ownerId);
	}

	// 方法：拒絕 AI 呼叫期間已被其他訊息更新的草稿。
	private void requireRevision(QuotationDraftSnapshot current, int expectedRevision) {
		if (current.revision() != expectedRevision) {
			throw error("STALE_DRAFT", "報價草稿已更新，請重新送出本次補充資訊");
		}
	}

	// 方法：把 pending 原圖掛入草稿，並由 AI 評分後選出最具代表性的候選圖。
	@Override
	public QuotationDraftWork attachImage(String ownerId, String messageId) {
		return attachImages(ownerId, List.of(messageId));
	}

	// 方法：待 LINE imageSet 全部收齊後，一次評估全部候選並保存唯一選圖。
	@Override
	public QuotationDraftWork attachImages(String ownerId, List<String> messageIds) {
		requireOwner(ownerId);
		if (messageIds == null || messageIds.isEmpty() || messageIds.size() > 20) {
			throw error("INVALID_IMAGE_SET", "候選圖片組必須包含 1 至 20 張圖片。");
		}
		List<String> uniqueMessageIds = List.copyOf(new java.util.LinkedHashSet<>(messageIds));
		Optional<QuotationDraftSnapshot> processedImages = findCommittedMessages(ownerId, uniqueMessageIds);
		if (processedImages.isPresent()) return work(processedImages.get());

		QuotationDraftSnapshot draft = findActive(ownerId)
			.orElseThrow(() -> error("DRAFT_NOT_FOUND", "目前沒有可補圖片的報價草稿。"));

		List<String> imageIds = new ArrayList<>(draft.imageMessageIds());
		for (String messageId : uniqueMessageIds) {
			PendingImageRepository.PendingImage pending = pendingImages.findByMessageId(messageId)
				.filter(image -> ownerId.equals(image.sourceId()))
				.orElseThrow(() -> error("IMAGE_NOT_FOUND", "找不到這張待處理圖片，請重新上傳。"));
			if (!imageIds.contains(pending.messageId())) imageIds.add(pending.messageId());
		}

		QuotationDraftSnapshot withImage = copyImages(draft, imageIds, null, false, draft.revision());
		QuotationAiParsingService.ParseResult parsed = parser.parse(
			"請評估候選圖片並保留既有報價資料。",
			imageInputs(withImage),
			draft.schemeCode()
		);
		QuotationDraftSnapshot committed = transactions.execute(status -> commitParsedImages(
			draft,
			ownerId,
			uniqueMessageIds,
			parsed
		));
		if (committed == null) throw error("DRAFT_SAVE_FAILED", "無法保存報價草稿");

		return work(committed);
	}

	// 方法：依草稿編號與擁有者載入工作內容。
	@Override
	public QuotationDraftWork load(long draftId, String ownerId) {
		return work(loadSnapshot(draftId, ownerId));
	}

	// 方法：讀取擁有者綁定草稿的目前 revision。
	@Override
	public int currentRevision(long draftId, String ownerId) {
		// 資料庫：草稿編號與 source_id 必須同時匹配，避免跨使用者探查。
		List<Integer> revisions = jdbc.query(
			"SELECT revision FROM quotation_draft WHERE id = ? AND source_type = 'user' AND source_id = ?",
			(result, rowNumber) -> result.getInt(1),
			draftId,
			ownerId
		);
		if (revisions.size() != 1) throw error("DRAFT_NOT_FOUND", "找不到可操作的報價草稿。");

		return revisions.getFirst();
	}

	// 方法：保存狀態機核准的草稿狀態與頂部欄位。
	@Override
	@Transactional
	public void save(QuotationDraftSnapshot draft) {
		Integer currentRevision = jdbc.queryForObject(
			"SELECT revision FROM quotation_draft WHERE id = ?",
			Integer.class,
			draft.draftId()
		);
		if (currentRevision == null || draft.revision() < currentRevision || draft.revision() > currentRevision + 1) {
			throw error("STALE_DRAFT", "報價草稿已更新，請重新操作。");
		}

		saveCas(draft, currentRevision);
	}

	// 方法：用預期 revision 原子保存草稿，避免舊快照覆蓋並行更新。
	private void saveCas(QuotationDraftSnapshot draft, int expectedRevision) {
		Map<String, String> fields = draft.baseFields();
		// 資料庫：只允許精確 revision 的 compare-and-set 更新。
		int changed = jdbc.update("""
			UPDATE quotation_draft
			SET company_name = ?, work_name = ?, contact_name = ?, customer_phone = ?,
				customer_email = ?, project_location = ?, status = ?, image_declined = ?,
				revision = ?, scheme_id = (SELECT id FROM quotation_scheme WHERE code = ? AND is_active = 1),
				confirmation_revision = CASE WHEN ? = 'AWAITING_CONFIRMATION' THEN ? ELSE confirmation_revision END,
				updated_at = CURRENT_TIMESTAMP
			WHERE id = ? AND revision = ?
			""",
			value(fields, "companyName"),
			value(fields, "workName"),
			value(fields, "contactName"),
			value(fields, "phone"),
			value(fields, "email"),
			value(fields, "projectLocation"),
			draft.status().name(),
			draft.imageDeclined() ? 1 : 0,
			draft.revision(),
			draft.schemeCode(),
			draft.status().name(),
			draft.revision(),
			draft.draftId(),
			expectedRevision
		);
		if (changed != 1) throw error("STALE_DRAFT", "報價草稿已更新，請重新操作。");

		upsertExtraField(draft.draftId(), "fax", value(fields, "fax"));
		upsertExtraField(draft.draftId(), "salesRepresentative", value(fields, "salesRepresentative"));
		upsertExtraField(draft.draftId(), "additionalHeader", value(fields, "additionalHeader"));
		if (draft.selectedImageMessageId() != null) selectImage(draft.draftId(), draft.selectedImageMessageId());
	}

	// 方法：接受後續修改文字，保留資料並清除已顯示預覽旗標。
	@Override
	public QuotationDraftWork requestModification(QuotationDraftWork work) {
		QuotationDraftSnapshot draft = work.draft();
		QuotationDraftSnapshot modified = new QuotationDraftSnapshot(
			draft.draftId(),
			draft.revision() + 1,
			QuotationDraftStatus.COLLECTING_ITEMS,
			draft.schemeCode(),
			draft.baseFields(),
			draft.items(),
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			draft.imageQuestionAsked(),
			draft.imageDeclined(),
			false,
			null
		);
		return new QuotationDraftWork(modified, work.calculation());
	}

	// 方法：確認候選圖屬於該草稿後更換唯一選取圖片。
	@Override
	@Transactional
	public QuotationDraftWork selectImage(long draftId, String ownerId, String messageId) {
		QuotationDraftSnapshot draft = loadSnapshot(draftId, ownerId);
		if (!draft.imageMessageIds().contains(messageId)) {
			throw error("IMAGE_NOT_FOUND", "找不到可選取的候選圖片。");
		}

		selectImage(draftId, messageId);
		QuotationDraftSnapshot selected = copyImages(
			draft,
			draft.imageMessageIds(),
			messageId,
			false
		);
		save(selected);
		return work(loadSnapshot(draftId, ownerId));
	}

	// 方法：移除唯一選取旗標，保留全部 pending 候選原圖。
	@Override
	@Transactional
	public QuotationDraftWork removeSelectedImage(long draftId, String ownerId) {
		QuotationDraftSnapshot draft = loadSnapshot(draftId, ownerId);
		if (draft.selectedImageMessageId() == null) return work(draft);

		// 資料庫：僅取消嵌入選取，所有候選關聯與 pending 原圖都保留供正式歸檔。
		jdbc.update("UPDATE quotation_draft_image SET is_selected = 0 WHERE draft_id = ?", draftId);
		QuotationDraftSnapshot removed = copyImages(
			draft,
			draft.imageMessageIds(),
			null,
			false
		);
		save(removed);
		return work(loadSnapshot(draftId, ownerId));
	}

	// 方法：尋找使用者最新的活動草稿。
	private Optional<QuotationDraftSnapshot> findActive(String ownerId) {
		// 資料庫：只載入同一 user source 的最新活動草稿。
		List<Long> ids = jdbc.query("""
			SELECT id FROM quotation_draft
			WHERE source_type = 'user' AND source_id = ?
				AND status IN (
					'COLLECTING_BASE_INFO', 'COLLECTING_ITEMS', 'AWAITING_IMAGE',
					'READY_FOR_PREVIEW', 'AWAITING_CONFIRMATION'
				)
			ORDER BY updated_at DESC, id DESC LIMIT 1
			""",
			(result, rowNumber) -> result.getLong(1),
			ownerId
		);
		return ids.isEmpty() ? Optional.empty() : Optional.of(loadSnapshot(ids.getFirst(), ownerId));
	}

	// 方法：重送事件若已有不可變訊息紀錄，直接載入原草稿以避免重跑 AI 與合併。
	private Optional<QuotationDraftSnapshot> findCommittedMessage(String ownerId, String messageId) {
		// 資料庫：訊息編號與草稿 owner 必須同時匹配，避免跨使用者探查。
		List<Long> draftIds = jdbc.query("""
			SELECT dm.draft_id
			FROM quotation_draft_message dm
			JOIN quotation_draft d ON d.id = dm.draft_id
			WHERE dm.message_id = ?
				AND d.source_type = 'user'
				AND d.source_id = ?
			""", (result, rowNumber) -> result.getLong(1), messageId, ownerId);
		if (draftIds.isEmpty()) return Optional.empty();

		return Optional.of(loadSnapshot(draftIds.getFirst(), ownerId));
	}

	// 方法：整組圖片訊息皆已提交到同一草稿時，直接回復該草稿而不重新評分。
	private Optional<QuotationDraftSnapshot> findCommittedMessages(String ownerId, List<String> messageIds) {
		Long committedDraftId = null;
		for (String messageId : messageIds) {
			Optional<QuotationDraftSnapshot> committed = findCommittedMessage(ownerId, messageId);
			if (committed.isEmpty()) return Optional.empty();

			long draftId = committed.get().draftId();
			if (committedDraftId != null && committedDraftId != draftId) {
				throw error("IMAGE_SET_CONFLICT", "候選圖片組不屬於同一張報價草稿。");
			}
			committedDraftId = draftId;
		}
		return committedDraftId == null
			? Optional.empty()
			: Optional.of(loadSnapshot(committedDraftId, ownerId));
	}

	// 方法：以資料庫唯一索引原子建立或取得同一使用者唯一的進行中草稿。
	private QuotationDraftSnapshot findOrCreateActiveDraft(String ownerId) {
		// 資料庫：ON CONFLICT 讓同時到達的訊息共用既有草稿，不建立第二份。
		jdbc.update("""
			INSERT INTO quotation_draft (draft_key, source_type, source_id, requester_id)
			VALUES (?, 'user', ?, ?)
			ON CONFLICT DO NOTHING
			""", UUID.randomUUID().toString(), ownerId, ownerId);
		return findActive(ownerId)
			.orElseThrow(() -> error("DRAFT_CREATE_FAILED", "暫時無法建立報價草稿，請稍後再試。"));
	}

	// 方法：把已驗證 AI patch 合併至既有草稿；低信心欄位維持空白。
	private QuotationDraftSnapshot merge(
		QuotationDraftSnapshot draft,
		QuotationRequestValidationService.ValidatedQuotationRequest request
	) {
		Map<String, String> fields = new LinkedHashMap<>(draft.baseFields());
		put(fields, "companyName", request.headerPatch().companyName());
		put(fields, "workName", request.headerPatch().workName());
		put(fields, "contactName", request.headerPatch().contactName());
		put(fields, "phone", request.headerPatch().phone());
		put(fields, "fax", request.headerPatch().fax());
		put(fields, "email", request.headerPatch().email());
		put(fields, "projectLocation", request.headerPatch().projectLocation());
		put(fields, "salesRepresentative", request.headerPatch().salesRepresentative());
		put(fields, "additionalHeader", request.headerPatch().additionalHeader());

		List<QuotationDraftItem> items = draftItems(request);
		// 應用服務：報價格式只由使用者指定，AI 回傳值不得改寫草稿格式。
		String schemeCode = draft.schemeCode() == null ? request.schemeCode() : draft.schemeCode();
		String bestImage = bestImageMessageId(request.imageAssessments());
		String selected = bestImage == null ? draft.selectedImageMessageId() : bestImage;
		return new QuotationDraftSnapshot(
			draft.draftId(),
			draft.revision() + 1,
			draft.status(),
			schemeCode,
			fields,
			items.isEmpty() ? draft.items() : items,
			draft.imageMessageIds(),
			selected,
			draft.imageQuestionAsked(),
			draft.imageDeclined(),
			false,
			null
		);
	}

	// 方法：載入完整草稿快照。
	private QuotationDraftSnapshot loadSnapshot(long draftId, String ownerId) {
		// 資料庫：草稿與 scheme 一次載入，再分別載入品項與候選圖。
		List<Map<String, Object>> rows = jdbc.queryForList("""
			SELECT d.*, s.code AS scheme_code FROM quotation_draft d
			LEFT JOIN quotation_scheme s ON s.id = d.scheme_id
			WHERE d.id = ? AND d.source_type = 'user' AND d.source_id = ?
			""", draftId, ownerId);
		if (rows.size() != 1) throw error("DRAFT_NOT_FOUND", "找不到可操作的報價草稿。");

		Map<String, Object> row = rows.getFirst();
		Map<String, String> fields = loadFields(draftId, row);
		List<String> imageIds = jdbc.query(
			"SELECT message_id FROM quotation_draft_image WHERE draft_id = ? ORDER BY candidate_order, id",
			(result, rowNumber) -> result.getString(1),
			draftId
		);
		String selected = jdbc.query("""
			SELECT message_id FROM quotation_draft_image
			WHERE draft_id = ? AND is_selected = 1 LIMIT 1
			""", (result, rowNumber) -> result.getString(1), draftId).stream().findFirst().orElse(null);
		return new QuotationDraftSnapshot(
			draftId,
			((Number) row.get("revision")).intValue(),
			QuotationDraftStatus.valueOf((String) row.get("status")),
			(String) row.get("scheme_code"),
			fields,
			loadItems(draftId),
			imageIds,
			selected,
			"AWAITING_IMAGE".equals(row.get("status")),
			((Number) row.get("image_declined")).intValue() == 1,
			row.get("confirmation_revision") != null,
			null
		);
	}

	// 方法：建立可供狀態機與訊息 builder 使用的計算結果。
	private QuotationDraftWork work(QuotationDraftSnapshot draft) {
		QuotationCalculationResult calculation = null;
		try {
			calculation = calculator.calculate(calculationRequest(draft));
		}
		catch (RuntimeException ignored) {
			// 未補齊的草稿尚不可計算，狀態機會先要求補件。
		}
		return new QuotationDraftWork(draft, calculation);
	}

	// 方法：將草稿品項轉成完全由程式計算的請求。
	private QuotationCalculationRequest calculationRequest(QuotationDraftSnapshot draft) {
		List<QuotationCalculationRequest.StandardItemIntent> standard = new ArrayList<>();
		List<QuotationCalculationRequest.CustomItem> custom = new ArrayList<>();
		for (QuotationDraftItem item : draft.items()) {
			Map<String, String> fields = item.fields();
			if (item.kind() == QuotationDraftItemKind.STANDARD) {
				standard.add(new QuotationCalculationRequest.StandardItemIntent(
					fields.get("itemCode"),
					decimal(fields.get("quantity")),
					null
				));
				continue;
			}

			custom.add(new QuotationCalculationRequest.CustomItem(
				fields.get("itemName"),
				fields.get("specification"),
				fields.get("unit"),
				decimal(fields.get("unitPrice")),
				decimal(fields.get("quantity")),
				fields.get("remark"),
				true,
				null
			));
		}
		return new QuotationCalculationRequest(draft.schemeCode(), standard, custom, loadRemovedItemCodes(draft.draftId()));
	}

	// 方法：讀取使用者明確刪除的標準品項代碼，交由程式計價排除。
	private Set<String> loadRemovedItemCodes(long draftId) {
		return Set.copyOf(jdbc.query(
			"SELECT item_code_snapshot FROM quotation_draft_item WHERE draft_id = ? AND is_removed = 1 AND item_kind = 'STANDARD'",
			(result, rowNumber) -> result.getString(1),
			draftId
		));
	}

	// 方法：把 AI 圖片清單轉成可重送至模型的原圖輸入。
	private List<AiImageInput> imageInputs(QuotationDraftSnapshot draft) {
		List<AiImageInput> inputs = new ArrayList<>();
		for (String messageId : draft.imageMessageIds()) {
			PendingImageRepository.PendingImage pending = pendingImages.findByMessageId(messageId).orElse(null);
			if (pending == null) continue;

			try {
				// 儲存 API：正式環境由物件儲存讀取；舊檔案模式保留實體路徑與連結檢查。
				byte[] content = storage.usesLegacyFilesystem()
					? Files.readAllBytes(resolvePendingPath(pending.stagingPath()))
					: storage.read(pending.stagingPath());
				inputs.add(new AiImageInput(messageId, content, pending.contentType()));
			}
			catch (IllegalArgumentException exception) {
				throw error("INVALID_IMAGE_PATH", "圖片暫存路徑不合法");
			}
			catch (IOException exception) {
				throw error("IMAGE_READ_FAILED", "暫時無法讀取候選圖片，請重新上傳。");
			}
		}
		return List.copyOf(inputs);
	}

	// 方法：將資料庫內的資產相對路徑安全解析到「圖片資產」子路徑內。
	private Path resolvePendingPath(String stagingPath) throws IOException {
		if (stagingPath == null || stagingPath.isBlank() || Path.of(stagingPath).isAbsolute()) {
			throw new IllegalArgumentException("暫存圖片路徑必須是相對路徑");
		}

		Path resolved = storage.resolve(stagingPath);
		Path realRoot = storage.root().toRealPath();
		Path realFile = resolved.toRealPath();
		if (!realFile.startsWith(realRoot)) throw new IllegalArgumentException("暫存圖片路徑超出資產根目錄");

		return realFile;
	}

	// 方法：把驗證後品項轉成草稿品項。
	private List<QuotationDraftItem> draftItems(QuotationRequestValidationService.ValidatedQuotationRequest request) {
		List<QuotationDraftItem> items = new ArrayList<>();
		for (QuotationRequestValidationService.ResolvedItem item : request.standardItems()) {
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("itemCode", item.itemCode());
			// 數量未明示時保持缺漏，交由對話狀態機以單一訊息回問，不可自行補值。
			if (item.quantity() != null) {
				fields.put("quantity", item.quantity().stripTrailingZeros().toPlainString());
			}
			if (item.matchedName() != null) fields.put("matchedName", item.matchedName());

			items.add(new QuotationDraftItem(item.itemCode(), QuotationDraftItemKind.STANDARD, fields));
		}
		for (QuotationRequestValidationService.CustomItem item : request.customItems()) {
			Map<String, String> fields = new LinkedHashMap<>();
			put(fields, "itemName", item.itemName());
			put(fields, "specification", item.specification());
			put(fields, "unit", item.unit());
			put(fields, "unitPrice", item.unitPrice());
			put(fields, "quantity", item.quantity());
			put(fields, "remark", item.remark());
			items.add(new QuotationDraftItem(item.clientItemId(), QuotationDraftItemKind.CUSTOM, fields));
		}
		return List.copyOf(items);
	}

	// 方法：以資料庫快照重建草稿品項。
	private List<QuotationDraftItem> loadItems(long draftId) {
		// 資料庫：已刪除品項不回到客戶預覽。
		return jdbc.query("""
			SELECT id, item_kind, client_item_id, item_code_snapshot, item_name_snapshot, specification_snapshot,
				quantity, unit_snapshot, unit_price_snapshot, remark_snapshot, matched_name
			FROM quotation_draft_item WHERE draft_id = ? AND is_removed = 0 ORDER BY display_order, id
			""", (result, rowNumber) -> {
			Map<String, String> fields = new LinkedHashMap<>();
			String kind = result.getString("item_kind");
			if ("STANDARD".equals(kind)) fields.put("itemCode", result.getString("item_code_snapshot"));

			put(fields, "matchedName", result.getString("matched_name"));

			put(fields, "itemName", result.getString("item_name_snapshot"));
			put(fields, "specification", result.getString("specification_snapshot"));
			put(fields, "quantity", result.getString("quantity"));
			put(fields, "unit", result.getString("unit_snapshot"));
			put(fields, "unitPrice", result.getString("unit_price_snapshot"));
			put(fields, "remark", result.getString("remark_snapshot"));
			return new QuotationDraftItem(
				"STANDARD".equals(kind) ? result.getString("item_code_snapshot") : result.getString("client_item_id"),
				"STANDARD".equals(kind) ? QuotationDraftItemKind.STANDARD : QuotationDraftItemKind.CUSTOM,
				fields
			);
		}, draftId);
	}

	// 方法：將 AI 驗證結果當作 patch 合併到既有草稿品項。
	private void mergeItems(long draftId, QuotationRequestValidationService.ValidatedQuotationRequest request) {
		// 階段 1：只有 removedItemCodes 能將既有標準品項標記為刪除。
		for (String itemCode : request.removedItemCodes()) {
			jdbc.update("""
				UPDATE quotation_draft_item
				SET is_removed = 1, updated_at = CURRENT_TIMESTAMP
				WHERE draft_id = ? AND item_kind = 'STANDARD' AND item_code_snapshot = ?
				""", draftId, itemCode);
		}

		// 階段 2：只更新本輪提及的標準品項，未提及品項保持原狀。
		int order = nextItemOrder(draftId);
		for (QuotationRequestValidationService.ResolvedItem item : request.standardItems()) {
			int changed = jdbc.update("""
				UPDATE quotation_draft_item
				SET specification_snapshot = ?, quantity = COALESCE(?, quantity), unit_snapshot = ?,
					unit_price_snapshot = ?, remark_snapshot = ?, source_text = ?,
					confidence = ?, matched_name = ?, is_removed = 0, updated_at = CURRENT_TIMESTAMP
				WHERE draft_id = ? AND item_kind = 'STANDARD' AND item_code_snapshot = ?
				""", item.specification(), item.quantity(), item.unit(), item.unitPrice(), item.remark(),
				item.sourceText(), item.confidence(), item.matchedName(), draftId, item.itemCode());
			if (changed == 1) continue;

			jdbc.update("""
				INSERT INTO quotation_draft_item (
					draft_id, item_kind, item_id, item_code_snapshot, item_name_snapshot,
					specification_snapshot, quantity, unit_snapshot, unit_price_snapshot,
					remark_snapshot, source_text, confidence, matched_name, display_order
				)
				SELECT ?, 'STANDARD', i.id, i.code, i.name, ?, ?, ?, ?, ?, ?, ?, ?, ?
				FROM quotation_item i WHERE i.code = ? AND i.is_active = 1
				""", draftId, item.specification(), item.quantity(), item.unit(), item.unitPrice(), item.remark(),
				item.sourceText(), item.confidence(), item.matchedName(), order++, item.itemCode());
		}

		// 階段 3：依本輪開始前的品項合併，保留同一輸入中明確分列的品項。
		List<QuotationDraftItem> previousItems = loadItems(draftId);
		for (QuotationRequestValidationService.CustomItem item : request.customItems()) {
			String clientItemId = resolveCustomItemId(previousItems, item);
			int changed = jdbc.update("""
				UPDATE quotation_draft_item
				SET item_name_snapshot = COALESCE(?, item_name_snapshot),
					specification_snapshot = COALESCE(?, specification_snapshot),
					quantity = COALESCE(?, quantity), unit_snapshot = COALESCE(?, unit_snapshot),
					unit_price_snapshot = COALESCE(?, unit_price_snapshot),
					remark_snapshot = COALESCE(?, remark_snapshot),
					source_text = COALESCE(?, source_text), confidence = COALESCE(?, confidence),
					updated_at = CURRENT_TIMESTAMP
				WHERE draft_id = ? AND item_kind = 'CUSTOM' AND client_item_id = ?
				""", extracted(item.itemName()), extracted(item.specification()), extracted(item.quantity()),
				extracted(item.unit()), extracted(item.unitPrice()), extracted(item.remark()),
				item.itemName() == null ? null : item.itemName().sourceText(),
				item.itemName() == null ? null : item.itemName().confidence(), draftId, clientItemId);
			if (changed == 1) continue;

			jdbc.update("""
				INSERT INTO quotation_draft_item (
					draft_id, item_kind, client_item_id, item_name_snapshot, specification_snapshot, quantity,
					unit_snapshot, unit_price_snapshot, remark_snapshot, source_text, confidence, display_order
				) VALUES (?, 'CUSTOM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", draftId, clientItemId, extracted(item.itemName()), extracted(item.specification()), extracted(item.quantity()),
				extracted(item.unit()), extracted(item.unitPrice()), extracted(item.remark()),
				item.itemName() == null ? null : item.itemName().sourceText(),
				item.itemName() == null ? null : item.itemName().confidence(), order++);
		}
	}

	// 方法：補答的 ID 漂移時只對應唯一同名且規格相容品項，歧義必須回問。
	private String resolveCustomItemId(List<QuotationDraftItem> previousItems, QuotationRequestValidationService.CustomItem patch) {
		List<QuotationDraftItem> items = previousItems.stream()
			.filter(item -> item.kind() == QuotationDraftItemKind.CUSTOM).toList();
		String name = patch.itemName() == null ? null : patch.itemName().value();
		for (QuotationDraftItem item : items) {
			if (!item.itemKey().equals(patch.clientItemId())) continue;

			if (name != null && item.fields().get("itemName") != null && !name.equals(item.fields().get("itemName"))) {
				throw error("CUSTOM_ITEM_CONFLICT", "品項識別與既有名稱不同，請提供要修改的品項名稱與規格；草稿已保留。");
			}
			return item.itemKey();
		}
		List<QuotationDraftItem> matches = items.stream()
			.filter(item -> name != null && name.equals(item.fields().get("itemName")))
			.filter(item -> compatible(item, "specification", patch.specification()) && compatible(item, "unit", patch.unit()))
			.toList();
		if (matches.size() > 1 || name == null) {
			throw error("CUSTOM_ITEM_CONFLICT", "無法唯一對應補充的品項，請提供品項名稱與規格；草稿已保留。");
		}
		return matches.isEmpty() ? patch.clientItemId() : matches.getFirst().itemKey();
	}

	// 方法：缺值可由續答補齊，明確不同規格或單位則保留為不同品項。
	private boolean compatible(QuotationDraftItem item, String field, QuotationRequestValidationService.ExtractedString value) {
		return value == null || item.fields().get(field) == null || value.value().equals(item.fields().get(field));
	}

	// 方法：保存本輪 AI／OCR 採用欄位的來源訊息、原文及信心證據。
	private void saveHeaderEvidence(
		long draftId,
		String messageId,
		QuotationRequestValidationService.HeaderPatch patch
	) {
		if (patch == null) return;

		upsertHeaderEvidence(draftId, messageId, "companyName", patch.companyName());
		upsertHeaderEvidence(draftId, messageId, "workName", patch.workName());
		upsertHeaderEvidence(draftId, messageId, "contactName", patch.contactName());
		upsertHeaderEvidence(draftId, messageId, "phone", patch.phone());
		upsertHeaderEvidence(draftId, messageId, "fax", patch.fax());
		upsertHeaderEvidence(draftId, messageId, "email", patch.email());
		upsertHeaderEvidence(draftId, messageId, "projectLocation", patch.projectLocation());
		upsertHeaderEvidence(draftId, messageId, "salesRepresentative", patch.salesRepresentative());
		upsertHeaderEvidence(draftId, messageId, "additionalHeader", patch.additionalHeader());
	}

	// 方法：只保存通過信心門檻的單一抬頭欄位證據，不以缺漏覆蓋既有資料。
	private void upsertHeaderEvidence(
		long draftId,
		String messageId,
		String fieldKey,
		QuotationRequestValidationService.ExtractedString extracted
	) {
		if (extracted == null) return;

		// 資料庫：以欄位唯一鍵更新最新採用值與可追溯 OCR 證據。
		jdbc.update("""
			INSERT INTO quotation_draft_field (
				draft_id, field_key, field_value, source_message_id,
				source_text, confidence, confirmation_status
			)
			VALUES (?, ?, ?, ?, ?, ?, 'EXTRACTED')
			ON CONFLICT (draft_id, field_key) DO UPDATE SET
				field_value = excluded.field_value,
				source_message_id = excluded.source_message_id,
				source_text = excluded.source_text,
				confidence = excluded.confidence,
				confirmation_status = 'EXTRACTED',
				updated_at = CURRENT_TIMESTAMP
			""", draftId, fieldKey, extracted.value(), messageId, extracted.sourceText(), extracted.confidence());
	}

	// 方法：取得下一個穩定顯示順序，避免 patch 改動未提及品項的位置。
	private int nextItemOrder(long draftId) {
		Integer maximum = jdbc.queryForObject(
			"SELECT COALESCE(MAX(display_order), -1) FROM quotation_draft_item WHERE draft_id = ?",
			Integer.class,
			draftId
		);
		return maximum == null ? 0 : maximum + 1;
	}

	// 方法：保存 AI 候選圖評分與唯一選取結果。
	private void saveImageAssessments(
		long draftId,
		QuotationRequestValidationService.ValidatedQuotationRequest request
	) {
		if (request.imageAssessments().isEmpty()) return;

		jdbc.update("UPDATE quotation_draft_image SET is_selected = 0 WHERE draft_id = ?", draftId);
		String selectedMessageId = bestImageMessageId(request.imageAssessments());
		int order = 0;
		for (QuotationRequestValidationService.ImageAssessment assessment : request.imageAssessments()) {
			jdbc.update("""
				INSERT INTO quotation_draft_image (
					draft_id, message_id, candidate_order, distinctiveness_score,
					quality_score, selection_reason, is_selected
				) VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (draft_id, message_id) DO UPDATE SET
					candidate_order = excluded.candidate_order,
					distinctiveness_score = excluded.distinctiveness_score,
					quality_score = excluded.quality_score,
					selection_reason = excluded.selection_reason,
					is_selected = excluded.is_selected
				""", draftId, assessment.messageId(), order++, assessment.distinctivenessScore(),
				assessment.qualityScore(), assessment.reason(),
				assessment.messageId().equals(selectedMessageId) ? 1 : 0);
		}
	}

	// 方法：依區別度、品質、視角依序選出最能代表案場的單一圖片。
	private String bestImageMessageId(
		List<QuotationRequestValidationService.ImageAssessment> assessments
	) {
		return assessments.stream()
			.max(Comparator
				.comparing(QuotationRequestValidationService.ImageAssessment::distinctivenessScore)
				.thenComparing(QuotationRequestValidationService.ImageAssessment::qualityScore)
				.thenComparing(QuotationRequestValidationService.ImageAssessment::viewpointScore)
			)
			.map(QuotationRequestValidationService.ImageAssessment::messageId)
			.orElse(null);
	}

	// 方法：建立尚未評分的候選圖片關聯。
	private void linkImage(long draftId, String messageId) {
		jdbc.update("""
			INSERT INTO quotation_draft_image (draft_id, message_id, candidate_order)
			VALUES (?, ?, (SELECT COUNT(*) FROM quotation_draft_image WHERE draft_id = ?))
			ON CONFLICT (draft_id, message_id) DO NOTHING
			""", draftId, messageId, draftId);
	}

	// 方法：更新唯一選取圖片，支援更換與移除。
	private void selectImage(long draftId, String messageId) {
		jdbc.update("UPDATE quotation_draft_image SET is_selected = 0 WHERE draft_id = ?", draftId);
		jdbc.update("""
			UPDATE quotation_draft_image SET is_selected = 1
			WHERE draft_id = ? AND message_id = ?
			""", draftId, messageId);
	}

	// 方法：保存原始訊息與 AI JSON，供後續稽核及局部補件重驗。
	private void saveMessage(long draftId, String messageId, String type, String rawText, String aiJson) {
		jdbc.update("""
			INSERT INTO quotation_draft_message (draft_id, message_id, message_type, raw_text, ai_response_json)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (message_id) DO NOTHING
			""", draftId, messageId, type, rawText, aiJson);
	}

	// 方法：載入頂部欄位與額外欄位。
	private Map<String, String> loadFields(long draftId, Map<String, Object> row) {
		Map<String, String> fields = new LinkedHashMap<>();
		put(fields, "companyName", (String) row.get("company_name"));
		put(fields, "workName", (String) row.get("work_name"));
		put(fields, "contactName", (String) row.get("contact_name"));
		put(fields, "phone", (String) row.get("customer_phone"));
		put(fields, "email", (String) row.get("customer_email"));
		put(fields, "projectLocation", (String) row.get("project_location"));
		for (Map<String, Object> extra : jdbc.queryForList(
			"SELECT field_key, field_value FROM quotation_draft_field WHERE draft_id = ?",
			draftId
		)) {
			put(fields, (String) extra.get("field_key"), (String) extra.get("field_value"));
		}
		return fields;
	}

	// 方法：保存不在 quotation_draft 固定欄位內的頂部資訊。
	private void upsertExtraField(long draftId, String key, String value) {
		jdbc.update("""
			INSERT INTO quotation_draft_field (draft_id, field_key, field_value, confirmation_status)
			VALUES (?, ?, ?, 'EXTRACTED')
			ON CONFLICT (draft_id, field_key) DO UPDATE SET
				field_value = excluded.field_value, updated_at = CURRENT_TIMESTAMP
			""", draftId, key, value);
	}

	// 方法：複製圖片候選狀態並增加 revision。
	private QuotationDraftSnapshot copyImages(
		QuotationDraftSnapshot draft,
		List<String> imageIds,
		String selected,
		boolean declined
	) {
		return copyImages(draft, imageIds, selected, declined, draft.revision() + 1);
	}

	// 方法：複製圖片候選狀態並指定 revision，供交易外 AI 輸入快照使用。
	private QuotationDraftSnapshot copyImages(
		QuotationDraftSnapshot draft,
		List<String> imageIds,
		String selected,
		boolean declined,
		int revision
	) {
		return new QuotationDraftSnapshot(
			draft.draftId(), revision, draft.status(), draft.schemeCode(), draft.baseFields(),
			draft.items(), imageIds, selected, draft.imageQuestionAsked(), declined, false, null
		);
	}

	// 方法：把 extracted string 放入欄位 patch。
	private void put(
		Map<String, String> target,
		String key,
		QuotationRequestValidationService.ExtractedString extracted
	) {
		if (extracted != null && extracted.value() != null) target.put(key, extracted.value());
	}

	// 方法：把 extracted decimal 放入欄位 patch。
	private void put(
		Map<String, String> target,
		String key,
		QuotationRequestValidationService.ExtractedDecimal extracted
	) {
		if (extracted != null && extracted.value() != null) {
			target.put(key, extracted.value().stripTrailingZeros().toPlainString());
		}
	}

	// 方法：放入非空字串欄位。
	private void put(Map<String, String> target, String key, String value) {
		if (value != null && !value.isBlank()) target.put(key, value);
	}

	// 方法：取得可為空的欄位值。
	private String value(Map<String, String> fields, String key) {
		String value = fields.get(key);
		return value == null || value.isBlank() ? null : value;
	}

	// 方法：取得 extracted string 的值。
	private String extracted(QuotationRequestValidationService.ExtractedString value) {
		return value == null ? null : value.value();
	}

	// 方法：取得 extracted decimal 的值。
	private BigDecimal extracted(QuotationRequestValidationService.ExtractedDecimal value) {
		return value == null ? null : value.value();
	}

	// 方法：解析草稿中的十進位數字。
	private BigDecimal decimal(String value) {
		return value == null || value.isBlank() ? null : new BigDecimal(value);
	}

	// 方法：驗證一對一使用者識別碼。
	private void requireOwner(String ownerId) {
		if (ownerId == null || ownerId.isBlank()) throw error("INVALID_OWNER", "無法辨識 LINE 使用者。");
	}

	// 方法：建立可安全顯示的草稿流程例外。
	private QuotationLineWorkflowException error(String code, String message) {
		return new QuotationLineWorkflowException(code, message);
	}
}
