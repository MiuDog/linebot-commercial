package dev.myudog.assetsmanagerlinebot.service.quotation;

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
	void aiContractAcceptsOnlyKnownSchemesAndNeverLetsAiSetPrices() throws IOException {
		JsonNode schema = readJson("/ai/quotation-request.schema.json");

		JsonNode properties = schema.path("properties");
		Set<String> schemeCodes = StreamSupport.stream(properties.path("schemeCode").path("enum").spliterator(), false)
			.map(JsonNode::asString)
			.collect(Collectors.toSet());
		JsonNode itemProperties = properties.path("items").path("items").path("properties");

		assertThat(schemeCodes).containsExactlyInAnyOrder("CNS", "GENERAL", "MARINE");
		assertThat(itemProperties.has("quantity")).isTrue();
		assertThat(itemProperties.has("price")).isFalse();
		assertThat(itemProperties.has("unitPrice")).isFalse();
		assertThat(itemProperties.has("lineAmount")).isFalse();
	}

	@Test
	void aiContractBoundsModelControlledCollectionsAndText() throws IOException {
		JsonNode schema = readJson("/ai/quotation-request.schema.json");
		JsonNode properties = schema.path("properties");

		assertThat(schema.path("required")).anySatisfy(field -> assertThat(field.asString()).isEqualTo("quotationName"));
		assertThat(properties.path("quotationName").path("maxLength").asInt()).isBetween(1, 200);
		assertThat(properties.path("items").path("maxItems").asInt()).isBetween(1, 100);
		assertThat(properties.path("items").path("items").path("properties").path("sourceText").path("maxLength").asInt())
			.isBetween(1, 2000);
		assertThat(properties.path("imageAssessments").path("maxItems").asInt()).isBetween(1, 50);
		assertThat(properties.path("warnings").path("maxItems").asInt()).isBetween(1, 50);
	}

	@Test
	void templateDefinitionsMatchTheThreeExtractedWorksheets() throws IOException {
		JsonNode definitions = readJson("/quotation/template-definitions.json");

		assertThat(definitions.path("templates")).hasSize(3);
		assertTemplate(definitions, "CNS", "CNS", 11, 28, "G32", false);
		assertTemplate(definitions, "GENERAL", "一般架", 11, 30, "G34", false);
		assertTemplate(definitions, "MARINE", "船用", 11, 23, "G27", true);
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
