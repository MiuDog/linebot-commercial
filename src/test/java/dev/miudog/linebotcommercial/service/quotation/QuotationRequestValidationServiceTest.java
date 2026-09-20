package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationRequestValidationServiceTest {

	QuotationAdminRepository repository;
	QuotationRequestValidationService service;
	ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		repository = mock(QuotationAdminRepository.class);
		when(repository.findSchemes()).thenReturn(
			List.of(
				new QuotationAdminRepository.SchemeSummary("CNS", "CNS", "DETAIL", true, true),
				new QuotationAdminRepository.SchemeSummary("GENERAL", "一般架", "DETAIL", true, true),
				new QuotationAdminRepository.SchemeSummary("MARINE", "船用", "SUMMARY_ONLY", true, true),
				new QuotationAdminRepository.SchemeSummary("BLANK", "空白", "DETAIL", true, true),
				new QuotationAdminRepository.SchemeSummary("SALES", "銷售報價單", "DETAIL", true, true)
			)
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
				),
				new QuotationAdminRepository.SchemeItem(
					2,
					1,
					2,
					"DUST_NET",
					"防塵網",
					"9針",
					"m2",
					new BigDecimal("40"),
					"(實做實算)",
					2,
					"DIRECT",
					true,
					true,
					true,
					true
				)
			)
		);
		objectMapper = new ObjectMapper();
		service = new QuotationRequestValidationService(repository);
	}

	@Test
	void validatesTwoPointZeroAndReturnsCompletePreviewDataWithoutCalculatingAmounts() {
		Object request = service.validate(readJson(validRequest()), "CNS");
		JsonNode preview = readJson(objectMapper.writeValueAsString(request));

		assertThat(preview.path("schemaVersion").asString()).isEqualTo("2.0");
		assertThat(preview.path("headerPatch").path("companyName").path("value").asString()).isEqualTo("範例工程");
		assertThat(preview.path("headerPatch").path("salesRepresentative").path("value").asString()).isEqualTo("陳業務");
		assertThat(preview.path("standardItems").get(0).path("itemName").asString()).isEqualTo("外部鷹架");
		assertThat(preview.path("standardItems").get(0).path("specification").asString()).isEqualTo("(CNS)");
		assertThat(preview.path("standardItems").get(0).path("unitPrice").decimalValue())
			.isEqualByComparingTo("220");
		assertThat(preview.path("standardItems").get(0).path("lineAmount").isNull()).isTrue();
		assertThat(preview.path("customItems").get(0).path("unitPrice").path("value").decimalValue())
			.isEqualByComparingTo("500");
		assertThat(preview.path("removedItemCodes")).extracting(JsonNode::asString).containsExactly("DUST_NET");
		assertThat(preview.path("nextAction").asString()).isEqualTo("SHOW_PREVIEW");
	}

	@Test
	void keepsTheUserWordingWhenAiMapsAnApproximateNameToAMasterItem() {
		String request = validRequest().replace(
			"\"itemCode\": \"EXTERNAL_SCAFFOLD\",",
			"\"itemCode\": \"EXTERNAL_SCAFFOLD\", \"matchedName\": \"外牆鷹架\","
		);

		JsonNode preview = readJson(objectMapper.writeValueAsString(service.validate(readJson(request), "CNS")));

		assertThat(preview.path("standardItems").get(0).path("itemName").asString()).isEqualTo("外部鷹架");
		assertThat(preview.path("standardItems").get(0).path("matchedName").asString()).isEqualTo("外牆鷹架");
	}

	@Test
	void dropsTheMatchedNameWhenTheUserAlreadyUsedTheMasterItemName() {
		String request = validRequest().replace(
			"\"itemCode\": \"EXTERNAL_SCAFFOLD\",",
			"\"itemCode\": \"EXTERNAL_SCAFFOLD\", \"matchedName\": \"外部鷹架\","
		);

		JsonNode preview = readJson(objectMapper.writeValueAsString(service.validate(readJson(request), "CNS")));

		assertThat(preview.path("standardItems").get(0).path("matchedName").isNull()).isTrue();
	}

	@Test
	void refusesAnyAiChosenSchemeWhenTheUserHasNotSpecifiedOne() {
		assertThatThrownBy(() -> service.validate(readJson(validRequest())))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("報價格式必須由使用者指定");
	}

	@Test
	void refusesToLetAiOverrideTheSchemeTheUserAlreadySpecified() {
		assertThatThrownBy(() -> service.validate(readJson(validRequest()), "GENERAL"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("不可改判");
	}

	@Test
	void acceptsTheUserSpecifiedSchemeEvenWhenAiLeavesItNull() {
		String request = validRequest()
			.replace("\"schemeCode\": \"CNS\"", "\"schemeCode\": null")
			.replace("\"schemeConfidence\": 0.99", "\"schemeConfidence\": 0");

		JsonNode preview = readJson(objectMapper.writeValueAsString(service.validate(readJson(request), "CNS")));

		assertThat(preview.path("schemeCode").asString()).isEqualTo("CNS");
		assertThat(preview.path("standardItems").get(0).path("itemName").asString()).isEqualTo("外部鷹架");
	}

	@Test
	void rejectsStandardItemsForSchemesThatDoNotUseAFixedCatalog() {
		String request = validRequest().replace("\"schemeCode\": \"CNS\"", "\"schemeCode\": \"MARINE\"");

		assertThatThrownBy(() -> service.validate(readJson(request), "MARINE"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("不使用固定品項");
	}

	@Test
	void rejectsPlaceholderItemCodesWithAnActionableMessage() {
		String request = validRequest().replace("\"itemCode\": \"EXTERNAL_SCAFFOLD\"", "\"itemCode\": \"UNKNOWN\"");

		assertThatThrownBy(() -> service.validate(readJson(request), "CNS"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("佔位代碼")
			.hasMessageContaining("品項缺漏欄位");
	}

	@Test
	void rejectsDatabaseFixedFieldsAndCalculatedAmountsOnStandardItems() {
		String request = validRequest().replace(
			"\"confidence\": 0.98",
			"\"confidence\": 0.98, \"unitPrice\": 1, \"lineAmount\": 2"
		);

		assertThatThrownBy(() -> service.validate(readJson(request), "CNS"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("不允許欄位");
	}

	@Test
	void rejectsLowConfidenceValuesInsteadOfGuessingThem() {
		String request = validRequest().replace(
			"\"value\": \"範例工程\", \"sourceText\": \"範例工程\", \"confidence\": 0.99",
			"\"value\": \"可能是範例\", \"sourceText\": \"模糊文字\", \"confidence\": 0.49"
		);

		assertThatThrownBy(() -> service.validate(readJson(request), "CNS"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("低信心");
	}

	@Test
	void rejectsUnknownNextActionsSoTheApplicationRemainsTheOnlyExecutor() {
		String request = validRequest().replace("\"SHOW_PREVIEW\"", "\"SEND_LINE_AND_EXPORT\"");

		assertThatThrownBy(() -> service.validate(readJson(request), "CNS"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("nextAction");
	}

	// 方法：船用與空白已完成圖片決策時，模型錯猜下一步不應使有效動態品項整批失敗。
	@Test
	void derivesImageDecisionForMarineAndBlankInsteadOfTrustingModelAction() {
		for (String scheme : List.of("MARINE", "BLANK")) {
			for (boolean declined : List.of(false, true)) {
				for (String suggested : List.of("SHOW_PREVIEW", "REQUEST_IMAGE_DECISION", "REQUEST_BASE_FIELDS", "REQUEST_ITEM_FIELDS")) {
					String request = salesRequestWithDynamicItems(1)
						.replace("\"SALES\"", "\"" + scheme + "\"")
						.replace("\"imageDeclined\": false", "\"imageDeclined\": " + declined)
						.replace("\"SHOW_PREVIEW\"", "\"" + suggested + "\"");
					var result = service.validate(readJson(request), scheme);
					assertThat(result.nextAction()).isEqualTo(declined ? "SHOW_PREVIEW" : "REQUEST_IMAGE_DECISION");
					assertThat(result.customItems()).hasSize(1);
				}
			}
		}
	}

	// 方法：已選圖、缺基礎欄位與缺品項欄位仍依固定優先順序，不能被模型預覽建議跳過。
	@Test
	void derivesActionFromValidatedFieldsForAllModes() {
		for (String scheme : List.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES")) {
			var root = (tools.jackson.databind.node.ObjectNode) readJson(salesRequestWithDynamicItems(0).replace("\"SALES\"", "\"" + scheme + "\""));
			root.put("nextAction", "REQUEST_IMAGE_DECISION");
			root.set("imageAssessments", readJson("[" + imageAssessmentJson("image-1", "0.95") + "]"));
			root.put("selectedImageMessageId", "image-1");
			assertThat(service.validate(root, scheme).nextAction()).isEqualTo("SHOW_PREVIEW");
			root.put("nextAction", "SHOW_PREVIEW");
			root.set("missingItemFields", readJson("""
				[{"itemRef":"item-1","fields":["quantity"],"reason":"MISSING","sourceText":"品項未提供數量","confidence":0}]
				"""));
			assertThat(service.validate(root, scheme).nextAction()).isEqualTo("REQUEST_ITEM_FIELDS");
			root.set("missingBaseFields", readJson("[" + missingBaseFieldJson("companyName") + "]"));
			assertThat(service.validate(root, scheme).nextAction()).isEqualTo("REQUEST_BASE_FIELDS");
		}
	}

	@Test
	void rejectsAnActiveDatabaseSchemeOutsideTheFiveFormatContract() {
		when(repository.findSchemes()).thenReturn(
			List.of(new QuotationAdminRepository.SchemeSummary("LEGACY", "舊格式", "DETAIL", true, true))
		);
		String request = validRequest().replace("\"schemeCode\": \"CNS\"", "\"schemeCode\": \"LEGACY\"");

		assertThatThrownBy(() -> service.validate(readJson(request), "LEGACY"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("不支援的報價格式");
	}

	@Test
	void acceptsTheThirdTemporaryItemForCns() {
		String customItem = customItemJson("temporary-2", "臨時品項二");
		String request = validRequest().replace(
			"\"customItems\": [" + customItemJson("temporary-1", "臨時品項") + "]",
			"\"customItems\": ["
				+ customItemJson("temporary-1", "臨時品項") + ","
				+ customItem + ","
				+ customItemJson("temporary-3", "臨時品項三") + "]"
		);

		assertThat(service.validate(readJson(request), "CNS").customItems()).hasSize(3);
	}

	@Test
	void acceptsTwoHundredDynamicItemsInOneSalesRequest() {
		Object result = service.validate(readJson(salesRequestWithDynamicItems(200)), "SALES");
		JsonNode preview = readJson(objectMapper.writeValueAsString(result));

		assertThat(preview.path("customItems")).hasSize(200);
	}

	@Test
	void rejectsTheTwoHundredAndFirstDynamicItem() {
		assertThatThrownBy(() -> service.validate(readJson(salesRequestWithDynamicItems(201)), "SALES"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("0 至 200");
	}

	@Test
	void rejectsASelectedImageThatIsNotTheHighestDistinctivenessCandidate() {
		String request = validRequest().replace(
			"\"imageAssessments\": [" + imageAssessmentJson("image-1", "0.95") + "]",
			"\"imageAssessments\": ["
				+ imageAssessmentJson("image-1", "0.95") + ","
				+ imageAssessmentJson("image-2", "0.99") + "]"
		);

		assertThatThrownBy(() -> service.validate(readJson(request), "CNS"))
			.isInstanceOf(QuotationAdminException.class)
			.hasMessageContaining("最高");
	}

	@Test
	void keepsAllMissingBaseFieldsInOneRequestAndRequiresOneBaseFieldAction() {
		String request = validRequest()
			.replace("\"headerPatch\": {" + headerPatchJson() + "}", "\"headerPatch\": {}")
			.replace(
				"\"missingBaseFields\": []",
				"\"missingBaseFields\": ["
					+ missingBaseFieldJson("companyName") + ","
					+ missingBaseFieldJson("workName") + "]"
			)
			.replace("\"SHOW_PREVIEW\"", "\"REQUEST_BASE_FIELDS\"");

		Object result = service.validate(readJson(request), "CNS");
		JsonNode preview = readJson(objectMapper.writeValueAsString(result));

		assertThat(preview.path("missingBaseFields")).hasSize(2);
		assertThat(preview.path("nextAction").asString()).isEqualTo("REQUEST_BASE_FIELDS");
	}

	private JsonNode readJson(String json) {
		return objectMapper.readTree(json);
	}

	private String validRequest() {
		return """
			{
			  "schemaVersion": "2.0",
			  "schemeCode": "CNS",
			  "schemeConfidence": 0.99,
			  "headerPatch": {%s},
			  "standardItemIntents": [
			    {
			      "itemCode": "EXTERNAL_SCAFFOLD",
			      "quantity": 2.5,
			      "sourceText": "外部鷹架 2.5 平方米",
			      "confidence": 0.98
			    }
			  ],
			  "customItems": [%s],
			  "removedItemCodes": ["DUST_NET"],
			  "imageAssessments": [%s],
			  "selectedImageMessageId": "image-1",
			  "imageDeclined": false,
			  "missingBaseFields": [],
			  "missingItemFields": [],
			  "nextAction": "SHOW_PREVIEW",
			  "warnings": []
			}
			""".formatted(
			headerPatchJson(),
			customItemJson("temporary-1", "臨時品項"),
			imageAssessmentJson("image-1", "0.95")
		);
	}

	private String salesRequestWithDynamicItems(int itemCount) {
		String customItems = IntStream.rangeClosed(1, itemCount)
			.mapToObj(index -> customItemJson("dynamic-" + index, "動態品項" + index))
			.map(item -> item.replace("\"kind\": \"TEMPORARY\"", "\"kind\": \"DYNAMIC\""))
			.collect(Collectors.joining(","));
		return """
			{
			  "schemaVersion": "2.0",
			  "schemeCode": "SALES",
			  "schemeConfidence": 0.99,
			  "headerPatch": {%s},
			  "standardItemIntents": [],
			  "customItems": [%s],
			  "removedItemCodes": [],
			  "imageAssessments": [],
			  "selectedImageMessageId": null,
			  "imageDeclined": false,
			  "missingBaseFields": [],
			  "missingItemFields": [],
			  "nextAction": "SHOW_PREVIEW",
			  "warnings": []
			}
			""".formatted(headerPatchJson(), customItems);
	}

	private String headerPatchJson() {
		return """
			"companyName": {"value": "範例工程", "sourceText": "範例工程", "confidence": 0.99},
			"workName": {"value": "台北工地", "sourceText": "工作名稱台北工地", "confidence": 0.98},
			"contactName": {"value": "王先生", "sourceText": "聯絡人王先生", "confidence": 0.96},
			"salesRepresentative": {"value": "陳業務", "sourceText": "業務承辦陳業務", "confidence": 0.97}
			""".stripTrailing();
	}

	private String customItemJson(String clientItemId, String itemName) {
		return """
			{
			  "clientItemId": "%s",
			  "kind": "TEMPORARY",
			  "itemName": {"value": "%s", "sourceText": "%s", "confidence": 0.99},
			  "specification": {"value": "一式", "sourceText": "規格一式", "confidence": 0.99},
			  "unit": {"value": "式", "sourceText": "單位式", "confidence": 0.99},
			  "unitPrice": {"value": 500, "sourceText": "單價500", "confidence": 0.99},
			  "quantity": {"value": 1, "sourceText": "數量1", "confidence": 0.99},
			  "remark": null
			}
			""".formatted(clientItemId, itemName, itemName).strip();
	}

	private String imageAssessmentJson(String messageId, String distinctivenessScore) {
		return """
			{
			  "messageId": "%s",
			  "qualityScore": 0.9,
			  "viewpointScore": 0.9,
			  "distinctivenessScore": %s,
			  "reason": "視角清楚且容易區分"
			}
			""".formatted(messageId, distinctivenessScore).strip();
	}

	private String missingBaseFieldJson(String field) {
		return """
			{"field": "%s", "reason": "MISSING", "sourceText": null, "confidence": null}
			""".formatted(field).strip();
	}
}
