package dev.miudog.linebotcommercial.service.quotation;

import com.sun.net.httpserver.HttpServer;
import dev.miudog.linebotcommercial.service.ai.AiExtractionService;
import dev.miudog.linebotcommercial.service.ai.AiImageInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class QuotationModelWorkflowTest {

	@TempDir Path directory;

	// 方法：透過真正 HTTP 與 SQLite 邊界驗證角色分流、圖片不重送與跨實例恢復。
	@Test
	void routesImagesThenTextAndReusesPrivatePersistentCheckpoints() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			var scope = new QuotationModelWorkflow.Scope("owner-a", "draft:1:event:1");
			List<AiImageInput> images = List.of(new AiImageInput("image-1", new byte[] {1, 2, 3}, "image/png"));
			fixture.workflow().parse("建立報價", images, "CNS", scope, this::accepted);
			assertThat(fixture.requests).extracting(node -> node.path("model").asString()).containsExactly("vision-model", "text-model");
			assertThat(fixture.requests.get(0).toString()).contains("data:image/png;base64,");
			assertThat(fixture.requests.get(1).toString()).contains("imageObservations").doesNotContain("data:image/");
			assertThat(fixture.requests).allSatisfy(node -> assertThat(node.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue());
			fixture.workflow().parse("建立報價", images, "CNS", scope, this::accepted);
			assertThat(fixture.requests).hasSize(2);
			fixture.workflow().parse("修改電話", images, "CNS", new QuotationModelWorkflow.Scope("owner-a", "draft:2:event:2"), this::accepted);
			assertThat(fixture.requests).hasSize(3);
			fixture.workflow().parse("建立報價", images, "CNS", new QuotationModelWorkflow.Scope("owner-b", "draft:1:event:1"), this::accepted);
			assertThat(fixture.requests).hasSize(5);
		}
	}

	// 方法：修復保留驗證標準，達到既有輸出token預算時可早於五次停止。
	@Test
	void escalatesValidationFailureOnceAndStopsOnRepeatedFailure() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.responses = model -> model.equals("text-model") ? "{broken" : "{}";
			var result = fixture.workflow().parse("建立報價", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "event"), raw -> {
				if (raw.equals("{broken")) throw new QuotationAiException("AI_RESPONSE_INVALID", "invalid");

				return accepted(raw);

			});
			assertThat(result.rawJson()).isEqualTo("{}");
			assertThat(fixture.requests).extracting(node -> node.path("model").asString()).containsExactly("text-model", "escalation-model");
			assertThatThrownBy(() -> fixture.workflow().parse("另一筆報價", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "event2"), raw -> {
				throw new QuotationAiException("AI_MASTER_DATA_VALIDATION_FAILED", "invalid");

			})).isInstanceOf(QuotationAiException.class);
			assertThat(fixture.requests).hasSize(5);
		}
	}

	// 方法：缺值補問不增加模型呼叫；候選衝突才進入升級角色。
	@Test
	void keepsMissingInputAsAQuestionAndEscalatesOnlyConflicts() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			var missing = mock(QuotationRequestValidationService.MissingItemFieldGroup.class);
			when(missing.reason()).thenReturn("NOT_PROVIDED");
			var request = mock(QuotationRequestValidationService.ValidatedQuotationRequest.class);
			when(request.missingItemFields()).thenReturn(List.of(missing));
			fixture.workflow().parse("缺值", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "event"), raw -> new QuotationAiParsingService.ParseResult(request, raw));
			assertThat(fixture.requests).hasSize(1);
			when(missing.reason()).thenReturn("CONFLICT");
			fixture.workflow().parse("衝突", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "event2"), raw -> new QuotationAiParsingService.ParseResult(request, raw));
			assertThat(fixture.requests).hasSize(3);
		}
	}

	// 方法：呼叫、累計輸出、實際 total token 與缺少 usage 都會阻擋額外升級。
	@Test
	void enforcesEveryBudgetBeforeAnotherNetworkCall() throws Exception {
		for (String kind : List.of("calls", "output", "total", "unknown")) {
			try (Fixture fixture = new Fixture(directory.resolve(kind))) {
				if (kind.equals("calls")) fixture.env.setProperty("app.ai.workflow.max-calls", "1");
				if (kind.equals("output")) fixture.env.setProperty("app.ai.workflow.max-output-tokens", "256");
				if (kind.equals("total")) fixture.env.setProperty("app.ai.workflow.max-total-tokens", "256");
				fixture.usage = kind.equals("unknown") ? null : 500;
				assertThatThrownBy(() -> fixture.workflow().parse("建立報價", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "event"), raw -> {
					throw new QuotationAiException("AI_RESPONSE_INVALID", "invalid");

				})).isInstanceOfSatisfying(QuotationAiException.class, error -> assertThat(error.code()).isEqualTo("AI_WORKFLOW_BUDGET"));
				assertThat(fixture.requests).hasSize(1);
			}
		}
	}

	// 方法：跨端點需獨立金鑰，拒絕回應及 HTTP 認證失敗都不換模型重試。
	@Test
	void protectsCredentialsAndDoesNotEscalateRefusalsOrHttpFailures() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.text.api-url", fixture.url() + "/separate");
			assertThatThrownBy(() -> fixture.profiles().resolve("TEXT")).isInstanceOf(QuotationAiException.class);
			fixture.env.setProperty("app.ai.workflow.text.api-key", "role-key");
			fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "ok"), this::accepted);
			assertThat(fixture.authorization).containsExactly("Bearer role-key");
			fixture.status = 401;
			assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "401"), this::accepted))
				.hasMessageContaining("401");
			fixture.status = 200;
			fixture.refusal = true;
			assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "refused"), this::accepted))
				.hasMessageContaining("AI_REFUSED");
			assertThat(fixture.requests).hasSize(3);
			assertThat(fixture.profiles().resolve("TEXT").toString()).doesNotContain("role-key");
		}
	}

	// 方法：整輪期限涵蓋模型等待，逾時不再發送升級請求。
	@Test
	void stopsAtTheWorkflowDeadline() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.timeout-seconds", "1");
			fixture.delayMillis = 1800;
			assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "timeout"), this::accepted))
				.isInstanceOfAny(QuotationAiException.class, dev.miudog.linebotcommercial.service.ai.AiExtractionException.class);
			assertThat(fixture.requests).hasSize(1);
		}
	}

	// 方法：成功結果使用明確無缺值的驗證輸出，錯誤案例由各測試單獨指定。
	@Test
	void capsCombinedOutputAndInvalidatesExpiredOrChangedModelCheckpoints() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.max-output-tokens", "4600");
			fixture.responses = model -> model.equals("text-model") ? "invalid" : "{}";
			var scope = new QuotationModelWorkflow.Scope("owner", "event");
			fixture.workflow().parse("建立", List.of(), "CNS", scope, raw -> {
				if (raw.equals("invalid")) throw new QuotationAiException("AI_RESPONSE_INVALID", "invalid");

				return accepted(raw);

			});
			assertThat(fixture.requests).extracting(node -> node.path("max_completion_tokens").asInt()).containsExactly(4000, 600);
			fixture.env.setProperty("app.ai.workflow.text.model", "new-model");
			fixture.workflow().parse("建立", List.of(), "CNS", scope, this::accepted);
			assertThat(fixture.requests).hasSize(3);
			fixture.jdbc.update("UPDATE quotation_model_checkpoint SET expires_at = 0");
			new QuotationModelCheckpointStore(fixture.jdbc).deleteExpired();
			assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM quotation_model_checkpoint", Integer.class)).isZero();
			fixture.workflow().parse("建立", List.of(), "CNS", scope, this::accepted);
			assertThat(fixture.requests).hasSize(4);
		}
	}

	// 方法：非法圖片輸出不進入文字模型，也不保存為可重用觀察資料。
	@Test
	void rejectsInventedImageIdsWithoutCachingOrCallingText() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.max-output-tokens", "20000");
			fixture.responses = model -> "{\"images\":[{\"messageId\":\"invented\",\"transcription\":\"\",\"description\":\"\"}]}";
			assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(new AiImageInput("image-1", new byte[] {1}, "image/png")),
				"CNS", new QuotationModelWorkflow.Scope("owner", "event"), this::accepted))
					.isInstanceOfSatisfying(QuotationAiException.class, error -> assertThat(error.code()).isEqualTo("AI_IMAGE_RESPONSE_INVALID"));
			assertThat(fixture.requests).hasSize(5);
			assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM quotation_model_checkpoint", Integer.class)).isZero();
		}
	}

	// 方法：建立預設無缺值的驗證結果。
	@Test
	void preservesAllFiveBusinessFormatsThroughTheRealParsingValidator() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			var repository = mock(dev.miudog.linebotcommercial.repository.QuotationAdminRepository.class);
			List<String> schemes = List.of("CNS", "GENERAL", "MARINE", "BLANK", "SALES");
			when(repository.findSchemes()).thenReturn(schemes.stream().map(code ->
				new dev.miudog.linebotcommercial.repository.QuotationAdminRepository.SchemeSummary(code, code, "DETAIL", true, true)).toList());
			var validator = new QuotationRequestValidationService(repository);
			var csv = new QuotationInputCsvService(validator, fixture.mapper);
			var legacy = mock(dev.miudog.linebotcommercial.service.ai.AiJsonCompletionClient.class);
			var parser = new QuotationAiParsingService(legacy, mock(QuotationAiPromptService.class), validator, fixture.mapper);
			parser.configureWorkflow(fixture.workflow());
			for (String scheme : schemes) {
				String input = QuotationInputCsvService.HEADER + "\n" + scheme + ",範例公司,工程,承辦,,,,支架,規格,組,120.5,2,";
				var expected = csv.parse(input);
				fixture.responses = model -> expected.rawJson();
				var actual = parser.parseScoped("建立報價", List.of(), scheme, new QuotationModelWorkflow.Scope("owner", scheme));
				assertThat(actual.request()).isEqualTo(expected.request());
				int before = fixture.requests.size();
				assertThat(parser.parseCsv(input).request()).isEqualTo(expected.request());
				assertThat(fixture.requests).hasSize(before);
			}
			assertThat(fixture.requests).hasSize(5);
			org.mockito.Mockito.verifyNoInteractions(legacy);
		}
	}

	// 方法：未啟用 workflow 時不要求額外端點或 Secret。
	@Test
	void remainsDisabledByDefaultAndInheritsTheLegacyModelWhenEnabled() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.enabled", "false");
			assertThat(fixture.workflow().enabled()).isFalse();
			fixture.env.setProperty("app.ai.workflow.text.model", "");
			assertThat(fixture.profiles().resolve("TEXT").model()).isEqualTo("base-model");
			assertThat(fixture.profiles().resolve("TEXT").key()).isEqualTo("base-key");
		}
	}

	// 方法：建立預設无缺值的驗證結果。
	@Test
	void keepsUnknownUsageBlockedWhenTheSameEventResumes() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.usage = null;
			for (int attempt = 0; attempt < 2; attempt++) {
				assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "same-event"), raw -> {
					throw new QuotationAiException("AI_RESPONSE_INVALID", "invalid");

				})).isInstanceOfSatisfying(QuotationAiException.class, error -> assertThat(error.code()).isEqualTo("AI_WORKFLOW_BUDGET"));
			}
			assertThat(fixture.requests).hasSize(1);
		}
	}

	// 方法：建立預設無缺值的驗證結果。
	private QuotationAiParsingService.ParseResult accepted(String raw) {
		var request = mock(QuotationRequestValidationService.ValidatedQuotationRequest.class);
		when(request.missingItemFields()).thenReturn(List.of());
		return new QuotationAiParsingService.ParseResult(request, raw);
	}

	// 方法：供應商暫時失敗最多五次；預算足夠時第五次有效結構可成功。
	@Test
	void sharesFiveCallCeilingAcrossTransientAndValidationRetries() throws Exception {
		try (Fixture fixture = new Fixture(directory)) {
			fixture.env.setProperty("app.ai.workflow.max-output-tokens", "20000");
			var validations = new java.util.concurrent.atomic.AtomicInteger();
			var result = fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "success"), raw -> {
				if (validations.incrementAndGet() < 5) throw new QuotationAiException("AI_RESPONSE_INVALID", "invalid");

				return accepted(raw);

			});
			assertThat(result).isNotNull();
			assertThat(fixture.requests).hasSize(5);
			fixture.requests.clear();
			fixture.status = 503;
			assertThatThrownBy(() -> fixture.workflow().parse("建立", List.of(), "CNS", new QuotationModelWorkflow.Scope("owner", "unavailable"), this::accepted))
				.hasMessageContaining("503");
			assertThat(fixture.requests).hasSize(5);
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final ObjectMapper mapper = new ObjectMapper();
		private final MockEnvironment env = new MockEnvironment();
		private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
		private final List<String> authorization = new CopyOnWriteArrayList<>();
		private final HttpServer server;
		private final JdbcTemplate jdbc;
		private volatile Integer usage = 100;
		private volatile int status = 200;
		private volatile boolean refusal;
		private volatile long delayMillis;
		private Function<String, String> responses = model -> model.equals("vision-model")
			? "{\"images\":[{\"messageId\":\"image-1\",\"transcription\":\"範例電話\",\"description\":\"名片\"}]}" : "{}";

		// 方法：只使用本機 HTTP stub 與臨時資料庫，不傳送真實客戶或憑證。
		private Fixture(Path directory) throws Exception {
			java.nio.file.Files.createDirectories(directory);
			var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("workflow.db"));
			new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__quotation_model_checkpoint.sql")).execute(dataSource);
			jdbc = new JdbcTemplate(dataSource);
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
			server.createContext("/", exchange -> {
				JsonNode input = mapper.readTree(exchange.getRequestBody().readAllBytes());
				requests.add(input);
				authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
				try {
					Thread.sleep(delayMillis);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				var body = mapper.createObjectNode();
				var choice = body.putArray("choices").addObject().put("finish_reason", "stop");
				if (refusal) choice.putObject("message").put("refusal", "refused");
				else choice.putObject("message").put("content", responses.apply(input.path("model").asString()));
				if (usage != null) body.putObject("usage").put("total_tokens", usage);
				byte[] bytes = mapper.writeValueAsBytes(body);
				exchange.sendResponseHeaders(status, bytes.length);
				try (var output = exchange.getResponseBody()) {
					output.write(bytes);
				}
			});
			server.start();
			env.withProperty("app.ai.api-url", url()).withProperty("app.ai.api-key", "base-key").withProperty("app.ai.model", "base-model")
				.withProperty("app.ai.workflow.enabled", "true");
			for (String role : List.of("text", "vision", "escalation")) env.setProperty("app.ai.workflow." + role + ".model", role + "-model");
		}

		// 方法：每次建立新的協調器，以測試重啟後仍可讀取持久檢查點。
		private QuotationModelWorkflow workflow() {
			QuotationAiPromptService prompt = mock(QuotationAiPromptService.class);
			when(prompt.build(anyString(), any(), anyString())).thenAnswer(call -> new QuotationAiPromptService.Prompt(
				"rules", call.getArgument(0), call.getArgument(1), mapper.readTree("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}")
			));
			return new QuotationModelWorkflow(profiles(), new QuotationModelCheckpointStore(jdbc), prompt, mapper);
		}

		// 方法：角色使用真實傳輸實作，但所有端點均為本機測試伺服器。
		private QuotationModelProfiles profiles() {
			return new QuotationModelProfiles(env, new AiExtractionService("5"));
		}

		// 方法：取得作業系統分配的本機連接埠。
		private String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		// 方法：測試結束時停止本機伺服器。
		@Override
		public void close() {
			server.stop(0);
		}
	}
}
