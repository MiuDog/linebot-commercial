package dev.myudog.assetsmanagerlinebot.service.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotationConversationServiceTest {

	private final QuotationConversationService service = new QuotationConversationService();

	// 方法：基礎資料缺少時一次回報所有欄位。
	@Test
	void reportsAllMissingBaseFieldsInOneDecision() {
		QuotationDraftSnapshot draft = draft("", "", "", "CNS", List.of(standardItem("FRAME", "10")));

		QuotationConversationDecision decision = service.review(draft);

		assertThat(decision.draft().status()).isEqualTo(QuotationDraftStatus.COLLECTING_BASE_INFO);
		assertThat(decision.missingBaseFields()).containsExactly(
			"companyName",
			"workName",
			"salesRepresentative"
		);
		assertThat(decision.nextAction()).isEqualTo(QuotationNextAction.REQUEST_BASE_FIELDS);
	}

	// 方法：局部補齊基礎資料後只追問仍缺少的欄位。
	@Test
	void revalidatesOnlyRemainingBaseFieldsAfterPartialPatch() {
		QuotationDraftSnapshot draft = draft("", "", "CNS", List.of(standardItem("FRAME", "10")));
		QuotationDraftPatch patch = new QuotationDraftPatch(
			Map.of("companyName", "正定公司"),
			Map.of(),
			List.of(),
			null,
			null
		);

		QuotationConversationDecision decision = service.applyPatch(draft, patch);

		assertThat(decision.missingBaseFields()).containsExactly("workName");
		assertThat(decision.draft().baseFields().get("companyName")).isEqualTo("正定公司");
	}

	// 方法：低信心欄位以 null 補入時仍列為缺漏而不猜測內容。
	@Test
	void keepsNullLowConfidenceFieldAsMissing() {
		QuotationDraftSnapshot draft = draft("正定公司", "碼頭工程", "CNS", List.of(standardItem("FRAME", "10")));
		Map<String, String> baseFieldPatch = new LinkedHashMap<>();
		baseFieldPatch.put("workName", null);
		QuotationDraftPatch patch = new QuotationDraftPatch(baseFieldPatch, Map.of(), List.of(), null, null);

		QuotationConversationDecision decision = service.applyPatch(draft, patch);

		assertThat(decision.draft().status()).isEqualTo(QuotationDraftStatus.COLLECTING_BASE_INFO);
		assertThat(decision.missingBaseFields()).containsExactly("workName");
	}

	// 方法：多個品項不完整時一次列出各品項的全部缺漏。
	@Test
	void reportsAllMissingItemFieldsInOneDecision() {
		QuotationDraftItem standard = standardItem("FRAME", "");
		QuotationDraftItem custom = customItem("custom-1", Map.of("itemName", "特殊材料"));
		QuotationDraftSnapshot draft = draft("正定公司", "碼頭工程", "CNS", List.of(standard, custom));

		QuotationConversationDecision decision = service.review(draft);

		assertThat(decision.draft().status()).isEqualTo(QuotationDraftStatus.COLLECTING_ITEMS);
		assertThat(decision.missingItemFields()).containsExactly(
			new QuotationMissingItemFields("FRAME", List.of("quantity")),
			new QuotationMissingItemFields(
				"custom-1",
				List.of("specification", "unit", "unitPrice", "quantity", "remark")
			)
		);
	}

	// 方法：船用報價沒有圖片時必須先詢問圖片。
	@Test
	void marineRequiresImageQuestionBeforePreview() {
		QuotationDraftSnapshot draft = draft("正定公司", "船塢工程", "MARINE", List.of(standardItem("PACKAGE", "1")));

		QuotationConversationDecision decision = service.review(draft);

		assertThat(decision.draft().status()).isEqualTo(QuotationDraftStatus.AWAITING_IMAGE);
		assertThat(decision.nextAction()).isEqualTo(QuotationNextAction.REQUEST_IMAGE);
	}

	// 方法：空白報價經圖片詢問且明確拒絕後可以進入完整預覽。
	@Test
	void blankCanSkipImageOnlyAfterExplicitDecline() {
		QuotationDraftSnapshot draft = draft("正定公司", "臨時工程", "BLANK", List.of(customItemComplete("custom-1")));
		QuotationConversationDecision awaitingImage = service.review(draft);
		QuotationDraftSnapshot asked = service.markImageQuestionAsked(awaitingImage.draft());
		QuotationDraftPatch decline = new QuotationDraftPatch(Map.of(), Map.of(), List.of(), true, null);

		QuotationConversationDecision decision = service.applyPatch(asked, decline);

		assertThat(decision.draft().status()).isEqualTo(QuotationDraftStatus.READY_FOR_PREVIEW);
		assertThat(decision.nextAction()).isEqualTo(QuotationNextAction.SHOW_PREVIEW);
	}

	// 測試：取消報表嵌入後仍保留候選資產，空白格式可經再次詢問後明確拒絕。
	@Test
	void blankCanDeclineEmbeddingWhileRetainingCandidateAssets() {
		QuotationDraftSnapshot base = draft("正定公司", "臨時工程", "BLANK", List.of(customItemComplete("custom-1")));
		QuotationDraftSnapshot candidatesWithoutSelection = new QuotationDraftSnapshot(
			base.draftId(),
			base.revision(),
			base.status(),
			base.schemeCode(),
			base.baseFields(),
			base.items(),
			List.of("IMG-001", "IMG-002"),
			null,
			true,
			false,
			false,
			null
		);

		QuotationConversationDecision imageQuestion = service.review(candidatesWithoutSelection);
		QuotationConversationDecision preview = service.applyPatch(
			imageQuestion.draft(),
			new QuotationDraftPatch(Map.of(), Map.of(), List.of(), true, null)
		);

		assertThat(imageQuestion.nextAction()).isEqualTo(QuotationNextAction.REQUEST_IMAGE);
		assertThat(preview.nextAction()).isEqualTo(QuotationNextAction.SHOW_PREVIEW);
		assertThat(preview.draft().imageMessageIds()).containsExactly("IMG-001", "IMG-002");
		assertThat(preview.draft().selectedImageMessageId()).isNull();
		assertThat(preview.draft().imageDeclined()).isTrue();
	}

	// 方法：尚未詢問圖片時不可直接接受拒絕圖片。
	@Test
	void rejectsImageDeclineBeforeQuestionWasAsked() {
		QuotationDraftSnapshot draft = draft("正定公司", "臨時工程", "BLANK", List.of(customItemComplete("custom-1")));
		QuotationDraftPatch decline = new QuotationDraftPatch(Map.of(), Map.of(), List.of(), true, null);

		assertThatThrownBy(() -> service.applyPatch(draft, decline))
			.isInstanceOf(QuotationConversationException.class)
			.hasMessageContaining("尚未詢問圖片");
	}

	// 方法：只有顯示完整預覽後才可接受明確確認並產生確認意圖。
	@Test
	void confirmsOnlyAfterFullPreviewWithoutAllocatingNumber() {
		QuotationDraftSnapshot draft = draft("正定公司", "廠房工程", "CNS", List.of(standardItem("FRAME", "10")));
		QuotationConversationDecision preview = service.review(draft);
		QuotationDraftSnapshot awaitingConfirmation = service.markPreviewPresented(preview.draft());

		QuotationConfirmationIntent intent = service.confirm(awaitingConfirmation, "postback-confirm-1");

		assertThat(intent.draft().status()).isEqualTo(QuotationDraftStatus.CONFIRMED);
		assertThat(intent.confirmationEventId()).isEqualTo("postback-confirm-1");
		assertThat(intent.quotationSequence()).isNull();
	}

	// 方法：重複確認與重複取消都回傳相同終態而不重做動作。
	@Test
	void confirmationAndCancellationAreIdempotent() {
		QuotationDraftSnapshot ready = service.markPreviewPresented(
			service.review(draft("正定公司", "廠房工程", "CNS", List.of(standardItem("FRAME", "10")))).draft()
		);
		QuotationConfirmationIntent firstConfirmation = service.confirm(ready, "postback-confirm-1");
		QuotationConfirmationIntent repeatedConfirmation = service.confirm(
			firstConfirmation.draft(),
			"postback-confirm-2"
		);
		QuotationDraftSnapshot collecting = draft("", "", "GENERAL", List.of());
		QuotationDraftSnapshot firstCancellation = service.cancel(collecting);
		QuotationDraftSnapshot repeatedCancellation = service.cancel(firstCancellation);

		assertThat(repeatedConfirmation).isEqualTo(firstConfirmation);
		assertThat(repeatedCancellation).isEqualTo(firstCancellation);
	}

	// 方法：建立測試用草稿快照並保留可局部更新的欄位。
	private QuotationDraftSnapshot draft(
		String companyName,
		String workName,
		String schemeCode,
		List<QuotationDraftItem> items
	) {
		return draft(companyName, workName, "陳業務", schemeCode, items);
	}

	// 方法：建立可明確控制業務承辦是否缺漏的測試草稿。
	private QuotationDraftSnapshot draft(
		String companyName,
		String workName,
		String salesRepresentative,
		String schemeCode,
		List<QuotationDraftItem> items
	) {
		Map<String, String> baseFields = new LinkedHashMap<>();
		baseFields.put("companyName", companyName);
		baseFields.put("workName", workName);
		baseFields.put("salesRepresentative", salesRepresentative);
		return new QuotationDraftSnapshot(
			1L,
			1,
			QuotationDraftStatus.COLLECTING_BASE_INFO,
			schemeCode,
			baseFields,
			items,
			List.of(),
			null,
			false,
			false,
			false,
			null
		);
	}

	// 方法：建立固定品項測試資料。
	private QuotationDraftItem standardItem(String itemCode, String quantity) {
		return new QuotationDraftItem(
			itemCode,
			QuotationDraftItemKind.STANDARD,
			Map.of("itemCode", itemCode, "quantity", quantity)
		);
	}

	// 方法：建立部分完成的臨時品項測試資料。
	private QuotationDraftItem customItem(String itemKey, Map<String, String> fields) {
		return new QuotationDraftItem(itemKey, QuotationDraftItemKind.CUSTOM, fields);
	}

	// 方法：建立欄位完整的臨時品項測試資料。
	private QuotationDraftItem customItemComplete(String itemKey) {
		return customItem(
			itemKey,
			Map.of(
				"itemName", "搭架工程",
				"specification", "一式",
				"unit", "式",
				"unitPrice", "50000",
				"quantity", "1",
				"remark", "實做實算"
			)
		);
	}
}
