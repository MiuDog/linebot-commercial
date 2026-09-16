package dev.miudog.linebotcommercial.service.quotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class QuotationConversationService {

	private static final List<String> REQUIRED_BASE_FIELDS = List.of(
		"companyName",
		"workName",
		"salesRepresentative"
	);
	private static final List<String> STANDARD_ITEM_FIELDS = List.of("itemCode", "quantity");
	private static final List<String> CUSTOM_ITEM_FIELDS = List.of(
		"itemName",
		"specification",
		"unit",
		"unitPrice",
		"quantity",
		"remark"
	);
	private static final Set<String> IMAGE_REQUIRED_SCHEMES = Set.of("MARINE", "BLANK");

	// 方法：重新檢查完整草稿並決定唯一的下一個對話動作。
	public QuotationConversationDecision review(QuotationDraftSnapshot draft) {
		requireDraft(draft);
		QuotationConversationDecision terminalDecision = terminalDecision(draft);
		if (terminalDecision != null) return terminalDecision;

		List<String> missingBaseFields = findMissingBaseFields(draft);
		if (!missingBaseFields.isEmpty()) {
			return decision(
				draft,
				QuotationDraftStatus.COLLECTING_BASE_INFO,
				missingBaseFields,
				List.of(),
				QuotationNextAction.REQUEST_BASE_FIELDS
			);
		}

		List<QuotationMissingItemFields> missingItemFields = findMissingItemFields(draft);
		if (!missingItemFields.isEmpty()) {
			return decision(
				draft,
				QuotationDraftStatus.COLLECTING_ITEMS,
				List.of(),
				missingItemFields,
				QuotationNextAction.REQUEST_ITEM_FIELDS
			);
		}

		validateItemLimits(draft);
		validateImageState(draft);
		if (mustRequestImage(draft)) {
			return decision(
				draft,
				QuotationDraftStatus.AWAITING_IMAGE,
				List.of(),
				List.of(),
				QuotationNextAction.REQUEST_IMAGE
			);
		}

		if (!draft.imageMessageIds().isEmpty()
			&& isBlank(draft.selectedImageMessageId())
			&& !draft.imageDeclined()) {
			return decision(
				draft,
				QuotationDraftStatus.AWAITING_IMAGE,
				List.of(),
				List.of(),
				QuotationNextAction.REQUEST_IMAGE_SELECTION
			);
		}

		if (draft.previewPresented()) {
			return decision(
				draft,
				QuotationDraftStatus.AWAITING_CONFIRMATION,
				List.of(),
				List.of(),
				QuotationNextAction.REQUEST_CONFIRMATION
			);
		}

		return decision(
			draft,
			QuotationDraftStatus.READY_FOR_PREVIEW,
			List.of(),
			List.of(),
			QuotationNextAction.SHOW_PREVIEW
		);
	}

	// 方法：合併使用者局部補件後重新驗證所有缺漏與圖片條件。
	public QuotationConversationDecision applyPatch(QuotationDraftSnapshot draft, QuotationDraftPatch patch) {
		requireMutable(draft);
		if (patch == null) throw error("INVALID_PATCH", "草稿補件資料不可留空");

		Map<String, String> baseFields = new LinkedHashMap<>(draft.baseFields());
		baseFields.putAll(patch.baseFieldPatch());
		List<QuotationDraftItem> items = mergeItemPatches(draft.items(), patch.itemFieldPatches());
		List<String> imageMessageIds = mergeImageMessageIds(draft.imageMessageIds(), patch.imageMessageIdsToAdd());
		boolean imageDeclined = resolveImageDeclined(draft, patch, imageMessageIds);
		String selectedImageMessageId = patch.selectedImageMessageId() == null
			? draft.selectedImageMessageId()
			: patch.selectedImageMessageId();

		QuotationDraftSnapshot merged = new QuotationDraftSnapshot(
			draft.draftId(),
			draft.revision() + 1,
			draft.status(),
			draft.schemeCode(),
			baseFields,
			items,
			imageMessageIds,
			selectedImageMessageId,
			draft.imageQuestionAsked(),
			imageDeclined,
			false,
			null
		);
		return review(merged);
	}

	// 方法：記錄船用或空白格式已向使用者詢問圖片。
	public QuotationDraftSnapshot markImageQuestionAsked(QuotationDraftSnapshot draft) {
		requireMutable(draft);
		if (draft.status() != QuotationDraftStatus.AWAITING_IMAGE) {
			throw error("INVALID_TRANSITION", "草稿目前不需要詢問圖片");
		}
		if (draft.imageQuestionAsked()) return draft;

		return copy(
			draft,
			draft.revision() + 1,
			draft.status(),
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			true,
			draft.imageDeclined(),
			draft.previewPresented(),
			draft.confirmationEventId()
		);
	}

	// 方法：記錄完整預覽已顯示，並進入只接受明確 postback 的確認狀態。
	public QuotationDraftSnapshot markPreviewPresented(QuotationDraftSnapshot draft) {
		requireMutable(draft);
		QuotationConversationDecision decision = review(draft);
		if (decision.draft().status() != QuotationDraftStatus.READY_FOR_PREVIEW) {
			throw error("INVALID_TRANSITION", "報價資料尚未完整，無法顯示最終預覽");
		}

		QuotationDraftSnapshot ready = decision.draft();
		return copy(
			ready,
			ready.revision() + 1,
			QuotationDraftStatus.AWAITING_CONFIRMATION,
			ready.imageMessageIds(),
			ready.selectedImageMessageId(),
			ready.imageQuestionAsked(),
			ready.imageDeclined(),
			true,
			ready.confirmationEventId()
		);
	}

	// 方法：確認完整且已預覽的草稿，只產生後續交易所需的確認意圖。
	public QuotationConfirmationIntent confirm(QuotationDraftSnapshot draft, String confirmationEventId) {
		requireDraft(draft);
		if (draft.status() == QuotationDraftStatus.CONFIRMED) return new QuotationConfirmationIntent(draft, draft.confirmationEventId(), null);

		if (draft.status() == QuotationDraftStatus.CANCELLED || draft.status() == QuotationDraftStatus.EXPIRED) {
			throw error("TERMINAL_DRAFT", "已取消或逾期的草稿不可確認");
		}
		if (isBlank(confirmationEventId)) throw error("INVALID_CONFIRMATION", "確認事件識別不可留空");

		QuotationConversationDecision decision = review(draft);
		if (decision.draft().status() != QuotationDraftStatus.AWAITING_CONFIRMATION) {
			throw error("INVALID_TRANSITION", "必須先顯示完整預覽才能確認報價");
		}

		QuotationDraftSnapshot confirmed = copy(
			decision.draft(),
			decision.draft().revision() + 1,
			QuotationDraftStatus.CONFIRMED,
			decision.draft().imageMessageIds(),
			decision.draft().selectedImageMessageId(),
			decision.draft().imageQuestionAsked(),
			decision.draft().imageDeclined(),
			true,
			confirmationEventId.trim()
		);
		return new QuotationConfirmationIntent(confirmed, confirmed.confirmationEventId(), null);
	}

	// 方法：取消進行中的草稿，重複取消維持相同結果。
	public QuotationDraftSnapshot cancel(QuotationDraftSnapshot draft) {
		requireDraft(draft);
		if (draft.status() == QuotationDraftStatus.CANCELLED) return draft;

		if (draft.status() == QuotationDraftStatus.CONFIRMED || draft.status() == QuotationDraftStatus.EXPIRED) {
			throw error("TERMINAL_DRAFT", "已確認或逾期的草稿不可取消");
		}

		return copy(
			draft,
			draft.revision() + 1,
			QuotationDraftStatus.CANCELLED,
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			draft.imageQuestionAsked(),
			draft.imageDeclined(),
			draft.previewPresented(),
			draft.confirmationEventId()
		);
	}

	// 方法：將未完成草稿標記為逾期，重複執行不改變版本。
	public QuotationDraftSnapshot expire(QuotationDraftSnapshot draft) {
		requireDraft(draft);
		if (draft.status() == QuotationDraftStatus.EXPIRED) return draft;

		if (draft.status() == QuotationDraftStatus.CONFIRMED || draft.status() == QuotationDraftStatus.CANCELLED) {
			throw error("TERMINAL_DRAFT", "已確認或取消的草稿不可標記逾期");
		}

		return copy(
			draft,
			draft.revision() + 1,
			QuotationDraftStatus.EXPIRED,
			draft.imageMessageIds(),
			draft.selectedImageMessageId(),
			draft.imageQuestionAsked(),
			draft.imageDeclined(),
			draft.previewPresented(),
			draft.confirmationEventId()
		);
	}

	// 方法：整理缺少的必要抬頭欄位並維持固定顯示順序。
	private List<String> findMissingBaseFields(QuotationDraftSnapshot draft) {
		List<String> missingFields = new ArrayList<>();
		if (isBlank(draft.schemeCode())) missingFields.add("schemeCode");

		for (String field : REQUIRED_BASE_FIELDS) {
			if (isBlank(draft.baseFields().get(field))) missingFields.add(field);
		}
		return List.copyOf(missingFields);
	}

	// 方法：整理所有品項的缺少欄位，供 LINE 一次列出完整補件清單。
	private List<QuotationMissingItemFields> findMissingItemFields(QuotationDraftSnapshot draft) {
		if (draft.items().isEmpty()) return List.of(new QuotationMissingItemFields("items", List.of("item")));

		List<QuotationMissingItemFields> missingItems = new ArrayList<>();
		for (QuotationDraftItem item : draft.items()) {
			List<String> requiredFields = item.kind() == QuotationDraftItemKind.STANDARD
				? STANDARD_ITEM_FIELDS
				: CUSTOM_ITEM_FIELDS;
			List<String> missingFields = requiredFields.stream()
				.filter(field -> isBlank(item.fields().get(field)))
				.toList();
			if (!missingFields.isEmpty()) {
				missingItems.add(new QuotationMissingItemFields(item.itemKey(), missingFields));
			}
		}
		return List.copyOf(missingItems);
	}

	// 方法：只有船用、空白、銷售保留動態品項上限；CNS／一般架臨時品項不限筆數。
	private void validateItemLimits(QuotationDraftSnapshot draft) {
		String schemeCode = normalizeSchemeCode(draft.schemeCode());
		if (Set.of("MARINE", "BLANK", "SALES").contains(schemeCode) && draft.items().size() > 200) {
			throw error("ITEM_LIMIT_EXCEEDED", "船用／空白／銷售每張報價最多 200 筆品項");
		}
	}

	// 方法：驗證候選圖片、選圖與拒絕圖片狀態彼此一致。
	private void validateImageState(QuotationDraftSnapshot draft) {
		if (draft.imageDeclined() && !isBlank(draft.selectedImageMessageId())) {
			throw error("INVALID_IMAGE_STATE", "已拒絕嵌入圖片時不可同時保留選中圖片");
		}
		if (!isBlank(draft.selectedImageMessageId())
			&& !draft.imageMessageIds().contains(draft.selectedImageMessageId())) {
			throw error("INVALID_IMAGE_SELECTION", "選中的圖片不在草稿候選圖片內");
		}
	}

	// 方法：判斷船用與空白格式是否仍需取得圖片或明確拒絕。
	private boolean mustRequestImage(QuotationDraftSnapshot draft) {
		String schemeCode = normalizeSchemeCode(draft.schemeCode());
		return IMAGE_REQUIRED_SCHEMES.contains(schemeCode)
			&& isBlank(draft.selectedImageMessageId())
			&& !draft.imageDeclined();
	}

	// 方法：合併已存在品項的局部欄位，不接受未知品項識別。
	private List<QuotationDraftItem> mergeItemPatches(
		List<QuotationDraftItem> items,
		Map<String, Map<String, String>> itemFieldPatches
	) {
		if (itemFieldPatches.isEmpty()) return items;

		Set<String> remainingKeys = new LinkedHashSet<>(itemFieldPatches.keySet());
		List<QuotationDraftItem> mergedItems = new ArrayList<>();
		for (QuotationDraftItem item : items) {
			Map<String, String> fields = new LinkedHashMap<>(item.fields());
			Map<String, String> fieldPatch = itemFieldPatches.get(item.itemKey());
			if (fieldPatch != null) {
				fields.putAll(fieldPatch);
				remainingKeys.remove(item.itemKey());
			}
			mergedItems.add(new QuotationDraftItem(item.itemKey(), item.kind(), fields));
		}
		if (!remainingKeys.isEmpty()) {
			throw error("UNKNOWN_ITEM", "找不到要補件的品項：" + String.join("、", remainingKeys));
		}
		return List.copyOf(mergedItems);
	}

	// 方法：合併候選圖片識別並保持首次出現順序。
	private List<String> mergeImageMessageIds(List<String> current, List<String> additions) {
		Set<String> merged = new LinkedHashSet<>(current);
		for (String messageId : additions) {
			if (isBlank(messageId)) throw error("INVALID_IMAGE", "候選圖片識別不可留空");

			merged.add(messageId.trim());
		}
		return List.copyOf(merged);
	}

	// 方法：套用明確拒絕圖片的選擇，且僅能在系統已詢問後接受。
	private boolean resolveImageDeclined(
		QuotationDraftSnapshot draft,
		QuotationDraftPatch patch,
		List<String> imageMessageIds
	) {
		if (!patch.imageMessageIdsToAdd().isEmpty()) return false;

		if (patch.imageDeclined() == null) return draft.imageDeclined();

		if (patch.imageDeclined() && !draft.imageQuestionAsked()) {
			throw error("IMAGE_QUESTION_REQUIRED", "尚未詢問圖片，不可直接標記拒絕圖片");
		}
		if (patch.imageDeclined() && !isBlank(draft.selectedImageMessageId())) {
			throw error("INVALID_IMAGE_STATE", "已有選中圖片時不可拒絕嵌入圖片");
		}
		return patch.imageDeclined();
	}

	// 方法：建立狀態決策並只在狀態實際改變時增加版本。
	private QuotationConversationDecision decision(
		QuotationDraftSnapshot draft,
		QuotationDraftStatus status,
		List<String> missingBaseFields,
		List<QuotationMissingItemFields> missingItemFields,
		QuotationNextAction nextAction
	) {
		QuotationDraftSnapshot transitioned = draft.status() == status
			? draft
			: copy(
				draft,
				draft.revision() + 1,
				status,
				draft.imageMessageIds(),
				draft.selectedImageMessageId(),
				draft.imageQuestionAsked(),
				draft.imageDeclined(),
				draft.previewPresented(),
				draft.confirmationEventId()
			);
		return new QuotationConversationDecision(
			transitioned,
			missingBaseFields,
			missingItemFields,
			nextAction
		);
	}

	// 方法：將終態映射為穩定決策，避免後續訊息重新啟動流程。
	private QuotationConversationDecision terminalDecision(QuotationDraftSnapshot draft) {
		return switch (draft.status()) {
			case CONFIRMED -> new QuotationConversationDecision(draft, List.of(), List.of(), QuotationNextAction.CONFIRMED);
			case CANCELLED -> new QuotationConversationDecision(draft, List.of(), List.of(), QuotationNextAction.CANCELLED);
			case EXPIRED -> new QuotationConversationDecision(draft, List.of(), List.of(), QuotationNextAction.EXPIRED);
			default -> null;
		};
	}

	// 方法：複製草稿並集中維護所有狀態欄位。
	private QuotationDraftSnapshot copy(
		QuotationDraftSnapshot draft,
		int revision,
		QuotationDraftStatus status,
		List<String> imageMessageIds,
		String selectedImageMessageId,
		boolean imageQuestionAsked,
		boolean imageDeclined,
		boolean previewPresented,
		String confirmationEventId
	) {
		return new QuotationDraftSnapshot(
			draft.draftId(),
			revision,
			status,
			draft.schemeCode(),
			draft.baseFields(),
			draft.items(),
			imageMessageIds,
			selectedImageMessageId,
			imageQuestionAsked,
			imageDeclined,
			previewPresented,
			confirmationEventId
		);
	}

	// 方法：拒絕對終態草稿套用補件或互動狀態。
	private void requireMutable(QuotationDraftSnapshot draft) {
		requireDraft(draft);
		if (draft.status() == QuotationDraftStatus.CONFIRMED
			|| draft.status() == QuotationDraftStatus.CANCELLED
			|| draft.status() == QuotationDraftStatus.EXPIRED) {
			throw error("TERMINAL_DRAFT", "終態草稿不可再修改");
		}
	}

	// 方法：驗證草稿物件及必要狀態欄位存在。
	private void requireDraft(QuotationDraftSnapshot draft) {
		if (draft == null) throw error("INVALID_DRAFT", "草稿不可留空");

		if (draft.status() == null) throw error("INVALID_DRAFT", "草稿狀態不可留空");
	}

	// 方法：統一報價格式代碼以進行規則判斷。
	private String normalizeSchemeCode(String schemeCode) {
		return schemeCode == null ? "" : schemeCode.trim().toUpperCase(Locale.ROOT);
	}

	// 方法：判斷文字欄位是否缺少有效內容。
	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	// 方法：建立具有固定錯誤代碼的狀態機例外。
	private QuotationConversationException error(String code, String message) {
		return new QuotationConversationException(code, message);
	}
}
