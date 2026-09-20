package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationEventReceiptRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class QuotationLineWorkflowServiceTest {
	private static final String SIGNED_DATA = "v=1&d=9&r=4&a=x&e=1893456000&u=owner&s=signature";

	@Mock QuotationDraftWorkflowPort port;
	@Mock QuotationEventReceiptRepository receipts;
	@Mock QuotationConversationService conversation;
	@Mock QuotationPostbackSigner signer;
	@Mock QuotationLineMessageBuilder messages;
	@Mock QuotationConfirmationService confirmations;
	@Mock QuotationGenerationLauncher generationLauncher;

	QuotationLineWorkflowService service;

	@BeforeEach
	void setUp() {
		service = new QuotationLineWorkflowService(port, receipts, conversation, signer, messages, confirmations);
	}

	// 方法：整份測試文件只要含有獨立 #報價 指令行，就交由報價流程處理。
	@Test
	void recognizesQuotationDirectiveInsideMultilineTestDocument() {
		String document = """
			# 一般架測試
			```text
			#報價 一般架
			公司：範例公司
			```
			""";
		assertThat(service.isQuotationText("U1", document)).isTrue();
		verify(port, never()).hasActiveDraft("U1");
	}

	@Test
	void validatesOwnerExpiryAndRevisionBeforeCancelling() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.CANCEL,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(port.currentRevision(9, "U1")).thenReturn(4);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenAnswer(invocation -> {
			java.util.function.LongToIntFunction lookup = invocation.getArgument(2);
			assertThat(lookup.applyAsInt(9)).isEqualTo(4);
			return verified;

		});
		when(receipts.claim("EV1", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(draft, calculation()));
		when(conversation.cancel(draft)).thenReturn(draft(5, QuotationDraftStatus.CANCELLED));
		when(conversation.review(draft(5, QuotationDraftStatus.CANCELLED)))
			.thenReturn(new QuotationConversationDecision(draft(5, QuotationDraftStatus.CANCELLED), List.of(), List.of(), QuotationNextAction.CANCELLED));
		when(messages.build(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("U1"), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(new QuotationLineMessage(Map.of("type", "text", "text", "cancelled"))));

		List<QuotationLineMessage> result = service.handlePostback("EV1", "U1", SIGNED_DATA);

		assertThat(result).hasSize(1);
		verify(port).save(draft(5, QuotationDraftStatus.CANCELLED));
		verify(confirmations, never()).confirm(org.mockito.ArgumentMatchers.any());
	}

	// 方法：失敗或租約逾期的非確認 postback 必須交由狀態感知 claim 重領。
	@Test
	void reclaimsAFailedCancellationPostbackInsteadOfBlockingOnReceiptExistence() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.CANCEL,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(receipts.blocksReplay("EV-RETRY")).thenReturn(false);
		when(receipts.claim("EV-RETRY", 9L, "POSTBACK")).thenReturn(true);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(draft, calculation()));
		when(conversation.cancel(draft)).thenReturn(draft(5, QuotationDraftStatus.CANCELLED));
		when(conversation.review(draft(5, QuotationDraftStatus.CANCELLED)))
			.thenReturn(new QuotationConversationDecision(
				draft(5, QuotationDraftStatus.CANCELLED),
				List.of(),
				List.of(),
				QuotationNextAction.CANCELLED
			));
		when(messages.build(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(List.of(new QuotationLineMessage(Map.of("type", "text", "text", "cancelled"))));

		List<QuotationLineMessage> result = service.handlePostback("EV-RETRY", "U1", SIGNED_DATA);

		assertThat(result).hasSize(1);
		verify(receipts).claim("EV-RETRY", 9L, "POSTBACK");
		verify(port).save(draft(5, QuotationDraftStatus.CANCELLED));
	}

	@Test
	void confirmationCallsOnlyTheAuthorizedConfirmationApplicationService() {
		service = new QuotationLineWorkflowService(
			port, receipts, conversation, signer, messages, confirmations, generationLauncher
		);
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationDraftSnapshot confirmed = draft(5, QuotationDraftStatus.CONFIRMED);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.CONFIRM,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(port.currentRevision(9, "U1")).thenReturn(4);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenAnswer(invocation -> {
			java.util.function.LongToIntFunction lookup = invocation.getArgument(2);
			assertThat(lookup.applyAsInt(9)).isEqualTo(4);
			return verified;

		});
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(draft, calculation()));
		when(conversation.confirm(draft, "EV2"))
			.thenReturn(new QuotationConfirmationIntent(confirmed, "EV2", null));
		QuotationConfirmationResult formal = new QuotationConfirmationResult(
			1, "2026081101", java.time.LocalDate.of(2026, 8, 11),
			java.time.LocalDate.of(2026, 8, 26), 1, "20260811-01", "公司-工程 20260811-01"
		);
		when(confirmations.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(formal);
		QuotationLineMessage accepted = new QuotationLineMessage(Map.of(
			"type", "text",
			"text", "報價 2026081101 已受理"
		));
		when(messages.generationAccepted(formal)).thenReturn(accepted);

		List<QuotationLineMessage> result = service.handlePostback("EV2", "U1", SIGNED_DATA);

		assertThat(result).containsExactly(accepted);
		verify(port, never()).save(confirmed);
		verify(confirmations).confirm(new QuotationConfirmationCommand(
			new QuotationConfirmationIntent(confirmed, "EV2", null),
			calculation()
		));
		verify(generationLauncher).launch(org.mockito.ArgumentMatchers.argThat(command ->
			command.confirmation().equals(formal)
				&& command.calculation().equals(calculation())
				&& command.header().salesRepresentative().equals("陳業務")
				&& command.destinationId().equals("U1")
		));
		verify(receipts, never()).claim("EV2", 9L, "POSTBACK");
	}

	@Test
	void failedFormalConfirmationDoesNotPersistTerminalDraftState() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationDraftSnapshot confirmed = draft(5, QuotationDraftStatus.CONFIRMED);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.CONFIRM,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(draft, calculation()));
		when(conversation.confirm(draft, "EV-FAIL"))
			.thenReturn(new QuotationConfirmationIntent(confirmed, "EV-FAIL", null));
		when(confirmations.confirm(org.mockito.ArgumentMatchers.any()))
			.thenThrow(new QuotationConfirmationException("injected failure"));

		org.assertj.core.api.Assertions.assertThatThrownBy(
			() -> service.handlePostback("EV-FAIL", "U1", SIGNED_DATA)
		).isInstanceOf(QuotationConfirmationException.class);

		verify(port, never()).save(confirmed);
		verifyNoMoreInteractions(generationLauncher);
	}

	@Test
	void duplicateMessageReceiptDoesNotInvokeParsingOrPersistenceAgain() {
		when(receipts.claim("EV-DUP", null, "MESSAGE")).thenReturn(false);

		List<QuotationLineMessage> result = service.handleText("EV-DUP", "M1", "U1", "補件");

		assertThat(result).isEmpty();
		verify(port, never()).applyText("U1", "M1", "補件");
		verify(port, never()).save(org.mockito.ArgumentMatchers.any());
	}

	// 測試：已驗證 postback 的候選圖片識別會原樣交給草稿埠進行改選。
	@Test
	void selectsOnlyTheCandidateCarriedByTheVerifiedPostback() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationDraftWork work = new QuotationDraftWork(draft, calculation());
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.SELECT_IMAGE,
			Instant.parse("2030-01-01T00:00:00Z"),
			"IMG-002"
		);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(receipts.claim("EV-SELECT", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(work);
		when(port.selectImage(9, "U1", "IMG-002")).thenReturn(work);
		QuotationConversationDecision decision = new QuotationConversationDecision(
			draft,
			List.of(),
			List.of(),
			QuotationNextAction.REQUEST_CONFIRMATION
		);
		when(conversation.review(draft)).thenReturn(decision);
		when(messages.build(decision, "U1", calculation())).thenReturn(List.of());

		service.handlePostback("EV-SELECT", "U1", SIGNED_DATA);

		verify(port).selectImage(9, "U1", "IMG-002");
		verify(receipts).complete("EV-SELECT", 9L, false);
	}

	// 測試：使用者以按鈕指定的報價格式原樣交給草稿埠，格式不由 AI 決定。
	@Test
	void appliesOnlyTheSchemeCarriedByTheVerifiedPostback() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.COLLECTING_BASE_INFO);
		QuotationDraftWork work = new QuotationDraftWork(draft, calculation());
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.SELECT_SCHEME,
			Instant.parse("2030-01-01T00:00:00Z"),
			"CNS"
		);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(receipts.claim("EV-SCHEME", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(work);
		when(port.applyScheme(9, "U1", "CNS")).thenReturn(work);
		QuotationConversationDecision decision = new QuotationConversationDecision(
			draft,
			List.of(),
			List.of(),
			QuotationNextAction.REQUEST_BASE_FIELDS
		);
		when(conversation.review(draft)).thenReturn(decision);
		when(messages.build(decision, "U1", calculation())).thenReturn(List.of());

		service.handlePostback("EV-SCHEME", "U1", SIGNED_DATA);

		verify(port).applyScheme(9, "U1", "CNS");
		verify(receipts).complete("EV-SCHEME", 9L, false);
	}

	// 測試：拒絕圖片先保存單一 revision，再保存預覽狀態，避免跨版 CAS 失敗。
	@Test
	void persistsImageDeclineBeforePresentingThePreview() {
		QuotationDraftSnapshot awaitingImage = new QuotationDraftSnapshot(
			9,
			4,
			QuotationDraftStatus.AWAITING_IMAGE,
			"BLANK",
			Map.of("companyName", "公司", "workName", "工程", "salesRepresentative", "陳業務"),
			List.of(new QuotationDraftItem("custom-1", QuotationDraftItemKind.CUSTOM, Map.of(
				"itemName", "搭設工程",
				"specification", "一式",
				"unit", "式",
				"unitPrice", "100",
				"quantity", "1",
				"remark", "實做實算"
			))),
			List.of(),
			null,
			true,
			false,
			false,
			null
		);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.DECLINE_IMAGE,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(receipts.claim("EV-DECLINE", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(awaitingImage, calculation()));
		service = new QuotationLineWorkflowService(
			port,
			receipts,
			new QuotationConversationService(),
			signer,
			messages,
			confirmations
		);

		service.handlePostback("EV-DECLINE", "U1", SIGNED_DATA);

		var order = inOrder(port);
		order.verify(port).load(9, "U1");
		order.verify(port).save(org.mockito.ArgumentMatchers.argThat(draft ->
			draft.revision() == 5
				&& draft.status() == QuotationDraftStatus.READY_FOR_PREVIEW
				&& draft.imageDeclined()
		));
		order.verify(port).save(org.mockito.ArgumentMatchers.argThat(draft ->
			draft.revision() == 6
				&& draft.status() == QuotationDraftStatus.AWAITING_CONFIRMATION
				&& draft.previewPresented()
		));
	}

	// 測試：已驗證的候選頁碼只顯示下一頁，不修改草稿版本或候選選取。
	@Test
	void showsTheSignedCandidateOptionsPageWithoutMutatingTheDraft() {
		QuotationDraftSnapshot draft = draft(4, QuotationDraftStatus.AWAITING_CONFIRMATION);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.IMAGE_OPTIONS_PAGE,
			Instant.parse("2030-01-01T00:00:00Z"),
			"1"
		);
		QuotationLineMessage page = new QuotationLineMessage(Map.of("type", "text", "text", "第二頁"));
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(receipts.claim("EV-PAGE", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(draft, calculation()));
		when(messages.imageOptionsPage(draft, "U1", 1)).thenReturn(page);

		List<QuotationLineMessage> result = service.handlePostback("EV-PAGE", "U1", SIGNED_DATA);

		assertThat(result).containsExactly(page);
		verify(messages).imageOptionsPage(draft, "U1", 1);
		verify(port, never()).save(org.mockito.ArgumentMatchers.any());
		verify(port, never()).selectImage(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString()
		);
		verify(receipts).complete("EV-PAGE", 9L, false);
	}

	// 測試：船用案件移除唯一圖片後，回到可上傳或明確拒絕的圖片詢問。
	@Test
	void removingTheOnlyMarineImageReturnsToTheImageQuestion() {
		QuotationDraftSnapshot original = new QuotationDraftSnapshot(
			9,
			4,
			QuotationDraftStatus.AWAITING_CONFIRMATION,
			"MARINE",
			Map.of("companyName", "範例", "workName", "船用工程", "salesRepresentative", "王先生"),
			List.of(),
			List.of("IMG-001"),
			"IMG-001",
			true,
			false,
			true,
			null
		);
		QuotationDraftSnapshot removed = new QuotationDraftSnapshot(
			9,
			5,
			QuotationDraftStatus.AWAITING_CONFIRMATION,
			"MARINE",
			original.baseFields(),
			List.of(),
			List.of("IMG-001"),
			null,
			true,
			false,
			false,
			null
		);
		QuotationDraftSnapshot awaitingImage = new QuotationDraftSnapshot(
			9,
			6,
			QuotationDraftStatus.AWAITING_IMAGE,
			"MARINE",
			removed.baseFields(),
			List.of(),
			List.of("IMG-001"),
			null,
			true,
			false,
			false,
			null
		);
		QuotationVerifiedPostback verified = new QuotationVerifiedPostback(
			9,
			4,
			QuotationPostbackAction.REMOVE_IMAGE,
			Instant.parse("2030-01-01T00:00:00Z")
		);
		when(signer.verify(
			org.mockito.ArgumentMatchers.eq(SIGNED_DATA),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.any(java.util.function.LongToIntFunction.class)
		)).thenReturn(verified);
		when(receipts.claim("EV-REMOVE", 9L, "POSTBACK")).thenReturn(true);
		when(port.load(9, "U1")).thenReturn(new QuotationDraftWork(original, calculation()));
		when(port.removeSelectedImage(9, "U1"))
			.thenReturn(new QuotationDraftWork(removed, calculation()));
		QuotationConversationDecision decision = new QuotationConversationDecision(
			awaitingImage,
			List.of(),
			List.of(),
			QuotationNextAction.REQUEST_IMAGE
		);
		when(conversation.review(removed)).thenReturn(decision);
		when(conversation.markImageQuestionAsked(awaitingImage)).thenReturn(awaitingImage);
		when(messages.build(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq("U1"),
			org.mockito.ArgumentMatchers.eq(calculation())
		)).thenReturn(List.of(new QuotationLineMessage(Map.of("type", "text", "text", "請上傳或拒絕"))));

		List<QuotationLineMessage> result = service.handlePostback("EV-REMOVE", "U1", SIGNED_DATA);

		assertThat(result).singleElement()
			.satisfies(message -> assertThat(message.payload().get("text")).isEqualTo("請上傳或拒絕"));
		verify(port).removeSelectedImage(9, "U1");
		verify(port).save(awaitingImage);
	}

	private QuotationDraftSnapshot draft(int revision, QuotationDraftStatus status) {
		return new QuotationDraftSnapshot(9, revision, status, "GENERAL", Map.of("companyName", "公司", "workName", "工程", "salesRepresentative", "陳業務"), List.of(), List.of(), null, false, false, true, null);
	}

	private QuotationCalculationResult calculation() {
		return new QuotationCalculationResult("GENERAL", List.of(), List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, QuotationCalculationResult.CustomerPresentation.DETAIL);
	}
}
