package dev.miudog.linebotcommercial.service.quotation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 將既有業務 Schema 轉成 strict 傳輸契約；數值與跨欄位規則仍由業務驗證器執行。 */
final class QuotationStructuredSchema {

	// 方法：此轉換器只有無狀態操作，不建立執行個體。
	private QuotationStructuredSchema() {}

	// 方法：建立獨立的傳輸 Schema，不修改原始 JSON／驗證契約。
	static JsonNode create(ObjectMapper mapper, String source, String schemeCode) {
		// 序列化：產生可修改的副本，避免污染其他同時處理的報價格式。
		ObjectNode schema = (ObjectNode) mapper.readTree(source);
		ObjectNode positive = (ObjectNode) schema.path("$defs").path("nullablePositiveExplicitDecimal");
		positive.removeAll();
		positive.putArray("anyOf")
			.add(mapper.createObjectNode().put("$ref", "#/$defs/explicitDecimal"))
			.add(mapper.createObjectNode().put("type", "null"));

		normalize(mapper, schema);
		ObjectNode itemCode = (ObjectNode) schema.path("properties").path("standardItemIntents")
			.path("items").path("properties").path("itemCode");
		if (itemCode.has("enum")) {
			// Schema：共用代碼定義，避免新增與刪除各複製 500 個 enum 而超出供應商限制。
			((ObjectNode) schema.path("$defs")).set("catalogItemCode", itemCode.deepCopy());
			itemCode.removeAll();
			itemCode.put("$ref", "#/$defs/catalogItemCode");
			((ObjectNode) schema.path("properties").path("removedItemCodes"))
				.set("items", mapper.createObjectNode().put("$ref", "#/$defs/catalogItemCode"));
		}
		ObjectNode scheme = mapper.createObjectNode().put("type", schemeCode == null ? "null" : "string");
		if (schemeCode != null) scheme.putArray("enum").add(schemeCode);

		((ObjectNode) schema.path("properties")).set("schemeCode", scheme);
		return schema;
	}

	// 方法：只處理現有 Schema 使用的結構，將可省略欄位改成 required＋null。
	private static void normalize(ObjectMapper mapper, JsonNode node) {
		if (node.isArray()) {
			for (JsonNode child : node) normalize(mapper, child);
			return;
		}
		if (!(node instanceof ObjectNode object)) return;

		object.remove(List.of("$schema", "$id", "title", "description", "uniqueItems", "exclusiveMinimum"));
		if (object.has("oneOf")) object.set("anyOf", object.remove("oneOf"));

		if (object.has("const")) {
			JsonNode constant = object.remove("const");
			object.put("type", "string");
			object.putArray("enum").add(constant);
		}
		if (object.get("properties") instanceof ObjectNode properties) {
			Set<String> required = new HashSet<>();
			for (JsonNode field : object.path("required")) required.add(field.asString());
			var allRequired = mapper.createArrayNode();
			for (String name : List.copyOf(properties.propertyNames())) {
				JsonNode field = properties.get(name);
				normalize(mapper, field);
				if (!required.contains(name)) {
					ObjectNode nullable = mapper.createObjectNode();
					nullable.putArray("anyOf").add(field).add(mapper.createObjectNode().put("type", "null"));
					properties.set(name, nullable);
				}
				allRequired.add(name);
			}
			object.set("required", allRequired);
			object.put("additionalProperties", false);
		}
		for (String name : List.copyOf(object.propertyNames())) {
			if (!"properties".equals(name)) normalize(mapper, object.get(name));
		}
	}
}
