package dev.miudog.linebotcommercial.service.quotation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QuotationLineMessageBuilder {

	private static final int MAXIMUM_TEXT_LENGTH = 5000;
	private static final int TEXT_CHUNK_LENGTH = 4900;
	private static final int MAXIMUM_REPLY_MESSAGES = 5;
	private static final int IMAGE_OPTIONS_PAGE_SIZE = 10;
	private static final Duration POSTBACK_LIFETIME = Duration.ofMinutes(15);
	private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
		Map.entry("schemeCode", "報價格式"),
		Map.entry("companyName", "公司名稱"),
		Map.entry("workName", "工作名稱"),
		Map.entry("contactName", "聯絡人"),
		Map.entry("phone", "電話"),
		Map.entry("fax", "傳真"),
		Map.entry("email", "信箱"),
		Map.entry("projectLocation", "工程地點"),
		Map.entry("salesRepresentative", "業務承辦"),
		Map.entry("additionalHeader", "其他抬頭資料"),
		Map.entry("item", "品項"),
		Map.entry("itemName", "品項名稱"),
		Map.entry("specification", "規格"),
		Map.entry("unit", "單位"),
		Map.entry("unitPrice", "單價"),
		Map.entry("quantity", "數量"),
		Map.entry("remark", "備註")
	);

	private final QuotationPostbackSigner signer;
	private final Clock clock;

	// 方法：建立正式 LINE 報價訊息 builder。
	@Autowired
	public QuotationLineMessageBuilder(QuotationPostbackSigner signer) {
		this(signer, Clock.systemUTC());
	}

	// 方法：建立可注入固定時間的訊息 builder，供簽章期限測試使用。
	QuotationLineMessageBuilder(QuotationPostbackSigner signer, Clock clock) {
		this.signer = signer;
		this.clock = clock;
	}

	// 方法：由應用程式解讀 nextAction 並產生符合 LINE 限制的訊息。
	public List<QuotationLineMessage> build(
		QuotationConversationDecision decision,
		String ownerId,
		QuotationCalculationResult calculation
	) {
		if (decision == null || decision.draft() == null || decision.nextAction() == null) {
			throw error("INVALID_DECISION", "報價對話決策不完整");
		}

		return switch (decision.nextAction()) {
			case REQUEST_BASE_FIELDS -> List.of(baseFieldRequest(decision, ownerId));
			case REQUEST_ITEM_FIELDS -> List.of(itemFieldRequest(decision, ownerId));
			case REQUEST_IMAGE -> List.of(imageRequest(decision.draft(), ownerId));
			case REQUEST_IMAGE_SELECTION -> List.of(
				textMessage("已收到候選圖片，正在選擇最能代表案場的一張。", List.of(cancelAction(decision.draft(), ownerId)))
			);
			case SHOW_PREVIEW -> previewMessages(decision.draft(), ownerId, calculation);
			case REQUEST_CONFIRMATION -> List.of(confirmationFlex(decision.draft(), ownerId, calculation));
			case CONFIRMED -> List.of(textMessage("報價已確認，系統將開始建立檔案。", List.of()));
			case CANCELLED -> List.of(textMessage("報價草稿已取消。", List.of()));
			case EXPIRED -> List.of(textMessage("報價草稿已逾期，請重新建立。", List.of()));
		};
	}

	// 方法：正式確認後立即告知使用者工作已持久化，完成結果將以第二則 push 訊息送達。
	public QuotationLineMessage generationAccepted(QuotationConfirmationResult confirmation) {
		if (confirmation == null || confirmation.quotationNumber() == null) {
			throw error("CONFIRMATION_REQUIRED", "正式報價確認結果不完整");
		}

		return textMessage(
			"報價 " + confirmation.quotationNumber()
				+ " 已受理，正在產生 Excel 與 PDF；完成後另行通知並顯示執行時間。",
			List.of()
		);
	}

	// 方法：建立等待使用者輸入修改內容的提示，並保留簽章取消按鈕。
	public QuotationLineMessage modificationRequest(QuotationDraftSnapshot draft, String ownerId) {
		if (draft == null) throw error("INVALID_DRAFT", "報價草稿不可留空");

		return textMessage(
			"請輸入要修改或補充的內容；系統重新解析後會再次顯示完整預覽。",
			List.of(cancelAction(draft, ownerId))
		);
	}

	// 方法：建立一次列出所有頂部缺漏欄位的訊息。
	private QuotationLineMessage baseFieldRequest(QuotationConversationDecision decision, String ownerId) {
		// 報價格式必須由使用者指定，未指定前只問格式，不與其他欄位混在同一則訊息。
		if (decision.missingBaseFields().contains("schemeCode")) return schemeRequest(decision.draft(), ownerId);

		StringBuilder text = new StringBuilder("建立報價前，請一次補充以下基礎資訊：\n");
		for (String field : decision.missingBaseFields()) {
			text.append("• ").append(label(field)).append('\n');
		}
		return textMessage(text.toString().stripTrailing(), List.of(cancelAction(decision.draft(), ownerId)));
	}

	// 方法：建立一次列出所有品項缺漏欄位的訊息。
	private QuotationLineMessage itemFieldRequest(QuotationConversationDecision decision, String ownerId) {
		StringBuilder text = new StringBuilder("請補充以下品項資料；可分多次回答，系統只會再問仍缺少的欄位：\n");
		for (QuotationMissingItemFields missingItem : decision.missingItemFields()) {
			text.append("• ").append(itemDisplayName(decision.draft(), missingItem.itemKey())).append("：");
			text.append(missingItem.fields().stream().map(this::label).toList());
			text.append('\n');
		}
		return textMessage(text.toString().stripTrailing(), List.of(cancelAction(decision.draft(), ownerId)));
	}

	// 方法：對使用者以中文品名辨識缺漏列，無品名時以順序描述且不暴露內部 ID。
	private String itemDisplayName(QuotationDraftSnapshot draft, String itemKey) {
		if ("items".equals(itemKey)) return "品項";

		for (int index = 0; index < draft.items().size(); index++) {
			QuotationDraftItem item = draft.items().get(index);
			if (!item.itemKey().equals(itemKey)) continue;

			String itemName = item.fields().get("itemName");
			if (itemName != null && !itemName.isBlank()) return display(itemName, 120);

			String matchedName = item.fields().get("matchedName");
			if (matchedName != null && !matchedName.isBlank()) return display(matchedName, 120);

			return "第 " + (index + 1) + " 筆品項";
		}
		return "品項";
	}

	// 方法：建立只能由使用者指定報價格式的按鈕訊息，AI 不參與格式判定。
	private QuotationLineMessage schemeRequest(QuotationDraftSnapshot draft, String ownerId) {
		List<Map<String, Object>> actions = new ArrayList<>();
		for (String schemeCode : QuotationSchemeKeywords.schemeCodes()) {
			actions.add(postbackAction(
				QuotationSchemeKeywords.displayName(schemeCode),
				"報價格式：" + QuotationSchemeKeywords.displayName(schemeCode),
				draft,
				ownerId,
				QuotationPostbackAction.SELECT_SCHEME,
				schemeCode
			));
		}
		actions.add(cancelAction(draft, ownerId));
		return textMessage(
			"請先選擇報價格式；格式由您指定，系統不會自行判斷。\n"
				+ "選好後請再送出一次報價內容（品項與數量）。",
			List.copyOf(actions)
		);
	}

	// 方法：建立必須提供圖片或明確拒絕的詢問訊息。
	private QuotationLineMessage imageRequest(QuotationDraftSnapshot draft, String ownerId) {
		List<Map<String, Object>> actions = List.of(
			postbackAction("不提供圖片", "我不提供圖片", draft, ownerId, QuotationPostbackAction.DECLINE_IMAGE),
			cancelAction(draft, ownerId)
		);
		return textMessage("請上傳一張最能代表案場或工程內容的圖片。若不提供圖片，請明確點選「不提供圖片」。", actions);
	}

	// 方法：建立包含完整明細文字與確認 Flex 的最終預覽。
	private List<QuotationLineMessage> previewMessages(
		QuotationDraftSnapshot draft,
		String ownerId,
		QuotationCalculationResult calculation
	) {
		requireCalculation(calculation);
		List<String> sections = previewSections(draft, calculation);
		List<String> chunks = packSections(sections, TEXT_CHUNK_LENGTH);
		if (chunks.size() + 1 > MAXIMUM_REPLY_MESSAGES) {
			throw error("PREVIEW_TOO_LONG", "完整預覽超過 LINE 單次回覆上限，請先減少品項或分批確認");
		}

		List<QuotationLineMessage> messages = new ArrayList<>();
		for (String chunk : chunks) messages.add(textMessage(chunk, List.of()));

		messages.add(confirmationFlex(draft, ownerId, calculation));
		return List.copyOf(messages);
	}

	// 方法：建立可安全翻頁的候選圖片操作訊息，該訊息本身是回覆中的最後一則。
	public QuotationLineMessage imageOptionsPage(
		QuotationDraftSnapshot draft,
		String ownerId,
		int page
	) {
		List<ImageCandidate> candidates = replacementCandidates(draft);
		int pageCount = Math.max(1, (candidates.size() + IMAGE_OPTIONS_PAGE_SIZE - 1) / IMAGE_OPTIONS_PAGE_SIZE);
		if (page < 0 || page >= pageCount) throw error("INVALID_IMAGE_PAGE", "圖片候選頁碼已失效");

		return textMessage(
			"候選圖片操作（第 " + (page + 1) + "／" + pageCount + " 頁）",
			previewImageActions(draft, ownerId, page)
		);
	}

	// 方法：建立指定頁面的候選圖片改選、翻頁與移除動作，所有參數都納入簽章。
	private List<Map<String, Object>> previewImageActions(
		QuotationDraftSnapshot draft,
		String ownerId,
		int page
	) {
		if (draft.selectedImageMessageId() == null) return List.of();

		List<ImageCandidate> candidates = replacementCandidates(draft);
		int pageCount = Math.max(1, (candidates.size() + IMAGE_OPTIONS_PAGE_SIZE - 1) / IMAGE_OPTIONS_PAGE_SIZE);
		if (page < 0 || page >= pageCount) throw error("INVALID_IMAGE_PAGE", "圖片候選頁碼已失效");

		List<Map<String, Object>> actions = new ArrayList<>();
		int start = page * IMAGE_OPTIONS_PAGE_SIZE;
		int end = Math.min(start + IMAGE_OPTIONS_PAGE_SIZE, candidates.size());
		for (ImageCandidate candidate : candidates.subList(start, end)) {
			actions.add(postbackAction(
				"改選第 " + candidate.number() + " 張",
				"改選第 " + candidate.number() + " 張圖片",
				draft,
				ownerId,
				QuotationPostbackAction.SELECT_IMAGE,
				candidate.messageId()
			));
		}
		if (page > 0) actions.add(imagePageAction(draft, ownerId, page - 1, "上一頁"));

		if (page + 1 < pageCount) actions.add(imagePageAction(draft, ownerId, page + 1, "下一頁"));

		actions.add(postbackAction(
			"移除目前圖片",
			"移除目前圖片",
			draft,
			ownerId,
			QuotationPostbackAction.REMOVE_IMAGE
		));
		return List.copyOf(actions);
	}

	// 方法：整理除目前選中圖片外的全部候選，並保留使用者上傳順序編號。
	private List<ImageCandidate> replacementCandidates(QuotationDraftSnapshot draft) {
		List<ImageCandidate> candidates = new ArrayList<>();
		for (int index = 0; index < draft.imageMessageIds().size(); index++) {
			String messageId = draft.imageMessageIds().get(index);
			if (!messageId.equals(draft.selectedImageMessageId())) {
				candidates.add(new ImageCandidate(index + 1, messageId));
			}
		}
		return List.copyOf(candidates);
	}

	// 方法：建立受簽章保護的候選圖片翻頁動作。
	private Map<String, Object> imagePageAction(
		QuotationDraftSnapshot draft,
		String ownerId,
		int page,
		String label
	) {
		return postbackAction(
			label,
			label,
			draft,
			ownerId,
			QuotationPostbackAction.IMAGE_OPTIONS_PAGE,
			Integer.toString(page)
		);
	}

	// 方法：整理抬頭、完整客戶列、圖片狀態、品項及總額的預覽段落。
	private List<String> previewSections(QuotationDraftSnapshot draft, QuotationCalculationResult calculation) {
		List<String> sections = new ArrayList<>();
		sections.add("報價完整預覽\n公司名稱：" + baseField(draft, "companyName")
			+ "\n工作名稱：" + baseField(draft, "workName")
			+ "\n報價格式：" + display(draft.schemeCode(), 30));

		for (Map.Entry<String, String> entry : draft.baseFields().entrySet()) {
			if ("companyName".equals(entry.getKey()) || "workName".equals(entry.getKey())) continue;

			if (entry.getValue() == null || entry.getValue().isBlank()) continue;

			sections.add(label(entry.getKey()) + "：" + display(entry.getValue(), 200));
		}

		String imageStatus = draft.selectedImageMessageId() != null
			? "已選擇 1 張圖片"
			: draft.imageDeclined() ? "使用者明確不提供圖片" : "未附圖片";
		sections.add("圖片：" + imageStatus);
		sections.add("品項明細（數量為 — 代表鎖價保留列）");

		int rowNumber = 1;
		for (QuotationCalculationResult.QuotationLine line : calculation.customerLines()) {
			sections.add(lineText(rowNumber++, line));
		}
		sections.add("未稅小計：" + money(calculation.subtotal())
			+ "\n稅額：" + money(calculation.tax())
			+ "\n含稅總額：" + money(calculation.total()));
		String matchedNames = matchedNameSection(draft);
		if (matchedNames != null) sections.add(matchedNames);

		return List.copyOf(sections);
	}

	// 方法：列出 AI 以近似名稱對應到的主檔品項，請使用者在確認前一併核對。
	private String matchedNameSection(QuotationDraftSnapshot draft) {
		StringBuilder text = new StringBuilder("名稱對應（請一併確認，若有誤請點「修改」）");
		boolean hasMatchedName = false;
		for (QuotationDraftItem item : draft.items()) {
			Map<String, String> fields = item.fields();
			String matchedName = fields.get("matchedName");
			if (matchedName == null || matchedName.isBlank()) continue;

			String masterName = fields.get("itemName") == null ? fields.get("itemCode") : fields.get("itemName");
			text.append("\n• 您說的「").append(display(matchedName, 100))
				.append("」已對應主檔品項「").append(display(masterName, 120)).append("」");
			hasMatchedName = true;
		}
		return hasMatchedName ? text.toString() : null;
	}

	// 方法：將每一列固定、臨時或動態品項完整轉成可讀文字。
	private String lineText(int rowNumber, QuotationCalculationResult.QuotationLine line) {
		return rowNumber + ". " + display(line.itemName(), 120) + " | 數量：" + number(line.quantity());
	}

	// 方法：將完整段落依 LINE 文字上限分割且不切斷單一段落。
	private List<String> packSections(List<String> sections, int maximumLength) {
		List<String> chunks = new ArrayList<>();
		StringBuilder chunk = new StringBuilder();
		for (String section : sections) {
			if (section.length() > maximumLength) throw error("PREVIEW_SECTION_TOO_LONG", "單筆預覽資料超過 LINE 文字限制");

			int appendedLength = chunk.isEmpty() ? section.length() : section.length() + 1;
			if (!chunk.isEmpty() && chunk.length() + appendedLength > maximumLength) {
				chunks.add(chunk.toString());
				chunk.setLength(0);
			}
			if (!chunk.isEmpty()) chunk.append('\n');

			chunk.append(section);
		}
		if (!chunk.isEmpty()) chunks.add(chunk.toString());

		return List.copyOf(chunks);
	}

	// 方法：建立含確認、修改與取消按鈕的 Flex 摘要。
	private QuotationLineMessage confirmationFlex(
		QuotationDraftSnapshot draft,
		String ownerId,
		QuotationCalculationResult calculation
	) {
		requireCalculation(calculation);
		List<Map<String, Object>> bodyContents = List.of(
			flexText("請確認報價內容", "lg", "bold"),
			flexText(baseField(draft, "companyName") + "｜" + baseField(draft, "workName"), "sm", "regular"),
			flexText("未稅 " + money(calculation.subtotal()), "sm", "regular"),
			flexText("稅額 " + money(calculation.tax()), "sm", "regular"),
			flexText("含稅總額 " + money(calculation.total()), "md", "bold")
		);
		List<Map<String, Object>> footerContents = List.of(
			flexButton("確認產生報價", postbackAction("確認產生報價", "確認產生報價", draft, ownerId, QuotationPostbackAction.CONFIRM), "primary"),
			flexButton("修改內容", postbackAction("修改內容", "我要修改報價", draft, ownerId, QuotationPostbackAction.MODIFY), "secondary"),
			flexButton("取消報價", cancelAction(draft, ownerId), "secondary")
		);

		Map<String, Object> bubble = new LinkedHashMap<>();
		bubble.put("type", "bubble");
		bubble.put("body", box("vertical", bodyContents));
		bubble.put("footer", box("vertical", footerContents));

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "flex");
		payload.put("altText", "報價完整預覽，請確認或取消");
		payload.put("contents", bubble);
		List<Map<String, Object>> imageActions = previewImageActions(draft, ownerId, 0);
		if (!imageActions.isEmpty()) payload.put("quickReply", quickReply(imageActions));

		return new QuotationLineMessage(payload);
	}

	// 方法：建立附 Quick Reply 的 LINE 文字訊息。
	private QuotationLineMessage textMessage(String text, List<Map<String, Object>> actions) {
		if (text == null || text.isBlank()) throw error("EMPTY_MESSAGE", "LINE 訊息不可留空");

		if (text.length() > MAXIMUM_TEXT_LENGTH) throw error("MESSAGE_TOO_LONG", "LINE 文字訊息超過 5,000 字");

		if (actions.size() > 13) throw error("TOO_MANY_QUICK_REPLIES", "Quick Reply 不可超過 13 個");

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", "text");
		payload.put("text", text);
		if (!actions.isEmpty()) payload.put("quickReply", quickReply(actions));

		return new QuotationLineMessage(payload);
	}

	// 方法：將不超過十三個動作包裝成 LINE Quick Reply payload。
	private Map<String, Object> quickReply(List<Map<String, Object>> actions) {
		if (actions.size() > 13) throw error("TOO_MANY_QUICK_REPLIES", "Quick Reply 不可超過 13 個");

		List<Map<String, Object>> items = actions.stream()
			.map(action -> Map.<String, Object>of("type", "action", "action", action))
			.toList();
		return Map.of("items", items);
	}

	// 方法：建立簽名、限時且綁定使用者與草稿版本的 postback 動作。
	private Map<String, Object> postbackAction(
		String label,
		String displayText,
		QuotationDraftSnapshot draft,
		String ownerId,
		QuotationPostbackAction action
	) {
		Instant expiresAt = clock.instant().plus(POSTBACK_LIFETIME);
		String data = signer.sign(draft.draftId(), draft.revision(), action, expiresAt, ownerId);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "postback");
		result.put("label", label);
		result.put("data", data);
		result.put("displayText", displayText);
		return result;
	}

	// 方法：建立攜帶候選資源識別且受 HMAC 保護的 LINE postback 動作。
	private Map<String, Object> postbackAction(
		String label,
		String displayText,
		QuotationDraftSnapshot draft,
		String ownerId,
		QuotationPostbackAction action,
		String resourceId
	) {
		Instant expiresAt = clock.instant().plus(POSTBACK_LIFETIME);
		String data = signer.sign(
			draft.draftId(),
			draft.revision(),
			action,
			expiresAt,
			ownerId,
			resourceId
		);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", "postback");
		result.put("label", label);
		result.put("data", data);
		result.put("displayText", displayText);
		return result;
	}

	// 方法：建立所有補件與預覽訊息共用的取消動作。
	private Map<String, Object> cancelAction(QuotationDraftSnapshot draft, String ownerId) {
		return postbackAction("取消報價", "取消報價", draft, ownerId, QuotationPostbackAction.CANCEL);
	}

	// 方法：建立 Flex 文字元件。
	private Map<String, Object> flexText(String text, String size, String weight) {
		return Map.of("type", "text", "text", display(text, 300), "size", size, "weight", weight, "wrap", true);
	}

	// 方法：建立 Flex 按鈕並沿用已簽名的 postback 動作。
	private Map<String, Object> flexButton(String label, Map<String, Object> action, String style) {
		return Map.of("type", "button", "style", style, "height", "sm", "action", action, "adjustMode", "shrink-to-fit");
	}

	// 方法：建立 Flex 垂直容器。
	private Map<String, Object> box(String layout, List<Map<String, Object>> contents) {
		return Map.of("type", "box", "layout", layout, "spacing", "sm", "contents", contents);
	}

	// 方法：取得頂部欄位並避免預覽中出現 null。
	private String baseField(QuotationDraftSnapshot draft, String field) {
		return display(draft.baseFields().get(field), 200);
	}

	// 方法：將固定欄位代碼轉成人員可理解的中文標籤。
	private String label(String field) {
		return FIELD_LABELS.getOrDefault(field, field);
	}

	// 方法：限制外部文字長度並以全形破折號顯示空白值。
	private String display(String value, int maximumLength) {
		if (value == null || value.isBlank()) return "—";

		String normalized = value.strip();
		return normalized.length() <= maximumLength
			? normalized
			: normalized.substring(0, maximumLength - 1) + "…";
	}

	// 方法：以千分位顯示金額並保留必要小數。
	private String money(BigDecimal value) {
		return value == null ? "—" : grouped(value.stripTrailingZeros().toPlainString());
	}

	// 方法：以千分位顯示一般數值並保留必要小數。
	private String number(BigDecimal value) {
		return value == null ? "—" : grouped(value.stripTrailingZeros().toPlainString());
	}

	// 方法：不改變數值精度地加入整數千分位。
	private String grouped(String value) {
		boolean negative = value.startsWith("-");
		String unsigned = negative ? value.substring(1) : value;
		String[] parts = unsigned.split("\\.", -1);
		String integer = parts[0];
		StringBuilder grouped = new StringBuilder();
		for (int index = 0; index < integer.length(); index++) {
			if (index > 0 && (integer.length() - index) % 3 == 0) grouped.append(',');

			grouped.append(integer.charAt(index));
		}
		if (parts.length > 1 && !parts[1].isEmpty()) grouped.append('.').append(parts[1]);

		return (negative ? "-" : "") + grouped;
	}

	// 方法：要求完整預覽具有由程式碼計算的金額結果。
	private void requireCalculation(QuotationCalculationResult calculation) {
		if (calculation == null) throw error("CALCULATION_REQUIRED", "完整預覽缺少程式計算結果");
	}

	// 方法：建立不暴露內部資料的 LINE 訊息錯誤。
	private QuotationLineMessageException error(String code, String message) {
		return new QuotationLineMessageException(code, message);
	}

	private record ImageCandidate(int number, String messageId) {}
}
