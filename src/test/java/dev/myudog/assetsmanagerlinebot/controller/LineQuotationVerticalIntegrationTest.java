package dev.myudog.assetsmanagerlinebot.controller;

import dev.myudog.assetsmanagerlinebot.repository.PendingImageRepository;
import dev.myudog.assetsmanagerlinebot.repository.QuotationEventReceiptRepository;
import dev.myudog.assetsmanagerlinebot.service.CommandService;
import dev.myudog.assetsmanagerlinebot.service.FileStorageService;
import dev.myudog.assetsmanagerlinebot.service.ImageArchiveService;
import dev.myudog.assetsmanagerlinebot.service.LineStorageService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationAiParsingService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationCalculationResult;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationCalculationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationConfirmationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationConversationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationLineMessageBuilder;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationLineWorkflowService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationPostbackSigner;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationRequestValidationService;
import dev.myudog.assetsmanagerlinebot.service.quotation.QuotationReplyOutboxService;
import dev.myudog.assetsmanagerlinebot.service.quotation.SqliteQuotationDraftWorkflowPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineQuotationVerticalIntegrationTest {

	private static final String CHANNEL_SECRET = "vertical-test-channel-secret";
	private static final String POSTBACK_SECRET = "0123456789abcdef0123456789abcdef";

	@TempDir
	Path temporaryDirectory;

	// 測試：LINE 私訊會穿過控制器、工作流程與真實 SQLite，保存草稿及 receipt 後回覆補圖詢問。
	@Test
	void persistsAQuotationDraftFromSignedLineWebhookThroughTheRealWorkflow() throws Exception {
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
			SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			QuotationAiParsingService parser = mock(QuotationAiParsingService.class);
			QuotationCalculationService calculator = mock(QuotationCalculationService.class);
			LineStorageService line = mock(LineStorageService.class);
			String instruction = "#報價 空白格式，正定公司，船塢工程，業務王先生";
			when(parser.parse(eq(instruction), anyList(), eq("BLANK"))).thenReturn(
				new QuotationAiParsingService.ParseResult(validatedRequest(), "{}")
			);
			when(calculator.calculate(org.mockito.ArgumentMatchers.any())).thenReturn(calculation());

			SqliteQuotationDraftWorkflowPort port = new SqliteQuotationDraftWorkflowPort(
				jdbc,
				parser,
				calculator,
				new PendingImageRepository(JdbcClient.create(dataSource)),
				new FileStorageService(temporaryDirectory.toString()),
				new DataSourceTransactionManager(dataSource)
			);
			QuotationPostbackSigner signer = new QuotationPostbackSigner(POSTBACK_SECRET);
			DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
			QuotationReplyOutboxService replyOutbox = new QuotationReplyOutboxService(
				jdbc,
				line,
				new ObjectMapper()
			);
			QuotationLineWorkflowService workflow = new QuotationLineWorkflowService(
				port,
				new QuotationEventReceiptRepository(jdbc),
				new QuotationConversationService(),
				signer,
				new QuotationLineMessageBuilder(signer),
				mock(QuotationConfirmationService.class),
				null,
				null,
				replyOutbox,
				transactionManager
			);
			LineWebhookController controller = new LineWebhookController(
				mock(CommandService.class),
				mock(ImageArchiveService.class),
				line,
				workflow,
				replyOutbox
			);
			ReflectionTestUtils.setField(controller, "channelSecret", CHANNEL_SECRET);
			String payload = payload(instruction);

			var response = controller.handleWebhook(signature(payload), payload);

			assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quotation_draft", Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForList(
				"SELECT field_key, field_value FROM quotation_draft_field ORDER BY field_key"
			)).contains(
				java.util.Map.of("field_key", "companyName", "field_value", "正定公司"),
				java.util.Map.of("field_key", "workName", "field_value", "船塢工程"),
				java.util.Map.of("field_key", "salesRepresentative", "field_value", "王先生")
			);
			assertThat(jdbc.queryForObject(
				"SELECT status FROM quotation_draft WHERE source_id = 'U-VERTICAL'",
				String.class
			)).isEqualTo("AWAITING_IMAGE");
			assertThat(jdbc.queryForObject(
				"SELECT result_status FROM quotation_event_receipt WHERE event_id = 'EV-VERTICAL'",
				String.class
			)).isEqualTo("PROCESSED");
			assertThat(jdbc.queryForObject(
				"SELECT status FROM quotation_reply_outbox WHERE event_id = 'EV-VERTICAL'",
				String.class
			)).isEqualTo("SENT");
			verify(line).reply(
				eq("REPLY-VERTICAL"),
				org.mockito.ArgumentMatchers.argThat(messages -> messages.toString().contains("不提供圖片"))
			);
		}
	}

	// 方法：建立完整且高信心的 AI 解析結果，讓流程直接進入船用／空白補圖階段。
	private QuotationRequestValidationService.ValidatedQuotationRequest validatedRequest() {
		QuotationRequestValidationService.ExtractedString company = extracted("正定公司");
		QuotationRequestValidationService.ExtractedString work = extracted("船塢工程");
		QuotationRequestValidationService.ExtractedString sales = extracted("王先生");
		return new QuotationRequestValidationService.ValidatedQuotationRequest(
			"2.0",
			"BLANK",
			BigDecimal.ONE,
			new QuotationRequestValidationService.HeaderPatch(
				company,
				work,
				null,
				null,
				null,
				null,
				null,
				sales,
				null
			),
			List.of(),
			List.of(new QuotationRequestValidationService.CustomItem(
				"CLIENT-1",
				"DYNAMIC",
				extracted("系統架搭設"),
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

	// 方法：建立可追溯至原始訊息的高信心文字欄位。
	private QuotationRequestValidationService.ExtractedString extracted(String value) {
		return new QuotationRequestValidationService.ExtractedString(value, value, BigDecimal.ONE);
	}

	// 方法：建立不含品項的空白格式計價結果。
	private QuotationCalculationResult calculation() {
		return new QuotationCalculationResult(
			"BLANK",
			List.of(),
			List.of(),
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			QuotationCalculationResult.CustomerPresentation.DETAIL
		);
	}

	// 方法：建立一對一 LINE 文字 webhook 測試資料。
	private String payload(String instruction) {
		return """
			{"events":[{
			  "webhookEventId":"EV-VERTICAL",
			  "type":"message",
			  "replyToken":"REPLY-VERTICAL",
			  "source":{"type":"user","userId":"U-VERTICAL"},
			  "message":{"id":"MSG-VERTICAL","type":"text","text":"%s"}
			}]}
			""".formatted(instruction);
	}

	// 方法：使用 LINE channel secret 對 webhook 原文產生有效簽章。
	private String signature(String payload) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
	}
}
