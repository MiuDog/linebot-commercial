package dev.miudog.linebotcommercial.service.quotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotationLineMessageBuilderTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";
	private static final Instant NOW = Instant.parse("2026-08-11T07:00:00Z");
	private static final String OWNER_ID = "U-sensitive-line-user-id";

	private final QuotationPostbackSigner signer = new QuotationPostbackSigner(
		SECRET,
		Clock.fixed(NOW, ZoneOffset.UTC)
	);
	private final QuotationLineMessageBuilder builder = new QuotationLineMessageBuilder(
		signer,
		Clock.fixed(NOW, ZoneOffset.UTC)
	);

	// 方法：基礎欄位補件以單一訊息一次列出並附取消按鈕。
	@Test
	void buildsOneBaseFieldRequestWithCancellation() {
		QuotationConversationDecision decision = decision(
			QuotationDraftStatus.COLLECTING_BASE_INFO,
			List.of("companyName", "workName", "phone"),
			List.of(),
			QuotationNextAction.REQUEST_BASE_FIELDS
		);

		List<QuotationLineMessage> messages = builder.build(decision, OWNER_ID, null);

		assertThat(messages).hasSize(1);
		assertThat(textOf(messages.getFirst())).contains("公司名稱", "工作名稱", "電話");
		assertThat(actionsOf(messages)).extracting(QuotationVerifiedPostback::action)
			.containsExactly(QuotationPostbackAction.CANCEL);
	}

	// 方法：報價格式只能由使用者按鈕指定，且未指定前不混問其他欄位。
	@Test
	void asksOnlyForTheSchemeWithSignedButtonsBeforeAnyOtherBaseField() {
		QuotationConversationDecision decision = decision(
			QuotationDraftStatus.COLLECTING_BASE_INFO,
			List.of("schemeCode", "companyName", "workName"),
			List.of(),
			QuotationNextAction.REQUEST_BASE_FIELDS
		);

		List<QuotationLineMessage> messages = builder.build(decision, OWNER_ID, null);

		assertThat(messages).hasSize(1);
		assertThat(textOf(messages.getFirst()))
			.contains("請先選擇報價格式", "系統不會自行判斷")
			.doesNotContain("公司名稱", "工作名稱");
		assertThat(actionsOf(messages))
			.filteredOn(action -> action.action() == QuotationPostbackAction.SELECT_SCHEME)
			.extracting(QuotationVerifiedPostback::resourceId)
			.containsExactly("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
		assertThat(actionsOf(messages)).extracting(QuotationVerifiedPostback::action)
			.contains(QuotationPostbackAction.CANCEL);
	}

	// 方法：所有不完整品項與欄位以單一清單詢問。
	@Test
	void buildsOneGroupedItemFieldRequest() {
		QuotationConversationDecision decision = decision(
			QuotationDraftStatus.COLLECTING_ITEMS,
			List.of(),
			List.of(
				new QuotationMissingItemFields("FRAME", List.of("quantity")),
				new QuotationMissingItemFields("custom-1", List.of("unit", "unitPrice", "remark"))
			),
			QuotationNextAction.REQUEST_ITEM_FIELDS
		);

		List<QuotationLineMessage> messages = builder.build(decision, OWNER_ID, null);

		assertThat(messages).hasSize(1);
		assertThat(textOf(messages.getFirst())).contains("FRAME", "數量", "custom-1", "單位", "單價", "備註");
		assertThat(actionsOf(messages)).extracting(QuotationVerifiedPostback::action)
			.containsExactly(QuotationPostbackAction.CANCEL);
	}

	// 方法：船用與空白圖片詢問提供明確拒絕及取消動作。
	@Test
	void buildsRequiredImageDecisionActions() {
		QuotationConversationDecision decision = decision(
			QuotationDraftStatus.AWAITING_IMAGE,
			List.of(),
			List.of(),
			QuotationNextAction.REQUEST_IMAGE
		);

		List<QuotationLineMessage> messages = builder.build(decision, OWNER_ID, null);

		assertThat(textOf(messages.getFirst())).contains("上傳", "圖片", "不提供圖片");
		assertThat(actionsOf(messages)).extracting(QuotationVerifiedPostback::action)
			.containsExactlyInAnyOrder(QuotationPostbackAction.DECLINE_IMAGE, QuotationPostbackAction.CANCEL);
	}

	// 方法：完整預覽將每筆明細縮成流水號、品項與數量，並保留三項金額及確認動作。
	@Test
	void buildsCompletePreviewWithBlankAndDynamicLinesAndTotals() {
		QuotationConversationDecision decision = decision(
			QuotationDraftStatus.READY_FOR_PREVIEW,
			List.of(),
			List.of(),
			QuotationNextAction.SHOW_PREVIEW
		);
		QuotationCalculationResult calculation = calculationResult();

		List<QuotationLineMessage> messages = builder.build(decision, OWNER_ID, calculation);
		String combined = messages.toString();

		assertThat(messages).hasSizeLessThanOrEqualTo(5);
		assertThat(combined).contains(
			"範例公司",
			"碼頭工程",
			"1. 外部鷹架 | 數量：—",
			"2. 特殊材料 | 數量：1",
			"未稅小計：5,000",
			"稅額：250",
			"含稅總額：5,250"
		);
		assertThat(combined).doesNotContain("規格：", "單位：", "單價：", "複價：", "備註：", "[動態]");
		assertThat(messages.getLast().payload().get("type")).isEqualTo("flex");
		assertThat(actionsOf(messages)).extracting(QuotationVerifiedPostback::action)
			.containsExactlyInAnyOrder(
				QuotationPostbackAction.CONFIRM,
				QuotationPostbackAction.MODIFY,
				QuotationPostbackAction.CANCEL
			);
		assertThat(messages.stream()
			.filter(message -> "text".equals(message.payload().get("type")))
			.map(message -> textOf(message).length()))
			.allMatch(length -> length <= 5000);
	}

	// 測試：AI 以近似名稱對應主檔時，預覽會請使用者一併確認對應結果。
	@Test
	void asksTheUserToConfirmApproximateItemNameMatchesInsideThePreview() {
		QuotationConversationDecision base = decision(
			QuotationDraftStatus.READY_FOR_PREVIEW,
			List.of(),
			List.of(),
			QuotationNextAction.SHOW_PREVIEW
		);
		QuotationDraftSnapshot source = base.draft();
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("itemCode", "EXTERNAL_SCAFFOLD");
		fields.put("itemName", "外部鷹架");
		fields.put("quantity", "2.5");
		fields.put("matchedName", "外牆鷹架");
		QuotationDraftSnapshot withMatchedName = new QuotationDraftSnapshot(
			source.draftId(),
			source.revision(),
			source.status(),
			source.schemeCode(),
			source.baseFields(),
			List.of(new QuotationDraftItem("EXTERNAL_SCAFFOLD", QuotationDraftItemKind.STANDARD, fields)),
			source.imageMessageIds(),
			source.selectedImageMessageId(),
			false,
			false,
			false,
			null
		);

		String combined = builder.build(
			new QuotationConversationDecision(
				withMatchedName,
				List.of(),
				List.of(),
				QuotationNextAction.SHOW_PREVIEW
			),
			OWNER_ID,
			calculationResult()
		).toString();

		assertThat(combined).contains("名稱對應", "外牆鷹架", "外部鷹架");
	}

	@Test
	void buildsImmediateGenerationAcceptedMessageWithQuotationNumber() {
		QuotationConfirmationResult confirmation = new QuotationConfirmationResult(
			1,
			"2026081101",
			java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26),
			1,
			"20260811-01",
			"範例-工程 20260811-01"
		);

		QuotationLineMessage message = builder.generationAccepted(confirmation);

		assertThat(textOf(message)).contains("2026081101", "已受理", "完成後另行通知");
	}

	// 測試：完整預覽提供已簽章的候選圖片改選與移除按鈕。
	@Test
	void buildsSignedCandidateImageReplacementAndRemovalActions() {
		QuotationConversationDecision base = decision(
			QuotationDraftStatus.READY_FOR_PREVIEW,
			List.of(),
			List.of(),
			QuotationNextAction.SHOW_PREVIEW
		);
		QuotationDraftSnapshot source = base.draft();
		QuotationDraftSnapshot withImages = new QuotationDraftSnapshot(
			source.draftId(),
			source.revision(),
			source.status(),
			source.schemeCode(),
			source.baseFields(),
			source.items(),
			List.of("IMG-001", "IMG-002"),
			"IMG-001",
			true,
			false,
			false,
			null
		);

		List<QuotationVerifiedPostback> actions = actionsOf(builder.build(
			new QuotationConversationDecision(
				withImages,
				List.of(),
				List.of(),
				QuotationNextAction.SHOW_PREVIEW
			),
			OWNER_ID,
			calculationResult()
		));
		List<QuotationLineMessage> preview = builder.build(
			new QuotationConversationDecision(
				withImages,
				List.of(),
				List.of(),
				QuotationNextAction.SHOW_PREVIEW
			),
			OWNER_ID,
			calculationResult()
		);

		assertThat(preview.getLast().payload()).containsKey("quickReply");
		assertThat(preview.subList(0, preview.size() - 1))
			.allSatisfy(message -> assertThat(message.payload()).doesNotContainKey("quickReply"));
		assertThat(actions).anySatisfy(action -> {
			assertThat(action.action()).isEqualTo(QuotationPostbackAction.SELECT_IMAGE);
			assertThat(action.resourceId()).isEqualTo("IMG-002");
		});
		assertThat(actions).anySatisfy(action -> {
			assertThat(action.action()).isEqualTo(QuotationPostbackAction.REMOVE_IMAGE);
			assertThat(action.resourceId()).isNull();
		});
	}

	// 測試：二十張候選圖片會分成合法 Quick Reply 批次，且每張都能被改選。
	@Test
	void exposesEveryCandidateAcrossQuickReplyBatches() {
		QuotationConversationDecision base = decision(
			QuotationDraftStatus.READY_FOR_PREVIEW,
			List.of(),
			List.of(),
			QuotationNextAction.SHOW_PREVIEW
		);
		List<String> imageIds = java.util.stream.IntStream.rangeClosed(1, 20)
			.mapToObj(number -> "IMG-%03d".formatted(number))
			.toList();
		QuotationDraftSnapshot source = base.draft();
		QuotationDraftSnapshot withImages = new QuotationDraftSnapshot(
			source.draftId(), source.revision(), source.status(), source.schemeCode(),
			source.baseFields(), source.items(), imageIds, imageIds.getFirst(),
			true, false, false, null
		);

		List<QuotationLineMessage> messages = builder.build(
			new QuotationConversationDecision(
				withImages, List.of(), List.of(), QuotationNextAction.SHOW_PREVIEW
			),
			OWNER_ID,
			calculationResult()
		);
		List<QuotationVerifiedPostback> firstPageActions = actionsOf(messages);
		QuotationVerifiedPostback nextPage = firstPageActions.stream()
			.filter(action -> action.action() == QuotationPostbackAction.IMAGE_OPTIONS_PAGE)
			.findFirst()
			.orElseThrow();
		QuotationLineMessage secondPage = builder.imageOptionsPage(
			withImages,
			OWNER_ID,
			Integer.parseInt(nextPage.resourceId())
		);
		List<QuotationVerifiedPostback> actions = new ArrayList<>(firstPageActions);
		actions.addAll(actionsOf(List.of(secondPage)));

		assertThat(messages).hasSizeLessThanOrEqualTo(5);
		assertThat(messages.getLast().payload()).containsKey("quickReply");
		assertThat(messages.subList(0, messages.size() - 1))
			.allSatisfy(message -> assertThat(message.payload()).doesNotContainKey("quickReply"));
		assertThat(secondPage.payload()).containsKey("quickReply");
		assertThat(actions.stream().filter(action -> action.action() == QuotationPostbackAction.SELECT_IMAGE))
			.hasSize(19);
		assertThat(actions.stream()
			.filter(action -> action.action() == QuotationPostbackAction.SELECT_IMAGE)
			.map(QuotationVerifiedPostback::resourceId))
			.containsExactlyElementsOf(imageIds.subList(1, imageIds.size()));
	}

	// 方法：建立具有完整抬頭的狀態決策。
	private QuotationConversationDecision decision(
		QuotationDraftStatus status,
		List<String> missingBaseFields,
		List<QuotationMissingItemFields> missingItemFields,
		QuotationNextAction nextAction
	) {
		Map<String, String> baseFields = new LinkedHashMap<>();
		baseFields.put("companyName", "範例公司");
		baseFields.put("workName", "碼頭工程");
		baseFields.put("contactName", "王先生");
		QuotationDraftSnapshot draft = new QuotationDraftSnapshot(
			42L,
			7,
			status,
			"BLANK",
			baseFields,
			List.of(),
			List.of(),
			null,
			false,
			false,
			false,
			null
		);
		return new QuotationConversationDecision(draft, missingBaseFields, missingItemFields, nextAction);
	}

	// 方法：建立同時具有固定空白列與動態計價列的預覽結果。
	private QuotationCalculationResult calculationResult() {
		QuotationCalculationResult.QuotationLine fixedBlank = new QuotationCalculationResult.QuotationLine(
			"FRAME",
			"外部鷹架",
			"一般料",
			"m2",
			new BigDecimal("180"),
			null,
			null,
			"實做實算",
			1,
			"DIRECT",
			QuotationCalculationResult.LineOrigin.STANDARD,
			true
		);
		QuotationCalculationResult.QuotationLine dynamic = new QuotationCalculationResult.QuotationLine(
			null,
			"特殊材料",
			"臨時規格",
			"式",
			new BigDecimal("5000"),
			BigDecimal.ONE,
			new BigDecimal("5000"),
			"經確認",
			2,
			"DIRECT",
			QuotationCalculationResult.LineOrigin.DYNAMIC,
			true
		);
		return new QuotationCalculationResult(
			"BLANK",
			List.of(fixedBlank, dynamic),
			List.of(fixedBlank, dynamic),
			new BigDecimal("5000"),
			new BigDecimal("250"),
			new BigDecimal("5250"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	// 方法：取得文字訊息的顯示內容。
	private String textOf(QuotationLineMessage message) {
		return (String) message.payload().get("text");
	}

	// 方法：遞迴擷取所有 postback 資料並驗證為目前使用者與版本。
	private List<QuotationVerifiedPostback> actionsOf(List<QuotationLineMessage> messages) {
		List<String> dataValues = new ArrayList<>();
		for (QuotationLineMessage message : messages) collectData(message.payload(), dataValues);

		return dataValues.stream()
			.map(data -> signer.verify(data, OWNER_ID, 7))
			.toList();
	}

	// 方法：從巢狀 LINE 訊息模型收集 postback data 欄位。
	private void collectData(Object value, List<String> dataValues) {
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if ("data".equals(entry.getKey()) && entry.getValue() instanceof String data) dataValues.add(data);
				else collectData(entry.getValue(), dataValues);
			}
		}
		else if (value instanceof List<?> list) {
			for (Object item : list) collectData(item, dataValues);
		}
	}
}
