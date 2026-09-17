package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import dev.miudog.linebotcommercial.service.ai.AiExtractionException;
import dev.miudog.linebotcommercial.service.ai.AiImageInput;
import dev.miudog.linebotcommercial.service.ai.AiJsonCompletionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

class QuotationAiParsingServiceTest {

	AiJsonCompletionClient client;
	QuotationAiPromptService promptService;
	QuotationAiParsingService service;

	@BeforeEach
	void setUp() {
		QuotationAdminRepository repository = mock(QuotationAdminRepository.class);
		when(repository.findSchemes()).thenReturn(
			List.of(new QuotationAdminRepository.SchemeSummary("CNS", "CNS", "DETAIL", true, true))
		);
		when(repository.findActiveSchemeItems("CNS")).thenReturn(
			List.of(
				new QuotationAdminRepository.SchemeItem(
					1,
					1,
					1,
					"EXTERNAL_SCAFFOLD",
					"外部鷹架",
					"(CNS)",
					"m2",
					new BigDecimal("220"),
					"(實做實算)",
					1,
					"DIRECT",
					true,
					true,
					true,
					true
				)
			)
		);

		client = mock(AiJsonCompletionClient.class);
		when(client.isConfigured()).thenReturn(true);
		promptService = mock(QuotationAiPromptService.class);
		when(promptService.build("外部鷹架 2 平方米", List.of("image-1"), "CNS"))
			.thenReturn(new QuotationAiPromptService.Prompt("system", "user", List.of("image-1")));
		service = new QuotationAiParsingService(
			client,
			promptService,
			new QuotationRequestValidationService(repository),
			new ObjectMapper()
		);
	}

	@Test
	void treatsNullStrictHeaderFieldsAsUnchanged() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
			.thenReturn(validModelJson().replace("\"headerPatch\":{", "\"headerPatch\":{\"phone\":null,"));
		assertThat(service.parse("外部鷹架 2 平方米", images, "CNS").request().headerPatch().phone()).isNull();
	}

	@Test
	void repairsMalformedOutputOnceAndRetainsBusinessValidation() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
			.thenReturn("{broken", validModelJson());
		assertThat(service.parse("外部鷹架 2 平方米", images, "CNS").request().items()).hasSize(1);
		org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2))
			.completeJson(anyString(), anyString(), eq(images), isNull());
	}

	@Test
	void neverRetriesRefusalsAndStopsAfterFiveInvalidResponses() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
			.thenThrow(new dev.miudog.linebotcommercial.service.ai.AiCompletionException("AI_REFUSED"));
		assertAiErrorCode(images, "AI_REFUSED");
		org.mockito.Mockito.verify(client).completeJson(anyString(), anyString(), eq(images), isNull());
		org.mockito.Mockito.reset(client);
		when(client.isConfigured()).thenReturn(true);
		when(client.completeJson(anyString(), anyString(), eq(images), isNull())).thenReturn("{broken");
		assertAiErrorCode(images, "AI_RESPONSE_INVALID");
		org.mockito.Mockito.verify(client, org.mockito.Mockito.times(5))
			.completeJson(anyString(), anyString(), eq(images), isNull());
	}

	@Test
	void parsesModelJsonAndResolvesTrustedDatabaseFields() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull())).thenReturn(validModelJson());

		QuotationAiParsingService.ParseResult result = service.parse("外部鷹架 2 平方米", images, "CNS");

		assertThat(result.request().schemeCode()).isEqualTo("CNS");
		assertThat(result.request().items()).singleElement().satisfies(item -> {
			assertThat(item.itemName()).isEqualTo("外部鷹架");
			assertThat(item.unitPrice()).isEqualByComparingTo("220");
			assertThat(item.lineAmount()).isNull();
		});
		assertThat(result.rawJson()).isEqualTo(validModelJson());
	}

	@Test
	void rejectsProseAroundTheJsonObject() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull())).thenReturn("以下是結果：\n" + validModelJson());

		assertThatThrownBy(() -> service.parse("外部鷹架 2 平方米", images, "CNS"))
			.isInstanceOf(QuotationAiException.class)
			.hasMessageContaining("只能包含一個 JSON 物件");
	}

	@Test
	void rejectsMultipleConcatenatedJsonObjects() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull())).thenReturn(validModelJson() + " {}");

		assertThatThrownBy(() -> service.parse("外部鷹架 2 平方米", images, "CNS"))
			.isInstanceOf(QuotationAiException.class)
			.hasMessageContaining("JSON 格式無法解析");
	}

	@Test
	void rejectsMissingOrInventedImageAssessments() {
		List<AiImageInput> images = images();
		String missingAssessment = validModelJson()
			.replace("\"selectedImageMessageId\": \"image-1\"", "\"selectedImageMessageId\": null")
			.replace(
				"[ {\"messageId\": \"image-1\", \"qualityScore\": 1, \"viewpointScore\": 1, \"distinctivenessScore\": 1, \"reason\": \"最有區別\"} ]",
				"[]"
			);
		when(client.completeJson(anyString(), anyString(), eq(images), isNull())).thenReturn(missingAssessment);

		assertThatThrownBy(() -> service.parse("外部鷹架 2 平方米", images, "CNS"))
			.isInstanceOf(QuotationAiException.class)
			.hasMessageContaining("候選圖片評估");
	}

	@Test
	void classifiesTimeoutAuthenticationRateLimitAndMasterDataFailures() {
		List<AiImageInput> images = images();
		for (String status : List.of("401", "403", "429", "503")) {
			org.mockito.Mockito.reset(client);
			when(client.isConfigured()).thenReturn(true);
			when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
				.thenThrow(new AiExtractionException("模型回應狀態碼 " + status, (Throwable) null));
			boolean auth = status.equals("401") || status.equals("403");
			assertAiErrorCode(images, auth ? "AI_AUTH_FAILED" : status.equals("429") ? "AI_RATE_LIMITED" : "AI_SERVICE_UNAVAILABLE");
			org.mockito.Mockito.verify(client, org.mockito.Mockito.times(auth ? 1 : 5)).completeJson(anyString(), anyString(), eq(images), isNull());
		}
		org.mockito.Mockito.reset(client);
		when(client.isConfigured()).thenReturn(true);
		when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
			.thenReturn(validModelJson().replace("EXTERNAL_SCAFFOLD", "UNKNOWN_ITEM"));
		assertAiErrorCode(images, "AI_MASTER_DATA_VALIDATION_FAILED");
	}

	// 方法：傳輸失敗與格式修復共用五次上限，第五次成功可正常套用。
	@Test
	void recoversOnFifthAttemptAcrossTransportAndFormatFailures() {
		List<AiImageInput> images = images();
		when(client.completeJson(anyString(), anyString(), eq(images), isNull()))
			.thenThrow(new AiExtractionException("連線逾時", new HttpTimeoutException("timeout")))
			.thenReturn("{broken", "", "{broken", validModelJson());
		assertThat(service.parse("外部鷹架 2 平方米", images, "CNS").request().items()).hasSize(1);
		org.mockito.Mockito.verify(client, org.mockito.Mockito.times(5)).completeJson(anyString(), anyString(), eq(images), isNull());
	}

	private List<AiImageInput> images() {
		return List.of(
			new AiImageInput("image-1", "image".getBytes(StandardCharsets.UTF_8), "image/jpeg")
		);
	}

	private void assertAiErrorCode(List<AiImageInput> images, String expectedCode) {
		assertThatThrownBy(() -> service.parse("外部鷹架 2 平方米", images, "CNS"))
			.isInstanceOfSatisfying(
				QuotationAiException.class,
				exception -> assertThat(exception.code()).isEqualTo(expectedCode)
			);
	}

	private String validModelJson() {
		return """
			{"schemaVersion":"2.0","schemeCode":"CNS","schemeConfidence":1,
			"headerPatch":{"companyName":{"value":"範例工程","sourceText":"範例工程","confidence":1},"workName":{"value":"測試報價","sourceText":"測試報價","confidence":1}},
			"standardItemIntents":[{"itemCode":"EXTERNAL_SCAFFOLD","quantity":2,"sourceText":"外部鷹架 2 平方米","confidence":1}],
			"customItems":[],"removedItemCodes":[],
			"selectedImageMessageId": "image-1",
			"imageAssessments": [ {"messageId": "image-1", "qualityScore": 1, "viewpointScore": 1, "distinctivenessScore": 1, "reason": "最有區別"} ],
			"imageDeclined":false,"missingBaseFields":[],"missingItemFields":[],"nextAction":"SHOW_PREVIEW","warnings":[]}
			""".trim();
	}
}
