package dev.miudog.linebotcommercial.service.quotation;

import dev.miudog.linebotcommercial.repository.QuotationAdminRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 範例檔本身必須通過實際 CSV／業務驗證與計價，避免文件和程式行為分歧。 */
class QuotationExamplePackTest {
	private static final Path ROOT = Path.of("docs/examples/quotation-test-pack");
	private final ObjectMapper mapper = new ObjectMapper();

	// 方法：逐一讀取交付使用者的檔案；每個案例使用全新合成主檔，不連正式服務。
	@TestFactory
	List<DynamicTest> verifiesDeliveredCsvFilesAndExpectedTotals() throws Exception {
		JsonNode manifest = mapper.readTree(Files.readString(ROOT.resolve("csv-manifest.json")));
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonNode fixture : manifest.path("fixtures")) {
			tests.add(DynamicTest.dynamicTest(fixture.path("file").asString(), () -> verifyFixture(fixture)));
		}
		return tests;
	}

	// 方法：真正呼叫解析與計價服務，不以測試內的另一份 CSV 解析器取代產品邏輯。
	private void verifyFixture(JsonNode fixture) throws Exception {
		byte[] bytes = Files.readAllBytes(ROOT.resolve(fixture.path("file").asString()));
		if (fixture.path("decodeError").asBoolean()) {
			assertThatThrownBy(() -> StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)))
				.isInstanceOf(java.nio.charset.CharacterCodingException.class);
			return;
		}
		String csv = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
		try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
			var repository = new QuotationAdminRepository(JdbcClient.create(new SingleConnectionDataSource(connection, true)), mapper);
			var parser = new QuotationInputCsvService(new QuotationRequestValidationService(repository), mapper);
			if (!fixture.path("accept").asBoolean()) {
				assertThatThrownBy(() -> parser.parse(csv)).isInstanceOf(RuntimeException.class)
					.hasMessageContaining(fixture.path("error").asString());
				return;
			}
			var request = parser.parse(csv).request();
			assertThat(request.schemeCode()).isEqualTo(fixture.path("scheme").asString());
			assertThat(request.standardItems().size() + request.customItems().size()).isEqualTo(fixture.path("items").asInt());
			var standard = request.standardItems().stream().map(item -> new QuotationCalculationRequest.StandardItemIntent(
				item.itemCode(), item.quantity(), null
			)).toList();
			var custom = request.customItems().stream().map(item -> new QuotationCalculationRequest.CustomItem(
				item.itemName().value(), item.specification().value(), item.unit().value(), item.unitPrice().value(),
				item.quantity().value(), item.remark() == null ? "" : item.remark().value(), true, null
			)).toList();
			var calculator = new QuotationCalculationService(repository, new QuotationBusinessRules(new BigDecimal("0.05"), 15));
			var result = calculator.calculate(new QuotationCalculationRequest(request.schemeCode(), standard, custom, Set.of()));
			assertThat(result.subtotal()).isEqualByComparingTo(fixture.path("subtotal").asString());
			assertThat(result.tax()).isEqualByComparingTo(fixture.path("tax").asString());
			assertThat(result.total()).isEqualByComparingTo(fixture.path("total").asString());
			if (fixture.has("presentation")) assertThat(result.customerPresentation().name()).isEqualTo(fixture.path("presentation").asString());
		}
	}

	// 方法：驗收清單須覆蓋所有格式與現行按鈕動作，且引用的檔案與自動測試確實存在。
	@Test
	void coversEverySchemeAndPostbackActionWithExecutableReferences() throws Exception {
		JsonNode cases = mapper.readTree(Files.readString(ROOT.resolve("cases.json")));
		Set<String> schemes = new java.util.HashSet<>();
		Set<String> actions = new java.util.HashSet<>();
		Set<String> ids = new java.util.HashSet<>();
		for (JsonNode scenario : cases) {
			assertThat(ids.add(scenario.path("id").asString())).isTrue();
			assertThat(scenario.path("steps").asString()).isNotBlank();
			assertThat(scenario.path("expected").asString()).isNotBlank();
			if (scenario.has("scheme")) schemes.add(scenario.path("scheme").asString());
			if (scenario.has("action")) actions.add(scenario.path("action").asString());
			for (JsonNode file : scenario.path("files")) assertThat(ROOT.resolve(file.asString())).exists();
			for (JsonNode test : scenario.path("tests")) assertThat(Path.of(test.asString())).exists();
		}
		assertThat(schemes).containsAll(QuotationSchemeKeywords.schemeCodes());
		assertThat(actions).containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(QuotationPostbackAction.values()).map(Enum::name).toList());
	}
}
