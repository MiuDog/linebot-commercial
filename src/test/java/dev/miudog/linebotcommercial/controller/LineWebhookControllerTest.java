package dev.miudog.linebotcommercial.controller;

import dev.miudog.linebotcommercial.service.CommandService;
import dev.miudog.linebotcommercial.service.ImageArchiveService;
import dev.miudog.linebotcommercial.service.LineStorageService;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineMessage;
import dev.miudog.linebotcommercial.service.quotation.QuotationLineWorkflowService;
import dev.miudog.linebotcommercial.service.quotation.QuotationReplyOutboxService;
import dev.miudog.linebotcommercial.service.quotation.QuotationAiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LineWebhookControllerTest {

	private static final String CHANNEL_SECRET = "test-channel-secret";

	@Mock
	CommandService commandService;

	@Mock
	ImageArchiveService archiveService;

	@Mock
	LineStorageService lineService;

	@Mock
	QuotationLineWorkflowService quotationWorkflow;

	@Mock
	QuotationReplyOutboxService replyOutbox;

	LineWebhookController controller;

	// 方法：免費進度查詢只使用本事件reply token，不啟動AI、草稿或主動推播。
	@Test
	void progressQueryDoesNotEnterQuotationOrCommandWorkflow() throws Exception {
		String payload = """
			{"events":[{"type":"message","replyToken":"progress-token",
			"source":{"type":"user","userId":"owner-a"},
			"message":{"id":"progress-1","type":"text","text":"#報價進度"}}]}
			""";
		controller.handleWebhook(signature(payload), payload);
		verify(lineService).replyText(eq("progress-token"), org.mockito.ArgumentMatchers.contains("目前沒有近期處理紀錄"));
		verifyNoInteractions(quotationWorkflow, commandService);
		verify(lineService, never()).showLoading(org.mockito.ArgumentMatchers.anyString());
	}

	@BeforeEach
	void setUp() {
		controller = new LineWebhookController(
			commandService,
			archiveService,
			lineService,
			quotationWorkflow,
			null
		);
		ReflectionTestUtils.setField(controller, "channelSecret", CHANNEL_SECRET);
	}

	// 方法：語音任務屬於文書機器人，本產品收到群組語音必須安靜忽略。
	@Test
	void ignoresGroupAudioBecauseVoiceBelongsToTheDocumentBot() throws Exception {
		String payload = """
			{"events":[{
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"group","groupId":"C1","userId":"U1"},
			  "message":{"id":"A1","type":"audio","duration":3500}
			}]}
			""";

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verifyNoInteractions(quotationWorkflow);
	}

	@Test
	void importsCsvFilesOnlyFromSignedPrivateMessages() throws Exception {
		String csv = "schemeCode,companyName\nSALES,測試公司";
		String payload = """
			{"events":[{"type":"message","webhookEventId":"csv-event","replyToken":"reply-token",
			"source":{"type":"user","userId":"U1"},
			"message":{"id":"F1","type":"file","fileName":"quote.csv","fileSize":100}}]}
			""";
		when(lineService.downloadContent("F1")).thenReturn(new LineStorageService.LineContent(
			new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "text/csv"
		));
		controller.handleWebhook(signature(payload), payload);
		verify(quotationWorkflow).handleText("csv-event", "F1", "U1", "#報價 CSV\n" + csv, null);

		String groupPayload = payload.replace("\"type\":\"user\"", "\"type\":\"group\",\"groupId\":\"G1\"");
		controller.handleWebhook(signature(groupPayload), groupPayload);
		verify(lineService, times(1)).downloadContent("F1");
	}

	@Test
	void rejectsOversizedAndInvalidUtf8FilesBeforeDraftMutation() throws Exception {
		String payload = """
			{"events":[{"type":"message","replyToken":"reply-token",
			"source":{"type":"user","userId":"U1"},
			"message":{"id":"F2","type":"file","fileName":"quote.csv","fileSize":100}}]}
			""";
		String oversized = payload.replace("\"fileSize\":100", "\"fileSize\":1048577");
		controller.handleWebhook(signature(oversized), oversized);
		verify(lineService, never()).downloadContent("F2");
		for (byte[] content : List.of(new byte[1048577], new byte[] {(byte) 0xC3, 0x28})) {
			when(lineService.downloadContent("F2")).thenReturn(new LineStorageService.LineContent(
				new ByteArrayInputStream(content), "text/csv"
			));
			controller.handleWebhook(signature(payload), payload);
		}
		verifyNoInteractions(quotationWorkflow);
	}

	@Test
	void rejectsEveryWebhookWhenChannelSecretIsBlank() {
		ReflectionTestUtils.setField(controller, "channelSecret", "");

		var response = controller.handleWebhook("attacker-signature", "{\"events\":[]}");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(commandService, never()).handleText(any(), any(), any(), any(), any());
		verifyNoInteractions(archiveService, lineService, quotationWorkflow);
	}

	@Test
	void ignoresQuotationInstructionsFromGroupsWithoutCreatingDrafts() throws Exception {
		String payload = """
			{"events":[{
			  "webhookEventId":"EV-GROUP",
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"group","groupId":"C1","userId":"U1"},
			  "message":{"id":"M1","type":"text","text":"#報價 一般架"}
			}]}
			""";

		controller.handleWebhook(signature(payload), payload);

		verify(quotationWorkflow, never()).handleText(any(), any(), any(), any(), any());
		verify(commandService, never()).handleText(any(), any(), any(), any(), any());
		verify(lineService).replyText("reply-token", "報價建立僅支援一對一私訊，請私訊機器人後重新輸入。");
	}

	@Test
	void routesDirectQuotationTextAndKeepsDuplicateEventIdIdempotent() throws Exception {
		String payload = """
			{"events":[{
			  "webhookEventId":"EV-USER",
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"user","userId":"U1"},
			  "message":{"id":"M1","type":"text","text":"#報價 一般架"}
			}]}
			""";
		when(quotationWorkflow.isQuotationText("U1", "#報價 一般架")).thenReturn(true);
		when(quotationWorkflow.handleText("EV-USER", "M1", "U1", "#報價 一般架", null))
			.thenReturn(java.util.List.of(new QuotationLineMessage(java.util.Map.of("type", "text", "text", "請補資料"))))
			.thenReturn(java.util.List.of());

		controller.handleWebhook(signature(payload), payload);
		controller.handleWebhook(signature(payload), payload);

		verify(quotationWorkflow, times(2)).handleText("EV-USER", "M1", "U1", "#報價 一般架", null);
		verify(lineService, times(1)).reply(eq("reply-token"), any());
	}

	@Test
	void reportsMasterDataValidationWithoutExposingInternalItemIdentifiers() throws Exception {
		String payload = directTextPayload("EV-VALIDATION", "#報價 CNS 外部鷹架 2");
		when(quotationWorkflow.isQuotationText("U1", "#報價 CNS 外部鷹架 2")).thenReturn(true);
		when(quotationWorkflow.handleText(
			"EV-VALIDATION",
			"M1",
			"U1",
			"#報價 CNS 外部鷹架 2",
			null
		)).thenThrow(new QuotationAiException(
			"AI_MASTER_DATA_VALIDATION_FAILED",
			"標準品項 UNKNOWN 不屬於所選格式 CNS"
		));

		controller.handleWebhook(signature(payload), payload);

		verify(lineService).replyText(
			"reply-token",
			"AI 辨識結果無法套用品項主檔。\n"
				+ "請改用品項主檔中的中文名稱，或補充報價格式與數量。\n"
				+ "錯誤代碼：AI_MASTER_DATA_VALIDATION_FAILED"
		);
	}

	@Test
	void distinguishesAiTimeoutFromDatabaseAndUnknownFailures() throws Exception {
		String timeoutPayload = directTextPayload("EV-TIMEOUT", "#報價 一般架");
		String databasePayload = directTextPayload("EV-DATABASE", "#報價 CNS");
		String unknownPayload = directTextPayload("EV-UNKNOWN", "#報價 空白");
		when(quotationWorkflow.isQuotationText("U1", "#報價 一般架")).thenReturn(true);
		when(quotationWorkflow.isQuotationText("U1", "#報價 CNS")).thenReturn(true);
		when(quotationWorkflow.isQuotationText("U1", "#報價 空白")).thenReturn(true);
		when(quotationWorkflow.handleText("EV-TIMEOUT", "M1", "U1", "#報價 一般架", null))
			.thenThrow(new QuotationAiException("AI_TIMEOUT", "AI 解析逾時"));
		when(quotationWorkflow.handleText("EV-DATABASE", "M1", "U1", "#報價 CNS", null))
			.thenThrow(new org.springframework.dao.CannotAcquireLockException("database busy"));
		when(quotationWorkflow.handleText("EV-UNKNOWN", "M1", "U1", "#報價 空白", null))
			.thenThrow(new IllegalStateException("sensitive internal detail"));

		controller.handleWebhook(signature(timeoutPayload), timeoutPayload);
		controller.handleWebhook(signature(databasePayload), databasePayload);
		controller.handleWebhook(signature(unknownPayload), unknownPayload);

		verify(lineService).replyText(
			"reply-token",
			"AI 解析超過等待時間，請稍後重送同一段報價指令。\n錯誤代碼：AI_TIMEOUT"
		);
		verify(lineService).replyText(
			"reply-token",
			"報價資料庫目前忙碌，資料尚未遺失，請稍後重試。\n錯誤代碼：QUOTATION_DATABASE_BUSY"
		);
		verify(lineService).replyText(
			"reply-token",
			"報價處理發生未預期錯誤，請將錯誤代碼提供給管理員。\n錯誤代碼：QUOTATION_INTERNAL_ERROR"
		);
	}

	@Test
	void retriesOnlyThePendingReplyWhenDuplicateMutationIsAlreadyIdempotent() throws Exception {
		String payload = """
			{"events":[{
			  "webhookEventId":"EV-OUTBOX",
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"user","userId":"U1"},
			  "message":{"id":"M1","type":"text","text":"#報價 一般架"}
			}]}
			""";
		LineWebhookController reliableController = new LineWebhookController(
			commandService,
			archiveService,
			lineService,
			quotationWorkflow,
			replyOutbox
		);
		ReflectionTestUtils.setField(reliableController, "channelSecret", CHANNEL_SECRET);
		List<QuotationLineMessage> firstReply = java.util.List.of(
			new QuotationLineMessage(java.util.Map.of("type", "text", "text", "請補資料"))
		);
		when(quotationWorkflow.isQuotationText("U1", "#報價 一般架")).thenReturn(true);
		when(quotationWorkflow.handleText("EV-OUTBOX", "M1", "U1", "#報價 一般架", null))
			.thenReturn(firstReply)
			.thenReturn(java.util.List.of());

		reliableController.handleWebhook(signature(payload), payload);
		reliableController.handleWebhook(signature(payload), payload);

		verify(replyOutbox).deliver(
			eq("EV-OUTBOX"),
			eq("U1"),
			eq("reply-token"),
			any()
		);
		verify(replyOutbox).retryPending("EV-OUTBOX");
		verifyNoInteractions(commandService);
	}

	@Test
	void routesOnlyDirectUserPostbacksToQuotationWorkflow() throws Exception {
		String payload = """
			{"events":[{
			  "webhookEventId":"EV-POSTBACK",
			  "type":"postback",
			  "replyToken":"reply-token",
			  "source":{"type":"user","userId":"U1"},
			  "postback":{"data":"signed-data"}
			}]}
			""";
		when(quotationWorkflow.handlePostback("EV-POSTBACK", "U1", "signed-data"))
			.thenReturn(java.util.List.of(new QuotationLineMessage(java.util.Map.of("type", "text", "text", "完成"))));

		controller.handleWebhook(signature(payload), payload);

		verify(quotationWorkflow).handlePostback("EV-POSTBACK", "U1", "signed-data");
		verify(lineService).reply(eq("reply-token"), any());
	}

	@Test
	void forwardsLineImageSetMetadataToTheArchiveService() throws Exception {
		String payload = """
                {"events":[{
                  "type":"message",
                  "replyToken":"reply-token",
                  "source":{"type":"group","groupId":"C1","userId":"U1"},
                  "message":{
                    "id":"M2",
                    "type":"image",
                    "imageSet":{"id":"SET1","index":2,"total":3}
                  }
                }]}
                """;
		when(lineService.downloadContent("M2"))
			.thenReturn(new LineStorageService
			.LineContent(new ByteArrayInputStream("image".getBytes(StandardCharsets.UTF_8)), "image/jpeg"));

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(archiveService)
			.stage(
			eq("M2"),
			eq("SET1"),
			eq(2),
			eq(3),
			eq("group"),
			eq("C1"),
			eq("U1"),
			any(InputStream.class),
			eq("image/jpeg")
		);
	}

	@Test
	void waitsForTheWholeDirectImageSetBeforeSendingEveryCandidateToQuotationAi() throws Exception {
		String firstPayload = directImagePayload("EV-IMG-1", "M1", 1, 2);
		String secondPayload = directImagePayload("EV-IMG-2", "M2", 2, 2);
		when(lineService.downloadContent("M1"))
			.thenReturn(new LineStorageService.LineContent(
				new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)),
				"image/jpeg"
			));
		when(lineService.downloadContent("M2"))
			.thenReturn(new LineStorageService.LineContent(
				new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)),
				"image/jpeg"
			));
		when(quotationWorkflow.isQuotationText("U1", "")).thenReturn(true);
		when(archiveService.completedSetMessageIds("U1", "SET1", "M1", 2)).thenReturn(List.of());
		when(archiveService.completedSetMessageIds("U1", "SET1", "M2", 2)).thenReturn(List.of("M1", "M2"));
		when(quotationWorkflow.handleImages("EV-IMG-2", List.of("M1", "M2"), "U1"))
			.thenReturn(List.of(new QuotationLineMessage(java.util.Map.of("type", "text", "text", "已選圖"))));

		controller.handleWebhook(signature(firstPayload), firstPayload);
		controller.handleWebhook(signature(secondPayload), secondPayload);

		verify(quotationWorkflow, never()).handleImages("EV-IMG-1", List.of(), "U1");
		verify(quotationWorkflow).handleImages("EV-IMG-2", List.of("M1", "M2"), "U1");
		verify(lineService).reply(eq("reply-token"), any());
	}

	@Test
	void recordsTheImagePositionWhenLineContentDownloadFails() throws Exception {
		String payload = """
				{"events":[{
				  "type":"message",
				  "replyToken":"reply-token",
				  "source":{"type":"group","groupId":"C1","userId":"U1"},
				  "message":{
				    "id":"M1",
				    "type":"image",
				    "imageSet":{"id":"SET1","index":1,"total":3}
				  }
				}]}
				""";
		when(lineService.downloadContent("M1")).thenReturn(null);

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(archiveService).recordFetchFailure("M1", "SET1", 1, 3, "C1");
	}

	@Test
	void repliesPingLatencyWhenTheBotIsMentioned() throws Exception {
		long timestamp = System.currentTimeMillis() - 120L;
		String payload = """
			{"events":[{
			  "type":"message",
			  "timestamp":%d,
			  "replyToken":"reply-token",
			  "source":{"type":"group","groupId":"C1","userId":"U1"},
			  "message":{
			    "id":"M1",
			    "type":"text",
			    "text":"@資產管理 ping",
			    "mention":{"mentionees":[{"index":0,"length":5,"type":"user","userId":"UBOT","isSelf":true}]}
			  }
			}]}
			""".formatted(timestamp);
		when(commandService.handleMentionPing("ping", timestamp, "reply-token")).thenReturn(true);

		var response = controller.handleWebhook(signature(payload), payload);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(commandService).handleMentionPing(eq("ping"), eq(timestamp), eq("reply-token"));
		verify(commandService, never()).handleText(any(), any(), any(), any(), any());
	}

	@Test
	void keepsRoutingMentionedTextThatIsNotPingToTheCommandService() throws Exception {
		String payload = """
			{"events":[{
			  "type":"message",
			  "timestamp":1700000000000,
			  "replyToken":"reply-token",
			  "source":{"type":"group","groupId":"C1","userId":"U1"},
			  "message":{
			    "id":"M1",
			    "type":"text",
			    "text":"@資產管理 #標籤",
			    "mention":{"mentionees":[{"index":0,"length":5,"type":"user","userId":"UBOT","isSelf":true}]}
			  }
			}]}
			""";

		controller.handleWebhook(signature(payload), payload);

		verify(commandService).handleText("@資產管理 #標籤", null, "C1", "U1", "reply-token");
	}

	private String directImagePayload(String eventId, String messageId, int index, int total) {
		return """
			{"events":[{
			  "webhookEventId":"%s",
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"user","userId":"U1"},
			  "message":{"id":"%s","type":"image","imageSet":{"id":"SET1","index":%d,"total":%d}}
			}]}
			""".formatted(eventId, messageId, index, total);
	}

	private String directTextPayload(String eventId, String text) {
		return """
			{"events":[{
			  "webhookEventId":"%s",
			  "type":"message",
			  "replyToken":"reply-token",
			  "source":{"type":"user","userId":"U1"},
			  "message":{"id":"M1","type":"text","text":"%s"}
			}]}
			""".formatted(eventId, text);
	}

	// 方法：建立符合 LINE webhook 規格的 HMAC-SHA256 簽章。
	private static String signature(String payload) throws Exception {
		// 外部 API：使用 Java 密碼 API 建立測試用 HMAC。
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
	}
}
