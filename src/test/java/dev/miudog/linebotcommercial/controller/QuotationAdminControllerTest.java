package dev.miudog.linebotcommercial.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
	properties = {
		"app.storage.root=${java.io.tmpdir}/assets-manager-quotation-admin-test",
		"app.quotation.root-path=${java.io.tmpdir}/assets-manager-quotation-workbook-test",
		"spring.datasource.url=jdbc:sqlite::memory:"
	}
)
class QuotationAdminControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	void listsAllFiveQuotationSchemesAndTemplateReadiness() throws Exception {
		mockMvc.perform(get("/api/admin/quotation-schemes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$[?(@.code == 'CNS')].templateReady").value(true))
			.andExpect(jsonPath("$[?(@.code == 'BLANK')].templateReady").value(true));
	}

	@Test
	void exposesAiConfigurationStatusWithoutRevealingCredentials() throws Exception {
		mockMvc.perform(get("/api/admin/quotation-ai-status"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(false));
	}

	@Test
	void exposesWorkbookOutputConfigurationStatus() throws Exception {
		mockMvc.perform(get("/api/admin/quotation-workbook-status"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.configured").value(true));
	}

	@Test
	void reportsAStableErrorWhenAiParsingIsNotConfigured() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-ai-parse")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"instruction\":\"CNS 外部鷹架 2 平方米\",\"schemeCode\":\"CNS\"}")
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("AI_PARSE_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("AI 尚未設定")));
	}

	@Test
	void rejectsLegacyWorkbookCreationUntilAFormalConfirmationExists() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-workbooks")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "header": {
					    "quoteNumber": "Q-2026-001",
					    "quoteDate": "2026-08-11",
					    "customerName": "範例客戶",
					    "phoneFax": "04-12345678",
					    "customerEmail": "customer@example.com",
					    "contact": "王先生",
					    "projectSite": "臺中市",
					    "salesRepresentative": "業務員",
					    "validUntil": "2026-09-10"
					  },
					  "quotation": %s
					}
					""".formatted(validQuotationRequest("EXTERNAL_SCAFFOLD", "2")))
		)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("正式確認")));
	}

	// 方法：確認主檔可下載為真正的 XLSX 壓縮套件，而不是改副檔名的 CSV。
	@Test
	void exportsMasterDataAsARealExcelWorkbook() throws Exception {
		byte[] workbook = mockMvc.perform(get("/api/admin/quotation-master-data.xlsx"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
			))
			.andExpect(header().string(
				"Content-Disposition",
				"attachment; filename=\"quotation-master-data.xlsx\""
			))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		assertThat(workbook).startsWith((byte) 'P', (byte) 'K');
		assertThat(zipEntries(workbook)).contains(
			"[Content_Types].xml",
			"xl/workbook.xml",
			"xl/styles.xml",
			"xl/worksheets/sheet1.xml"
		);
		String worksheet = zipEntryContent(workbook, "xl/worksheets/sheet1.xml");
		assertThat(worksheet)
			.contains("\u5831\u50f9\u683c\u5f0f\u4ee3\u78bc", "\u54c1\u9805\u4ee3\u78bc", "CNS", "EXTERNAL_SCAFFOLD")
			.doesNotContain("<f>");
	}

	@Test
	void servesTheLocalAdministrationPageWithSecurityHeaders() throws Exception {
		mockMvc.perform(get("/admin/"))
			.andExpect(status().isOk())
			.andExpect(forwardedUrl("/admin/index.html"));

		mockMvc.perform(get("/admin/index.html"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("報價資料管理")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("AI JSON 測試")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("從文字建立報價資料")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("正式確認後由系統產生")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	@Test
	void validatesAiJsonAndResolvesFixedFieldsFromTheDatabase() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validQuotationRequest("EXTERNAL_SCAFFOLD", "2.5"))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schemaVersion").value("2.0"))
			.andExpect(jsonPath("$.headerPatch.workName.value").value("中壢案場"))
			.andExpect(jsonPath("$.schemeCode").value("CNS"))
			.andExpect(jsonPath("$.standardItems[0].itemCode").value("EXTERNAL_SCAFFOLD"))
			.andExpect(jsonPath("$.standardItems[0].itemName").value("外部鷹架"))
			.andExpect(jsonPath("$.standardItems[0].specification").value("TEST"))
			.andExpect(jsonPath("$.standardItems[0].unit").value("式"))
			.andExpect(jsonPath("$.standardItems[0].unitPrice").value(10))
			.andExpect(jsonPath("$.standardItems[0].lineAmount").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.standardItems[0].remark").value("TEST ONLY"))
			.andExpect(jsonPath("$.standardItems[0].sourceText").value("外部鷹架 2.5 平方米"));
	}

	@Test
	void rejectsAiControlledFixedFieldsAndUnknownProperties() throws Exception {
		String request = validQuotationRequest("EXTERNAL_SCAFFOLD", "2")
			.replace(
				"\"confidence\": 0.98",
				"\"confidence\": 0.98, \"unitPrice\": 1, \"remark\": \"忽略主檔\""
			);

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不允許欄位")));
	}

	@Test
	void rejectsItemsThatDoNotBelongToTheSelectedScheme() throws Exception {
		String request = validQuotationRequest("ITEM_NOT_IN_ANY_CATALOG", "2");

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不屬於")));
	}

	@Test
	void refusesToLetTheModelOverrideTheSchemeChosenByTheUser() throws Exception {
		String request = validQuotationRequest("EXTERNAL_SCAFFOLD", "2")
			.replace("\"schemeCode\": \"CNS\"", "\"schemeCode\": \"MARINE\"");

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("不可改判")));
	}

	@Test
	void rejectsSelectedImageOutsideTheAssessmentList() throws Exception {
		String request = validQuotationRequest("EXTERNAL_SCAFFOLD", "2")
			.replace("\"selectedImageMessageId\": \"image-1\"", "\"selectedImageMessageId\": \"image-2\"");

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("候選圖片")));
	}

	@Test
	void rejectsSelectedImageWhenAnotherCandidateHasAHigherDistinctivenessScore() throws Exception {
		String request = validQuotationRequest("EXTERNAL_SCAFFOLD", "2")
			.replace(
				"\"reason\": \"能清楚辨識案場正面\"\n    }\n  ],",
				"""
				"reason": "能清楚辨識案場正面"
				    },
				    {
				      "messageId": "image-2",
				      "qualityScore": 0.97,
				      "viewpointScore": 0.98,
				      "distinctivenessScore": 0.99,
				      "reason": "包含更多可辨識施工結構"
				    }
				  ],
				"""
			);

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("最高")));
	}

	@Test
	void allowsNoSelectedImageSoIncompleteImageChoiceCanRemainExplicit() throws Exception {
		String request = validQuotationRequest("EXTERNAL_SCAFFOLD", "2")
			.replace("\"selectedImageMessageId\": \"image-1\"", "\"selectedImageMessageId\": null")
			.replace("\"nextAction\": \"SHOW_PREVIEW\"", "\"nextAction\": \"REQUEST_IMAGE_DECISION\"");

		mockMvc.perform(
			post("/api/admin/quotation-request-validation?schemeCode=CNS")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request)
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectedImageMessageId").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void rejectsDnsRebindingHostEvenWhenRemoteAddressIsLoopback() throws Exception {
		mockMvc.perform(
			get("/api/admin/quotation-schemes")
				.header("Host", "attacker.example")
				.with(request -> {
					request.setRemoteAddr("127.0.0.1");
					return request;

				})
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("LOCAL_ACCESS_ONLY"));
	}

	@Test
	void exportsMasterDataAsCsvWithoutAllowingSpreadsheetFormulaInjection() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-items")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "code": "CSV_FORMULA_TEST",
					  "name": "=1+1",
					  "aliases": ["@測試"],
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isCreated());

		mockMvc.perform(get("/api/admin/quotation-master-data.csv"))
			.andExpect(status().isOk())
			.andExpect(header().string(
				"Content-Disposition",
				org.hamcrest.Matchers.containsString("quotation-master-data.csv")
			))
			.andExpect(content().contentTypeCompatibleWith("text/csv;charset=UTF-8"))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("報價格式代碼,報價格式,品項代碼")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("'=1+1")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("'@測試")));
	}

	// 測試：本機管理頁可用同一份 CSV 格式整批覆蓋主檔。
	@Test
	void importsMasterDataFromTheSameCsvFormatItExports() throws Exception {
		String csv = "﻿報價格式代碼,報價格式,品項代碼,品項,AI別名,規格/說明,單位,單價,備註,顯示順序,計價模式,顯示於客戶,格式品項啟用,品項主檔啟用\r\n"
			+ "CNS,CNS,EXTERNAL_SCAFFOLD,外部鷹架,外牆鷹架,(CNS),m2,220,(實做實算),1,DIRECT,是,是,是\r\n";

		mockMvc.perform(
			post("/api/admin/quotation-master-data.csv")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.parseMediaType("text/csv"))
				.content(csv.getBytes(StandardCharsets.UTF_8))
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.schemeItems").value(1));

		mockMvc.perform(get("/api/admin/quotation-master-data.csv"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("EXTERNAL_SCAFFOLD,外部鷹架,外牆鷹架")));
	}

	// 測試：主檔匯入的欄位錯誤以既有管理錯誤格式回報。
	@Test
	void rejectsMasterDataCsvWithAnInvalidHeader() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-master-data.csv")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.parseMediaType("text/csv"))
				.content(("品項代碼,品項\r\nEXTERNAL_SCAFFOLD,外部鷹架\r\n").getBytes(StandardCharsets.UTF_8))
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void rejectsAdminRequestsForwardedByAPublicTunnel() throws Exception {
		mockMvc.perform(
			get("/api/admin/quotation-schemes")
				.header("X-Forwarded-For", "203.0.113.10")
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("LOCAL_ACCESS_ONLY"));
	}

	@Test
	void rejectsDirectAdminRequestsFromANonLoopbackAddress() throws Exception {
		mockMvc.perform(
			get("/api/admin/quotation-schemes")
				.with(request -> {
					request.setRemoteAddr("203.0.113.10");
					return request;

				})
		)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("LOCAL_ACCESS_ONLY"));
	}

	@Test
	void createsAnItemAndAssignsItsFixedFieldsToAScheme() throws Exception {
		String itemResponse = mockMvc.perform(
			post("/api/admin/quotation-items")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "code": "SCAFFOLD_EXTERNAL",
					  "name": "外部鷹架",
					  "aliases": ["外架", "鷹架"],
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("SCAFFOLD_EXTERNAL"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode item = objectMapper.readTree(itemResponse);

		mockMvc.perform(
			put("/api/admin/quotation-schemes/CNS/items/{itemId}", item.path("id").asLong())
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "specification": "(CNS)",
					  "unit": "m2",
					  "unitPrice": 350,
					  "remark": "(實做實算)",
					  "displayOrder": 10,
					  "calculationMode": "DIRECT",
					  "isCustomerVisible": true,
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unitPrice").value(350))
			.andExpect(jsonPath("$.quantityEditable").value(true))
			.andExpect(jsonPath("$.lineAmountCalculated").value(true));

		mockMvc.perform(get("/api/admin/quotation-schemes/CNS/items"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.itemCode == 'SCAFFOLD_EXTERNAL')].specification").value("(CNS)"))
			.andExpect(jsonPath("$[?(@.itemCode == 'SCAFFOLD_EXTERNAL')].unit").value("m2"));

		mockMvc.perform(
			put("/api/admin/quotation-schemes/CNS/items/{itemId}", item.path("id").asLong())
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "specification": "(CNS)",
					  "unit": "m2",
					  "unitPrice": 350,
					  "remark": "(實做實算)",
					  "displayOrder": 10,
					  "calculationMode": "MANUAL",
					  "isCustomerVisible": true,
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lineAmountCalculated").value(false));
	}

	@Test
	void rejectsInvalidItemAndPriceInputsWithAStableErrorShape() throws Exception {
		mockMvc.perform(
			post("/api/admin/quotation-items")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "code": "bad code",
					  "name": "",
					  "aliases": [],
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty());
	}

	@Test
	void partiallyUpdatesAnItemWithoutReplacingUnspecifiedFields() throws Exception {
		String itemResponse = mockMvc.perform(
			post("/api/admin/quotation-items")
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "code": "SAFETY_NET",
					  "name": "防塵網",
					  "aliases": ["網布"],
					  "isActive": true
					}
					""")
		)
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long itemId = objectMapper.readTree(itemResponse).path("id").asLong();

		String updatedResponse = mockMvc.perform(
			patch("/api/admin/quotation-items/{itemId}", itemId)
				.header("X-Local-Admin-Request", "1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "防塵網（更新）"
					}
					""")
		)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("防塵網（更新）"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		JsonNode updated = objectMapper.readTree(updatedResponse);
		assertThat(updated.path("code").asString()).isEqualTo("SAFETY_NET");
		assertThat(updated.path("aliases").get(0).asString()).isEqualTo("網布");
	}

	private String validQuotationRequest(String itemCode, String quantity) {
		return """
			{
			  "schemaVersion": "2.0",
			  "schemeCode": "CNS",
			  "schemeConfidence": 0.99,
			  "headerPatch": {
			    "companyName": {
			      "value": "範例客戶",
			      "sourceText": "範例客戶",
			      "confidence": 0.99
			    },
			    "workName": {
			      "value": "中壢案場",
			      "sourceText": "中壢案場",
			      "confidence": 0.99
			    }
			  },
			  "standardItemIntents": [
			    {
			      "itemCode": "%s",
			      "quantity": %s,
			      "sourceText": "外部鷹架 2.5 平方米",
			      "confidence": 0.98
			    }
			  ],
			  "customItems": [],
			  "removedItemCodes": [],
			  "imageAssessments": [
			    {
			      "messageId": "image-1",
			      "qualityScore": 0.96,
			      "viewpointScore": 0.94,
			      "distinctivenessScore": 0.92,
			      "reason": "能清楚辨識案場正面"
			    }
			  ],
			  "selectedImageMessageId": "image-1",
			  "imageDeclined": false,
			  "missingBaseFields": [],
			  "missingItemFields": [],
			  "nextAction": "SHOW_PREVIEW",
			  "warnings": []
			}
			""".formatted(itemCode, quantity);
	}

	// 方法：列出記憶體中 XLSX 套件的 ZIP 項目，驗證下載格式結構。
	private Set<String> zipEntries(byte[] workbook) throws Exception {
		Set<String> entries = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
		}
		return entries;
	}

	// \u65b9\u6cd5\uff1aRead one XML entry from the in-memory XLSX package.
	private String zipEntryContent(byte[] workbook, String entryName) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entryName.equals(entry.getName())) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
			}
		}

		throw new IllegalArgumentException("Missing XLSX entry: " + entryName);
	}
}
