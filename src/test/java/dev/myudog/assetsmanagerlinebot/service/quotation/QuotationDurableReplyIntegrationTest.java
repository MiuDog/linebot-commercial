package dev.myudog.assetsmanagerlinebot.service.quotation;

import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.repository.QuotationEventReceiptRepository;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotationDurableReplyIntegrationTest {

	private static final String POSTBACK_SECRET = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path temporaryDirectory;

	// 方法：驗證文字 mutation 完成後、outbox 入列前當機，租約重領不重跑 AI 並可補送一次。
	@Test
	void reclaimsTextAfterCrashBeforeStageWithoutParsingAgain() throws Exception {
		TestContext context = context("text-crash.sqlite");
		QuotationReplyOutboxService crashingOutbox = mock(QuotationReplyOutboxService.class);
		doThrow(new SimulatedCrash()).when(crashingOutbox).stage(anyString(), anyString(), anyList());
		QuotationLineWorkflowService crashingWorkflow = context.workflow(crashingOutbox);

		assertThatThrownBy(() -> crashingWorkflow.handleText(
			"EV-TEXT-CRASH",
			"MSG-TEXT-CRASH",
			"U-TEXT",
			"#報價 空白"
		)).isInstanceOf(SimulatedCrash.class);

		assertThat(context.jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_draft_message WHERE message_id = 'MSG-TEXT-CRASH'",
			Integer.class
		)).isEqualTo(1);
		assertThat(context.jdbc.queryForObject(
			"SELECT result_status FROM quotation_event_receipt WHERE event_id = 'EV-TEXT-CRASH'",
			String.class
		)).isEqualTo("RECEIVED");
		assertThat(context.jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_reply_outbox WHERE event_id = 'EV-TEXT-CRASH'",
			Integer.class
		)).isZero();
		ArgumentCaptor<List> originalReply = ArgumentCaptor.forClass(List.class);
		verify(crashingOutbox).stage(
			org.mockito.ArgumentMatchers.eq("EV-TEXT-CRASH"),
			org.mockito.ArgumentMatchers.eq("U-TEXT"),
			originalReply.capture()
		);

		// 資料庫：模擬程序重啟後租約已到期，使相同 webhook 可安全重領。
		context.jdbc.update("""
			UPDATE quotation_event_receipt
			SET lease_until = datetime(CURRENT_TIMESTAMP, '-1 second')
			WHERE event_id = 'EV-TEXT-CRASH'
			""");
		QuotationReplyOutboxService durableOutbox = context.outbox();
		QuotationLineWorkflowService recoveredWorkflow = context.workflow(durableOutbox);

		recoveredWorkflow.handleText(
			"EV-TEXT-CRASH",
			"MSG-TEXT-CRASH",
			"U-TEXT",
			"#報價 空白"
		);
		durableOutbox.retryPending("EV-TEXT-CRASH");
		durableOutbox.retryPending("EV-TEXT-CRASH");

		verify(context.parser, times(1)).parse(anyString(), anyList(), anyString());
		verify(context.line, times(1)).push(
			org.mockito.ArgumentMatchers.eq("U-TEXT"),
			anyList(),
			any(java.util.UUID.class)
		);
		assertThat(context.jdbc.queryForObject(
			"SELECT result_status FROM quotation_event_receipt WHERE event_id = 'EV-TEXT-CRASH'",
			String.class
		)).isEqualTo("PROCESSED");
		assertThat(context.jdbc.queryForObject(
			"SELECT status FROM quotation_reply_outbox WHERE event_id = 'EV-TEXT-CRASH'",
			String.class
		)).isEqualTo("SENT");
		assertThat(context.jdbc.queryForObject(
			"SELECT messages_json FROM quotation_reply_outbox WHERE event_id = 'EV-TEXT-CRASH'",
			String.class
		)).isEqualTo(new ObjectMapper().writeValueAsString(originalReply.getValue()));
	}

	// 方法：驗證 postback mutation 與 outbox 同一短交易，入列前當機會完整回滾並只補送一次。
	@Test
	void rollsBackPostbackMutationWhenCrashHappensBeforeStage() {
		TestContext context = context("postback-crash.sqlite");
		QuotationReplyOutboxService durableOutbox = context.outbox();
		QuotationLineWorkflowService seedWorkflow = context.workflow(durableOutbox);
		seedWorkflow.handleText("EV-SEED", "MSG-SEED", "U-POSTBACK", "#報價 空白");
		long draftId = context.jdbc.queryForObject(
			"SELECT id FROM quotation_draft WHERE source_id = 'U-POSTBACK'",
			Long.class
		);
		int revision = context.jdbc.queryForObject(
			"SELECT revision FROM quotation_draft WHERE id = ?",
			Integer.class,
			draftId
		);
		String postback = context.signer().sign(
			draftId,
			revision,
			QuotationPostbackAction.CANCEL,
			Instant.now().plusSeconds(300),
			"U-POSTBACK"
		);
		QuotationReplyOutboxService crashingOutbox = mock(QuotationReplyOutboxService.class);
		doThrow(new SimulatedCrash()).when(crashingOutbox).stage(anyString(), anyString(), anyList());

		assertThatThrownBy(() -> context.workflow(crashingOutbox).handlePostback(
			"EV-CANCEL-CRASH",
			"U-POSTBACK",
			postback
		)).isInstanceOf(SimulatedCrash.class);

		assertThat(context.jdbc.queryForObject(
			"SELECT status FROM quotation_draft WHERE id = ?",
			String.class,
			draftId
		)).isNotEqualTo("CANCELLED");
		assertThat(context.jdbc.queryForObject(
			"SELECT COUNT(*) FROM quotation_event_receipt WHERE event_id = 'EV-CANCEL-CRASH'",
			Integer.class
		)).isZero();

		QuotationLineWorkflowService recoveredWorkflow = context.workflow(durableOutbox);
		recoveredWorkflow.handlePostback("EV-CANCEL-CRASH", "U-POSTBACK", postback);
		durableOutbox.retryPending("EV-CANCEL-CRASH");
		assertThat(recoveredWorkflow.handlePostback(
			"EV-CANCEL-CRASH",
			"U-POSTBACK",
			postback
		)).isEmpty();
		durableOutbox.retryPending("EV-CANCEL-CRASH");

		assertThat(context.jdbc.queryForObject(
			"SELECT status FROM quotation_draft WHERE id = ?",
			String.class,
			draftId
		)).isEqualTo("CANCELLED");
		verify(context.line, times(1)).push(
			org.mockito.ArgumentMatchers.eq("U-POSTBACK"),
			anyList(),
			any(java.util.UUID.class)
		);
	}

	// 方法：驗證慢速 AI 推論期間不持有 SQLite 交易連線，其他資料庫讀取仍可立即完成。
	@Test
	void keepsDatabaseReadableWhileAiParsingIsSlow() throws Exception {
		TestContext context = context("slow-ai.sqlite", false);
		CountDownLatch parsingStarted = new CountDownLatch(1);
		CountDownLatch allowParsing = new CountDownLatch(1);
		when(context.parser.parse(anyString(), anyList(), anyString())).thenAnswer(invocation -> {
			parsingStarted.countDown();
			if (!allowParsing.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");

			return new QuotationAiParsingService.ParseResult(validatedRequest(), "{}");

		});
		QuotationLineWorkflowService workflow = context.workflow(context.outbox());

		CompletableFuture<List<QuotationLineMessage>> processing = CompletableFuture.supplyAsync(
			() -> workflow.handleText("EV-SLOW", "MSG-SLOW", "U-SLOW", "#報價 空白")
		);
		assertThat(parsingStarted.await(2, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Integer> readable = CompletableFuture.supplyAsync(
			() -> context.jdbc.queryForObject("SELECT COUNT(*) FROM quotation_draft", Integer.class)
		);
		assertThat(readable.get(1, TimeUnit.SECONDS)).isEqualTo(1);
		allowParsing.countDown();
		assertThat(processing.get(5, TimeUnit.SECONDS)).isNotEmpty();
	}

	// 方法：建立採真 SQLite、真 receipt/outbox/port 的測試環境。
	private TestContext context(String fileName) {
		return context(fileName, true);
	}

	// 方法：建立測試環境並選擇是否立即配置 AI 回傳。
	private TestContext context(String fileName, boolean configureParser) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			"jdbc:sqlite:" + temporaryDirectory.resolve(fileName).toAbsolutePath()
		);
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
			return null;

		});
		QuotationAiParsingService parser = mock(QuotationAiParsingService.class);
		if (configureParser) {
			when(parser.parse(anyString(), anyList(), anyString())).thenReturn(
				new QuotationAiParsingService.ParseResult(validatedRequest(), "{}")
			);
		}
		QuotationCalculationService calculator = mock(QuotationCalculationService.class);
		when(calculator.calculate(any())).thenReturn(calculation());
		LineStorageService line = mock(LineStorageService.class);
		DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
		SqliteQuotationDraftWorkflowPort port = new SqliteQuotationDraftWorkflowPort(
			jdbc,
			parser,
			calculator,
			new PendingImageRepository(JdbcClient.create(dataSource)),
			new FileStorageService(temporaryDirectory.toString()),
			transactionManager
		);
		return new TestContext(jdbc, parser, line, transactionManager, port);
	}

	// 方法：建立具有完整必填欄位的 AI 解析結果。
	private QuotationRequestValidationService.ValidatedQuotationRequest validatedRequest() {
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			"2.0",
			"GENERAL",
			BigDecimal.ONE,
			new QuotationRequestValidationService.HeaderPatch(
				extracted("正定工程"),
				extracted("測試工程"),
				null,
				null,
				null,
				null,
				null,
				extracted("王先生"),
				null
			),
			List.of(),
			List.of(new QuotationRequestValidationService.CustomItem(
				"CLIENT-1",
				"DYNAMIC",
				extracted("施工架搭設"),
				extracted("一式"),
				extracted("式"),
				new QuotationRequestValidationService.ExtractedDecimal(
					new BigDecimal("50000"),
					"50000",
					BigDecimal.ONE
				),
				new QuotationRequestValidationService.ExtractedDecimal(
					BigDecimal.ONE,
					"1",
					BigDecimal.ONE
				),
				extracted("實做實算")
			)),
			List.of(),
			List.of(),
			null,
			false,
			List.of(),
			List.of(),
			"SHOW_PREVIEW",
			List.of()
		);
	}

	// 方法：建立高信心字串欄位。
	private QuotationRequestValidationService.ExtractedString extracted(String value) {
		return new QuotationRequestValidationService.ExtractedString(value, value, BigDecimal.ONE);
	}

	// 方法：建立不含明細的固定測試計算結果。
	private QuotationCalculationResult calculation() {
		return new QuotationCalculationResult(
			"GENERAL",
			List.of(),
			List.of(),
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	private record TestContext(
		JdbcTemplate jdbc,
		QuotationAiParsingService parser,
		LineStorageService line,
		DataSourceTransactionManager transactionManager,
		SqliteQuotationDraftWorkflowPort port
	) {

		// 方法：建立真 SQLite outbox 服務。
		private QuotationReplyOutboxService outbox() {
			return new QuotationReplyOutboxService(jdbc, line, new ObjectMapper());
		}

		// 方法：建立測試用簽章服務。
		private QuotationPostbackSigner signer() {
			return new QuotationPostbackSigner(POSTBACK_SECRET);
		}

		// 方法：建立使用短交易 durable completion 的正式 workflow。
		private QuotationLineWorkflowService workflow(QuotationReplyOutboxService outbox) {
			QuotationPostbackSigner signer = signer();
			return new QuotationLineWorkflowService(
				port,
				new QuotationEventReceiptRepository(jdbc),
				new QuotationConversationService(),
				signer,
				new QuotationLineMessageBuilder(signer),
				mock(QuotationConfirmationService.class),
				null,
				null,
				outbox,
				transactionManager
			);
		}
	}

	private static final class SimulatedCrash extends Error {
	}
}
