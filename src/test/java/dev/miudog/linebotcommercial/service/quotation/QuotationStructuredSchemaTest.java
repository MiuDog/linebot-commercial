package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotationStructuredSchemaTest {

	@Test
	void producesStrictNullableSchemaWithoutChangingTheBusinessContract() {
		var repository = mock(QuotationAdminRepository.class);
		when(repository.findActiveAiCatalog()).thenReturn(List.of());
		var prompt = new QuotationAiPromptService(repository, new ObjectMapper()).build("建立報價", List.of(), "CNS");
		JsonNode schema = prompt.responseSchema();
		assertThat(schema.toString()).doesNotContain("\"oneOf\"", "\"allOf\"", "\"uniqueItems\"", "\"const\"");
		assertStrictObjects(schema);
		assertThat(schema.path("properties").path("headerPatch").path("properties").path("companyName")
			.path("anyOf").path(1).path("type").asString()).isEqualTo("null");
		assertThat(schema.path("properties").path("schemeCode").path("enum").path(0).asString()).isEqualTo("CNS");
	}

	// 遞迴檢查每個物件都關閉額外欄位，並將全部已宣告欄位列為 required。
	@Test
	void sharesCatalogEnumsAtTheMaximumSupportedCatalogSize() {
		var repository = mock(QuotationAdminRepository.class);
		when(repository.findActiveAiCatalog()).thenReturn(java.util.stream.IntStream.range(0, 500)
			.mapToObj(index -> new QuotationAdminRepository.AiCatalogEntry(
				"CNS", "CNS", "DETAIL", "CODE_" + index, "品項" + index, List.of(), "規格", "組", "DIRECT"
			)).toList());
		JsonNode schema = new QuotationAiPromptService(repository, new ObjectMapper()).build("建立報價", List.of(), "CNS").responseSchema();
		assertThat(schema.path("$defs").path("catalogItemCode").path("enum").size()).isEqualTo(500);
		assertThat(schema.path("properties").path("removedItemCodes").path("items").path("$ref").asString())
			.isEqualTo("#/$defs/catalogItemCode");
		assertThat(schema.path("properties").path("standardItemIntents").path("items").path("properties")
			.path("itemCode").path("$ref").asString()).isEqualTo("#/$defs/catalogItemCode");
	}

	// 方法：逐一檢查巢狀物件的 required 與額外屬性規則。
	private void assertStrictObjects(JsonNode node) {
		if (node.isObject() && node.has("properties")) {
			assertThat(node.path("additionalProperties").asBoolean()).isFalse();
			assertThat(node.path("required")).extracting(JsonNode::asString)
				.containsExactlyElementsOf(node.path("properties").propertyNames());
		}
		for (JsonNode child : node) assertStrictObjects(child);
	}
}
