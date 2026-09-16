package dev.miudog.linebotcommercial.service.quotation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void aiContractDefinesTheCompleteTwoPointZeroPatchShapeForAllSchemes() throws IOException {
		JsonNode schema = readJson("/ai/quotation-request.schema.json");

		JsonNode properties = schema.path("properties");
		Set<String> schemeCodes = StreamSupport.stream(
			properties.path("schemeCode").path("oneOf").get(0).path("enum").spliterator(),
			false
		)
			.map(JsonNode::asString)
			.collect(Collectors.toSet());
		Set<String> nextActions = StreamSupport.stream(properties.path("nextAction").path("enum").spliterator(), false)
			.map(JsonNode::asString)
			.collect(Collectors.toSet());
		JsonNode headerProperties = properties.path("headerPatch").path("properties");

		assertThat(properties.path("schemaVersion").path("const").asString()).isEqualTo("2.0");
		assertThat(schemeCodes).containsExactlyInAnyOrder(
			"CNS",
			"GENERAL",
			"MARINE",
			"BLANK",
			"SALES"
		);
		assertThat(headerProperties.propertyNames()).containsExactlyInAnyOrder(
			"companyName",
			"workName",
			"contactName",
			"phone",
			"fax",
			"email",
			"projectLocation",
			"salesRepresentative",
			"additionalHeader"
		);
		assertThat(schema.path("$defs").path("missingBaseField").path("properties")
			.path("field").path("enum"))
			.extracting(JsonNode::asString)
			.contains("salesRepresentative");
		assertThat(nextActions).containsExactlyInAnyOrder(
			"REQUEST_BASE_FIELDS",
			"REQUEST_ITEM_FIELDS",
			"REQUEST_IMAGE_DECISION",
			"SHOW_PREVIEW"
		);
		assertThat(schema.path("required")).extracting(JsonNode::asString).containsExactlyInAnyOrder(
			"schemaVersion",
			"schemeCode",
			"schemeConfidence",
			"headerPatch",
			"standardItemIntents",
			"customItems",
			"removedItemCodes",
			"imageAssessments",
			"selectedImageMessageId",
			"imageDeclined",
			"missingBaseFields",
			"missingItemFields",
			"nextAction",
			"warnings"
		);
	}

	@Test
	void aiContractSeparatesStandardAndCustomItemsWithoutAllowingCalculatedAmounts() throws IOException {
		JsonNode schema = readJson("/ai/quotation-request.schema.json");
		JsonNode properties = schema.path("properties");
		JsonNode standardProperties = properties.path("standardItemIntents").path("items").path("properties");
		JsonNode customProperties = properties.path("customItems").path("items").path("properties");

		assertThat(standardProperties.propertyNames()).containsExactlyInAnyOrder(
			"itemCode",
			"matchedName",
			"quantity",
			"sourceText",
			"confidence"
		);
		assertThat(standardProperties.has("specification")).isFalse();
		assertThat(standardProperties.has("unit")).isFalse();
		assertThat(standardProperties.has("unitPrice")).isFalse();
		assertThat(standardProperties.has("remark")).isFalse();
		assertThat(standardProperties.has("lineAmount")).isFalse();
		assertThat(customProperties.propertyNames()).contains(
			"clientItemId",
			"kind",
			"itemName",
			"specification",
			"unit",
			"unitPrice",
			"quantity",
			"remark"
		);
		assertThat(customProperties.has("lineAmount")).isFalse();
		assertThat(properties.has("subtotal")).isFalse();
		assertThat(properties.has("taxAmount")).isFalse();
		assertThat(properties.has("totalAmount")).isFalse();
		assertThat(properties.path("standardItemIntents").path("maxItems").asInt()).isBetween(1, 100);
		assertThat(standardProperties.path("sourceText").path("maxLength").asInt())
			.isBetween(1, 2000);
		assertThat(standardProperties.path("quantity").path("anyOf").get(0).path("maximum").decimalValue())
			.isEqualByComparingTo("1000000000");
		assertThat(customProperties.path("quantity").path("$ref").asString())
			.isEqualTo("#/$defs/nullablePositiveExplicitDecimal");
		assertThat(properties.path("customItems").path("maxItems").asInt()).isEqualTo(200);
		assertThat(properties.path("imageAssessments").path("maxItems").asInt()).isBetween(1, 50);
		assertThat(properties.path("selectedImageMessageId").path("description").asString())
			.contains("highest distinctiveness score");
		assertThat(properties.path("warnings").path("maxItems").asInt()).isBetween(1, 50);
		assertThat(properties.path("missingBaseFields").path("maxItems").asInt()).isBetween(1, 50);
		assertThat(properties.path("missingItemFields").path("maxItems").asInt()).isBetween(1, 50);
	}

	@Test
	void templateDefinitionsMatchTheFiveExtractedWorksheets() throws IOException {
		JsonNode definitions = readJson("/quotation/template-definitions.json");

		assertThat(definitions.path("templates")).hasSize(5);
		assertTemplate(definitions, "CNS", "CNS", 11, 31, "G35", false);
		assertTemplate(definitions, "GENERAL", "一般架", 11, 30, "G34", false);
		assertTemplate(definitions, "MARINE", "船用", 11, 23, "G27", true);
		assertTemplate(definitions, "BLANK", "空白", 11, 28, "G32", false);
		assertTemplate(definitions, "SALES", "銷售報價單(報價單號前會多一個S)", 11, 28, "G32", false);
	}

	@Test
	void richMenuDefinesSixNonOverlappingMessageActions() throws IOException {
		JsonNode richMenu = readJson("/line/rich-menu.json");

		assertThat(richMenu.path("size").path("width").asInt()).isEqualTo(2500);
		assertThat(richMenu.path("size").path("height").asInt()).isEqualTo(1686);
		assertThat(richMenu.path("selected").asBoolean()).isTrue();
		assertThat(richMenu.path("chatBarText").asString().length()).isLessThanOrEqualTo(14);
		assertThat(richMenu.path("areas")).hasSize(6);
		assertThat(richMenu.path("areas"))
			.allSatisfy(area -> assertThat(area.path("action").path("type").asString()).isEqualTo("message"));
		assertThat(richMenu.path("areas"))
			.extracting(area -> area.path("action").path("text").asString())
			.containsExactly("建立報價", "我的草稿", "選擇報價類型", "上傳圖片", "使用說明", "取消操作");
	}

	private void assertTemplate(
		JsonNode definitions,
		String schemeCode,
		String sheetName,
		int firstRow,
		int lastRow,
		String totalCell,
		boolean summaryOnly
	) {
		JsonNode template = StreamSupport.stream(definitions.path("templates").spliterator(), false)
			.filter(candidate -> schemeCode.equals(candidate.path("schemeCode").asString()))
			.findFirst()
			.orElseThrow();

		assertThat(template.path("sheetName").asString()).isEqualTo(sheetName);
		assertThat(template.path("workbookPath").asString())
			.isEqualTo("src/test/resources/quotation/templates/quotation-template-" + schemeCode + ".xlsx");
		assertThat(template.path("detail").path("firstRow").asInt()).isEqualTo(firstRow);
		assertThat(template.path("detail").path("lastRow").asInt()).isEqualTo(lastRow);
		assertThat(template.path("totals").path("total").asString()).isEqualTo(totalCell);
		assertThat(template.path("summaryOnly").asBoolean()).isEqualTo(summaryOnly);
		assertThat(template.path("image").path("fit").asString()).isEqualTo("CONTAIN");
	}

	private JsonNode readJson(String resourcePath) throws IOException {
		try (InputStream input = getClass().getResourceAsStream(resourcePath)) {
			assertThat(input).as("resource %s", resourcePath).isNotNull();
			return objectMapper.readTree(input);
		}
	}
}
