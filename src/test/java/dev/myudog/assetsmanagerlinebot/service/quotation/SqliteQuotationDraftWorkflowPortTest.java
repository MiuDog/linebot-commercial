package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SqliteQuotationDraftWorkflowPortTest {
	@TempDir Path temporaryDirectory;

	@Mock QuotationAiParsingService parser;
	@Mock QuotationCalculationService calculator;

	Connection connection;
	JdbcTemplate jdbc;
	SqliteQuotationDraftWorkflowPort port;
	FileStorageService storage;

	@BeforeEach
	void setUp() throws Exception {
		connection = DriverManager.getConnection("jdbc:sqlite::memory:");
		ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
		SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
		jdbc = new JdbcTemplate(dataSource);
		storage = new FileStorageService(temporaryDirectory.toString());
		port = new SqliteQuotationDraftWorkflowPort(
			jdbc,
			parser,
			calculator,
			new PendingImageRepository(JdbcClient.create(dataSource)),
			storage,
			new DataSourceTransactionManager(dataSource)
		);
		lenient().when(calculator.calculate(any())).thenReturn(calculation());
	}

	@AfterEach
	void tearDown() throws Exception {
		connection.close();
	}

	@Test
	void createsPrivateDraftPersistsMissingFieldsAndMergesLaterCorrection() {
		when(parser.parse("#報價 一般架，外部鷹架 2m2", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request(null, "工程A"), "{}"));
		when(parser.parse("公司是正定工程", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(headerOnlyRequest("正定工程", null), "{}"));

		QuotationDraftWork first = port.applyText("U1", "M1", "#報價 一般架，外部鷹架 2m2");
		QuotationDraftWork corrected = port.applyText("U1", "M2", "公司是正定工程");

		assertThat(first.draft().baseFields()).doesNotContainKey("companyName");
		assertThat(corrected.draft().draftId()).isEqualTo(first.draft().draftId());
		assertThat(corrected.draft().baseFields())
			.containsEntry("companyName", "正定工程")
			.containsEntry("workName", "工程A");
		assertThat(corrected.draft().items()).singleElement()
			.satisfies(item -> assertThat(item.fields()).containsEntry("quantity", "2"));
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_draft_message WHERE draft_id = ?",
			Integer.class,
			first.draft().draftId()
		)).isEqualTo(2);
	}

	// 方法：已提交訊息在 receipt crash 後重送時直接載入草稿，不重跑 AI 或增加 revision。
	@Test
	void returnsCommittedTextMessageWithoutReplayingAiOrMerge() {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		QuotationDraftWork first = port.applyText("U1", "REPLAY-TEXT", "#報價 一般架");
		clearInvocations(parser);

		QuotationDraftWork replayed = port.applyText("U1", "REPLAY-TEXT", "#報價 一般架");

		assertThat(replayed.draft().revision()).isEqualTo(first.draft().revision());
		assertThat(replayed.draft().baseFields()).isEqualTo(first.draft().baseFields());
		verifyNoInteractions(parser);
	}

	// 測試：使用者尚未指定報價格式前不呼叫 AI，草稿也不會被猜出格式。
	@Test
	void neverCallsAiBeforeTheUserHasSpecifiedTheQuotationScheme() {
		QuotationDraftWork work = port.applyText("U1", "M1", "#報價 幫我算外牆鷹架 2");

		assertThat(work.draft().schemeCode()).isNull();
		assertThat(work.draft().items()).isEmpty();
		verifyNoInteractions(parser);
	}

	// 測試：以按鈕指定格式後才解析，且格式一旦指定就不再改變。
	@Test
	void locksTheSchemeChosenByTheUserAndParsesWithIt() {
		QuotationDraftWork created = port.applyText("U1", "M1", "#報價 外牆鷹架 2");
		QuotationDraftWork schemed = port.applyScheme(created.draft().draftId(), "U1", "GENERAL");
		when(parser.parse("外部鷹架 2m2", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));

		QuotationDraftWork parsed = port.applyText("U1", "M2", "外部鷹架 2m2");

		assertThat(schemed.draft().schemeCode()).isEqualTo("GENERAL");
		assertThat(parsed.draft().schemeCode()).isEqualTo("GENERAL");
		assertThat(parsed.draft().items()).hasSize(1);
	}

	// 測試：格式指定後不可再改，避免使用者或 AI 在同一張報價中途換價目表。
	@Test
	void keepsTheFirstSchemeWhenAnotherSchemeIsRequestedLater() {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		QuotationDraftWork created = port.applyText("U1", "M1", "#報價 一般架");
		port.applyScheme(created.draft().draftId(), "U1", "SALES");

		assertThat(port.load(created.draft().draftId(), "U1").draft().schemeCode()).isEqualTo("GENERAL");
	}

	@Test
	void removesOnlyExplicitlyNamedStandardItems() {
		when(parser.parse("#報價 一般架，外部鷹架 2m2", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		when(parser.parse("刪除外部鷹架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(removalRequest("EXTERNAL_SCAFFOLD"), "{}"));

		QuotationDraftWork first = port.applyText("U1", "M1", "#報價 一般架，外部鷹架 2m2");
		QuotationDraftWork removed = port.applyText("U1", "M2", "刪除外部鷹架");

		assertThat(first.draft().items()).hasSize(1);
		assertThat(removed.draft().items()).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT is_removed FROM quotation_draft_item WHERE draft_id = ? AND item_code_snapshot = ?",
			Integer.class,
			first.draft().draftId(),
			"EXTERNAL_SCAFFOLD"
		)).isEqualTo(1);
	}

	@Test
	void bindsRevisionLookupToTheOwningLineUser() {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		QuotationDraftWork work = port.applyText("U1", "M1", "#報價 一般架");

		assertThat(port.currentRevision(work.draft().draftId(), "U1")).isEqualTo(work.draft().revision());
		org.assertj.core.api.Assertions.assertThatThrownBy(
			() -> port.currentRevision(work.draft().draftId(), "U2")
		).isInstanceOf(QuotationLineWorkflowException.class);
	}

	@Test
	void keepsEveryPendingOriginalSelectsHighestDistinctivenessAndAllowsReplacementOrRemoval() throws Exception {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		QuotationDraftWork created = port.applyText("U1", "TEXT1", "#報價 一般架");
		insertPending("IMG1", "one.jpg");
		insertPending("IMG2", "two.jpg");
		when(parser.parse(eq("請評估候選圖片並保留既有報價資料。"), anyList(), eq("GENERAL")))
			.thenReturn(new QuotationAiParsingService.ParseResult(
				requestWithImages(List.of(assessment("IMG1", "0.70", "0.95", "0.90"))),
				"{}"
			))
			.thenReturn(new QuotationAiParsingService.ParseResult(
				requestWithImages(List.of(
					assessment("IMG1", "0.70", "0.95", "0.90"),
					assessment("IMG2", "0.95", "0.70", "0.70")
				)),
				"{}"
			));

		port.attachImage("U1", "IMG1");
		QuotationDraftWork selected = port.attachImage("U1", "IMG2");

		assertThat(selected.draft().imageMessageIds()).containsExactly("IMG1", "IMG2");
		assertThat(selected.draft().selectedImageMessageId()).isEqualTo("IMG2");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isEqualTo(2);

		QuotationDraftWork replaced = port.selectImage(created.draft().draftId(), "U1", "IMG1");
		assertThat(replaced.draft().selectedImageMessageId()).isEqualTo("IMG1");
		QuotationDraftWork removed = port.removeSelectedImage(created.draft().draftId(), "U1");
		assertThat(removed.draft().selectedImageMessageId()).isNull();
		assertThat(removed.draft().imageMessageIds()).containsExactly("IMG1", "IMG2");
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_draft_image WHERE draft_id = ?",
			Integer.class,
			created.draft().draftId()
		)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_image", Integer.class)).isEqualTo(2);
	}

	@Test
	void evaluatesACompletedLineImageSetOnceWithEveryCandidate() throws Exception {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		port.applyText("U1", "TEXT1", "#報價 一般架");
		insertPending("IMG1", "SET1", 1, 2, "one.jpg");
		insertPending("IMG2", "SET1", 2, 2, "two.jpg");
		when(parser.parse(eq("請評估候選圖片並保留既有報價資料。"), anyList(), eq("GENERAL")))
			.thenReturn(new QuotationAiParsingService.ParseResult(
				requestWithImages(List.of(
					assessment("IMG1", "0.70", "0.95", "0.90"),
					assessment("IMG2", "0.95", "0.70", "0.70")
				)),
				"{}"
			));

		QuotationDraftWork selected = port.attachImages("U1", List.of("IMG1", "IMG2"));

		assertThat(selected.draft().imageMessageIds()).containsExactly("IMG1", "IMG2");
		assertThat(selected.draft().selectedImageMessageId()).isEqualTo("IMG2");
		verify(parser).parse(
			eq("請評估候選圖片並保留既有報價資料。"),
			org.mockito.ArgumentMatchers.argThat(images -> images.size() == 2),
			eq("GENERAL")
		);
		clearInvocations(parser);

		QuotationDraftWork replayed = port.attachImages("U1", List.of("IMG1", "IMG2"));

		assertThat(replayed.draft().revision()).isEqualTo(selected.draft().revision());
		assertThat(replayed.draft().selectedImageMessageId()).isEqualTo("IMG2");
		verifyNoInteractions(parser);
	}

	@Test
	void sendsQuotedBusinessCardImageToAiAndPersistsOcrHeaderFields() throws Exception {
		insertPending("CARD1", "business-card.jpg");
		when(parser.parse(eq("#報價 一般架"), anyList(), eq("GENERAL")))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));

		QuotationDraftWork work = port.applyText("U1", "TEXT1", "#報價 一般架", "CARD1");

		assertThat(work.draft().baseFields()).containsEntry("companyName", "正定工程");
		assertThat(work.draft().baseFields()).containsEntry("salesRepresentative", "陳業務");
		assertThat(work.draft().imageMessageIds()).containsExactly("CARD1");
		assertThat(jdbc.queryForMap("""
			SELECT field_value, source_message_id, source_text, confidence
			FROM quotation_draft_field
			WHERE draft_id = ? AND field_key = 'companyName'
			""", work.draft().draftId()))
			.containsEntry("field_value", "正定工程")
			.containsEntry("source_message_id", "TEXT1")
			.containsEntry("source_text", "正定工程")
			.satisfies(row -> assertThat((Number) row.get("confidence")).isNotNull());
		assertThat(jdbc.queryForMap("""
			SELECT field_value, source_message_id, source_text, confidence
			FROM quotation_draft_field
			WHERE draft_id = ? AND field_key = 'salesRepresentative'
			""", work.draft().draftId()))
			.containsEntry("field_value", "陳業務")
			.containsEntry("source_message_id", "TEXT1")
			.containsEntry("source_text", "陳業務")
			.satisfies(row -> assertThat((Number) row.get("confidence")).isNotNull());
		verify(parser).parse(
			eq("#報價 一般架"),
			org.mockito.ArgumentMatchers.argThat(images ->
				images.size() == 1 && "CARD1".equals(images.getFirst().messageId())
			),
			eq("GENERAL")
		);
	}

	@Test
	void ignoresAiImageDeclineUntilTheSignedDeclineActionFollowsAnImageQuestion() {
		when(parser.parse("#報價 空白，不提供圖片", List.of(), "BLANK"))
			.thenReturn(new QuotationAiParsingService.ParseResult(requestWithImageDecline(), "{}"));

		QuotationDraftWork work = port.applyText("U1", "M1", "#報價 空白，不提供圖片");

		assertThat(work.draft().imageDeclined()).isFalse();
	}

	@Test
	void mergesAIncompleteCustomItemAcrossMessagesByStableClientItemId() {
		when(parser.parse("#報價 銷售，品項是特製扣件，數量 2", List.of(), "SALES"))
			.thenReturn(new QuotationAiParsingService.ParseResult(customItemRequest(false), "{}"));
		when(parser.parse("特製扣件的規格 M8、單位 pcs、單價 50、備註鍍鋅", List.of(), "SALES"))
			.thenReturn(new QuotationAiParsingService.ParseResult(customItemRequest(true), "{}"));

		QuotationDraftWork first = port.applyText("U1", "M1", "#報價 銷售，品項是特製扣件，數量 2");
		QuotationDraftWork completed = port.applyText("U1", "M2", "特製扣件的規格 M8、單位 pcs、單價 50、備註鍍鋅");

		assertThat(first.draft().items()).singleElement();
		assertThat(completed.draft().items()).singleElement().satisfies(item ->
			assertThat(item.fields())
				.containsEntry("itemName", "特製扣件")
				.containsEntry("specification", "M8")
				.containsEntry("unit", "pcs")
				.containsEntry("unitPrice", "50")
				.containsEntry("quantity", "2")
				.containsEntry("remark", "鍍鋅")
		);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_draft_item WHERE draft_id = ?",
			Integer.class,
			first.draft().draftId()
		)).isEqualTo(1);
	}

	@Test
	void rejectsAbsoluteAndEscapingPendingImagePathsBeforeCallingAi() throws Exception {
		when(parser.parse("#報價 一般架", List.of(), "GENERAL"))
			.thenReturn(new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}"));
		port.applyText("U1", "TEXT1", "#報價 一般架");
		insertPending("ABS", "absolute.jpg");
		jdbc.update("UPDATE pending_image SET staging_path = ? WHERE message_id = 'ABS'", temporaryDirectory.resolve(".pending/absolute.jpg").toString());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> port.attachImage("U1", "ABS"))
			.isInstanceOf(QuotationLineWorkflowException.class);

		insertPending("ESCAPE", "escape.jpg");
		jdbc.update("UPDATE pending_image SET staging_path = '../escape.jpg' WHERE message_id = 'ESCAPE'");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> port.attachImage("U1", "ESCAPE"))
			.isInstanceOf(QuotationLineWorkflowException.class);
	}

	@Test
	void slowAiDoesNotHoldDatabaseTransactionOrBlockConcurrentReads() throws Exception {
		CountDownLatch parsingStarted = new CountDownLatch(1);
		CountDownLatch allowResponse = new CountDownLatch(1);
		when(parser.parse("#報價 一般架", List.of(), "GENERAL")).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			parsingStarted.countDown();
			assertThat(allowResponse.await(5, TimeUnit.SECONDS)).isTrue();

			return new QuotationAiParsingService.ParseResult(request("正定工程", "工程A"), "{}");

		});

		CompletableFuture<QuotationDraftWork> future = CompletableFuture.supplyAsync(
			() -> port.applyText("U1", "M1", "#報價 一般架")
		);
		assertThat(parsingStarted.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_draft", Integer.class)).isEqualTo(1);
		allowResponse.countDown();
		assertThat(future.get(5, TimeUnit.SECONDS).draft().items()).hasSize(1);
	}

	@Test
	void staleAiResponseCannotOverwriteNewerDraftRevision() throws Exception {
		CountDownLatch parsingStarted = new CountDownLatch(1);
		CountDownLatch allowResponse = new CountDownLatch(1);
		when(parser.parse("#報價 一般架", List.of(), "GENERAL")).thenAnswer(invocation -> {
			parsingStarted.countDown();
			assertThat(allowResponse.await(5, TimeUnit.SECONDS)).isTrue();

			return new QuotationAiParsingService.ParseResult(request("舊公司", "舊工程"), "{}");

		});

		CompletableFuture<QuotationDraftWork> future = CompletableFuture.supplyAsync(
			() -> port.applyText("U1", "M1", "#報價 一般架")
		);
		assertThat(parsingStarted.await(5, TimeUnit.SECONDS)).isTrue();
		jdbc.update("UPDATE quotation_draft SET company_name = '較新公司', revision = revision + 1");
		allowResponse.countDown();

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
			.hasRootCauseInstanceOf(QuotationLineWorkflowException.class);
		assertThat(jdbc.queryForObject("SELECT company_name FROM quotation_draft", String.class)).isEqualTo("較新公司");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_draft_item", Integer.class)).isZero();
	}

	private void insertPending(String messageId, String fileName) throws Exception {
		insertPending(messageId, messageId, 1, 1, fileName);
	}

	private void insertPending(
		String messageId,
		String imageSetId,
		int imageIndex,
		int imageTotal,
		String fileName
	) throws Exception {
		Path file = temporaryDirectory.resolve(".pending").resolve(fileName);
		Files.createDirectories(file.getParent());
		// 檔案系統：建立各候選圖獨立的 pending 原圖。
		Files.write(file, messageId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		jdbc.update("""
			INSERT INTO pending_image (
				message_id, image_set_id, image_index, image_total, source_type,
				source_id, uploader_id, staging_path, content_type, file_size, received_at
			) VALUES (?, ?, ?, ?, 'user', 'U1', 'U1', ?, 'image/jpeg', ?, ?)
			""", messageId, imageSetId, imageIndex, imageTotal,
			".pending/" + fileName, Files.size(file), Instant.now().toString());
	}

	private QuotationRequestValidationService.ImageAssessment assessment(
		String messageId,
		String distinctiveness,
		String quality,
		String viewpoint
	) {
		return new QuotationRequestValidationService.ImageAssessment(
			messageId,
			new BigDecimal(quality),
			new BigDecimal(viewpoint),
			new BigDecimal(distinctiveness),
			"候選評分"
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest requestWithImages(
		List<QuotationRequestValidationService.ImageAssessment> assessments
	) {
		QuotationRequestValidationService.ValidatedQuotationRequest base = request(null, null);
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			base.schemaVersion(),
			base.schemeCode(),
			base.schemeConfidence(),
			base.headerPatch(),
			base.standardItems(),
			base.customItems(),
			base.removedItemCodes(),
			assessments,
			assessments.getFirst().messageId(),
			false,
			base.missingBaseFields(),
			base.missingItemFields(),
			base.nextAction(),
			base.warnings()
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest requestWithImageDecline() {
		QuotationRequestValidationService.ValidatedQuotationRequest base = request("正定工程", "工程A");
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			base.schemaVersion(),
			"BLANK",
			base.schemeConfidence(),
			base.headerPatch(),
			List.of(),
			List.of(new QuotationRequestValidationService.CustomItem(
				"blank-1",
				"DYNAMIC",
				extracted("搭設工程"),
				extracted("一式"),
				extracted("式"),
				extractedDecimal("50000"),
				extractedDecimal("1"),
				extracted("實做實算")
			)),
			List.of(),
			List.of(),
			null,
			true,
			List.of(),
			List.of(),
			"SHOW_PREVIEW",
			List.of()
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest customItemRequest(boolean completion) {
		QuotationRequestValidationService.ValidatedQuotationRequest base = request("正定工程", "工程A");
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			base.schemaVersion(),
			"SALES",
			base.schemeConfidence(),
			base.headerPatch(),
			List.of(),
			List.of(new QuotationRequestValidationService.CustomItem(
				"special-fastener",
				"DYNAMIC",
				completion ? null : extracted("特製扣件"),
				completion ? extracted("M8") : null,
				completion ? extracted("pcs") : null,
				completion ? extractedDecimal("50") : null,
				completion ? null : extractedDecimal("2"),
				completion ? extracted("鍍鋅") : null
			)),
			List.of(),
			List.of(),
			null,
			false,
			List.of(),
			List.of(),
			"REQUEST_ITEM_FIELDS",
			List.of()
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest request(
		String companyName,
		String workName
	) {
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			"2.0",
			"GENERAL",
			BigDecimal.ONE,
			new QuotationRequestValidationService.HeaderPatch(
				extracted(companyName),
				extracted(workName),
				null,
				null,
				null,
				null,
				null,
				extracted("陳業務"),
				null
			),
			List.of(new QuotationRequestValidationService.ResolvedItem(
				"EXTERNAL_SCAFFOLD", "外部鷹架", "(一般料)", new BigDecimal("2"), "m2",
				new BigDecimal("180"), null, "實做實算", 1, "DIRECT", true,
				"外部鷹架 2m2", BigDecimal.ONE
			)),
			List.of(),
			List.of(),
			List.of(),
			null,
			false,
			companyName == null
				? List.of(new QuotationRequestValidationService.MissingBaseField(
					"companyName", "未提供", null, null
				))
				: List.of(),
			List.of(),
			companyName == null ? "REQUEST_BASE_FIELDS" : "SHOW_PREVIEW",
			List.of()
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest headerOnlyRequest(
		String companyName,
		String workName
	) {
		QuotationRequestValidationService.ValidatedQuotationRequest base = request(companyName, workName);
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			base.schemaVersion(),
			null,
			base.schemeConfidence(),
			base.headerPatch(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			null,
			false,
			base.missingBaseFields(),
			List.of(),
			base.nextAction(),
			List.of()
		);
	}

	private QuotationRequestValidationService.ValidatedQuotationRequest removalRequest(String itemCode) {
		QuotationRequestValidationService.ValidatedQuotationRequest base = headerOnlyRequest(null, null);
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			base.schemaVersion(),
			null,
			base.schemeConfidence(),
			base.headerPatch(),
			List.of(),
			List.of(),
			List.of(itemCode),
			List.of(),
			null,
			false,
			base.missingBaseFields(),
			List.of(),
			base.nextAction(),
			List.of()
		);
	}

	private QuotationRequestValidationService.ExtractedString extracted(String value) {
		return value == null
			? null
			: new QuotationRequestValidationService.ExtractedString(value, value, BigDecimal.ONE);
	}

	private QuotationRequestValidationService.ExtractedDecimal extractedDecimal(String value) {
		return new QuotationRequestValidationService.ExtractedDecimal(
			new BigDecimal(value),
			value,
			BigDecimal.ONE
		);
	}

	private QuotationCalculationResult calculation() {
		return new QuotationCalculationResult(
			"GENERAL",
			List.of(),
			List.of(),
			new BigDecimal("360"),
			new BigDecimal("18"),
			new BigDecimal("378"),
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}
}
