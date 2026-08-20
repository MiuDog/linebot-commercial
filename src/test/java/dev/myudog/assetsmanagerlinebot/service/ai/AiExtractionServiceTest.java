package dev.myudog.assetsmanagerlinebot.service.ai;

import com.sun.net.httpserver.HttpServer;
import dev.myudog.assetsmanagerlinebot.observability.AiUsageAuditService;
import dev.myudog.assetsmanagerlinebot.observability.AiUsageCostCalculator;
import dev.myudog.assetsmanagerlinebot.observability.NetworkObservationLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 以 JDK 內建的 HttpServer 假扮模型端點，驗證呼叫、回應解析與必要欄位檢查。
 *
 * <p>不需要真實金鑰，因此可以留在一般的 CI 流程裡跑。
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
	properties =
	{"app.storage.root=${java.io.tmpdir}/assets-manager-ai-test",
		"spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/assets-manager-ai-test/test.db",
		"app.ai.api-key=test-key",
		"app.ai.model=test-model",
		"app.ai.required-fields=品名,數量"}
)
class AiExtractionServiceTest {

	private static HttpServer server;

	/** 每個測試把要回傳的模型輸出放進來。 */
	private static final AtomicReference<String> modelContent = new AtomicReference<>();

	/** 回應的 HTTP 狀態碼，供測試錯誤路徑。 */
	private static final AtomicInteger statusCode = new AtomicInteger(200);
	private static final AtomicReference<String> receivedRequestBody = new AtomicReference<>();

	@Autowired
	AiExtractionService service;

	@BeforeAll
	static void startStubServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
				receivedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				byte[] body;
				int status = statusCode.get();
				if (status == 200) {
				// 模擬 OpenAI 相容的回應外殼
					body = ("""
						{"model":"test-model-snapshot","usage":{"prompt_tokens":120,"completion_tokens":30,
						"prompt_tokens_details":{"cached_tokens":20}},
						"choices":[{"message":{"role":"assistant","content":%s}}]}
						""".formatted(quote(modelContent.get()))).getBytes(StandardCharsets.UTF_8);
				}
				else {
					body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
				}
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
		server.createContext("/v1/slow", exchange -> {
				try {
					Thread.sleep(1_500);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.close();
			});
		server.start();
	}

	@AfterAll
	static void stopStubServer() {
		server.stop(0);
	}

	@DynamicPropertySource
	static void aiEndpoint(DynamicPropertyRegistry registry) {
		registry.add("app.ai.api-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
	}

	@Test
	void parsesJsonResponseIntoFields(CapturedOutput output) {
		statusCode.set(200);
		modelContent.set("{\"品名\": \"不鏽鋼支架\", \"數量\": \"12 組\"}");

		ExtractedSpec spec = service.extract("fake-image".getBytes(StandardCharsets.UTF_8), "image/jpeg");

		assertThat(spec.text("品名")).isEqualTo("不鏽鋼支架");
		assertThat(new ObjectMapper().readTree(receivedRequestBody.get()).has("temperature")).isFalse();
		// 數字混在單位文字裡也要抓得出來
		assertThat(spec.number("數量")).isEqualByComparingTo("12");
		assertThat(output)
			.contains("event=ai_extraction_started")
			.contains("event=ai_extraction_completed")
			.contains("event=ai_attempt_audited")
			.contains("model=test-model-snapshot")
			.contains("inputTokens=120")
			.contains("cachedInputTokens=20")
			.contains("outputTokens=30")
			.contains("priceStatus=UNCONFIGURED")
			.contains("requestId=background")
			.doesNotContain("不鏽鋼支架");
	}

	@Test
	void stripsMarkdownCodeFenceAroundJson() {
		statusCode.set(200);
		modelContent.set("```json\n{\"品名\": \"鋁擠型\", \"數量\": 5}\n```");

		ExtractedSpec spec = service.extract("fake".getBytes(StandardCharsets.UTF_8), "image/jpeg");

		assertThat(spec.text("品名")).isEqualTo("鋁擠型");
		assertThat(spec.number("數量")).isEqualByComparingTo("5");
	}

	@Test
	void reportsMissingRequiredFields() {
		statusCode.set(200);
		// 數量辨識不出來，模型依提示詞填 null
		modelContent.set("{\"品名\": \"鋁擠型\", \"數量\": null}");

		assertThatThrownBy(() -> service.extract("fake".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
			.isInstanceOf(AiExtractionException.class)
			.satisfies(thrown -> {
				AiExtractionException e = (AiExtractionException) thrown;
				assertThat(e.missingFields()).containsExactly("數量");
				assertThat(e.userMessage()).contains("數量");
			});
	}

	@Test
	void reportsNonJsonResponse() {
		statusCode.set(200);
		modelContent.set("我看不清楚這張圖片。");

		assertThatThrownBy(() -> service.extract("fake".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("沒有回傳 JSON 物件");
	}

	@Test
	void reportsHttpError(CapturedOutput output) {
		statusCode.set(500);
		modelContent.set("");

		assertThatThrownBy(() -> service.extract("fake".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("500");
		assertThat(output)
			.contains("event=ai_extraction_failed")
			.contains("event=ai_attempt_audited")
			.contains("status=HTTP_ERROR")
			.contains("inputTokens=null")
			.doesNotContain("\"error\":\"boom\"");
	}

	// 驗證未設定時也留下模型、明確狀態及未知 token，且不記錄提示詞。
	@Test
	void auditsNotConfiguredAttempt(CapturedOutput output) {
		AiExtractionService unconfigured = standaloneService(1, "", "", "");

		assertThatThrownBy(() -> unconfigured.completeJson("private-system", "private-user", List.of()))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("尚未設定");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("status=NOT_CONFIGURED")
			.contains("model=unknown")
			.contains("inputTokens=null")
			.doesNotContain("private-system")
			.doesNotContain("private-user");
	}

	// 驗證連線失敗時仍留下同一次嘗試的安全稽核事件。
	@Test
	void auditsNetworkFailure(CapturedOutput output) {
		AiExtractionService unavailable = standaloneService(
			1,
			"http://127.0.0.1:1/v1/chat/completions",
			"network-secret-key",
			"network-model"
		);

		assertThatThrownBy(() -> unavailable.completeJson("system", "user", List.of()))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("呼叫模型失敗");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("status=NETWORK_ERROR")
			.contains("model=network-model")
			.doesNotContain("network-secret-key");
	}

	// 驗證逾時與一般連線錯誤分開標記，方便營運查詢。
	@Test
	void auditsTimeout(CapturedOutput output) {
		AiExtractionService slow = standaloneService(
			1,
			"http://127.0.0.1:" + server.getAddress().getPort() + "/v1/slow",
			"timeout-secret-key",
			"timeout-model"
		);

		assertThatThrownBy(() -> slow.completeJson("system", "user", List.of()))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("呼叫模型失敗");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("status=TIMEOUT")
			.contains("model=timeout-model")
			.doesNotContain("timeout-secret-key");
	}

	@Test
	void completesStrictJsonWithSystemPromptAndOrderedImageIdentifiers() throws Exception {
		statusCode.set(200);
		modelContent.set("{\"schemaVersion\":\"1.0\"}");

		String content = service.completeJson(
			"system rules",
			"quotation instruction",
			List.of(
				new AiImageInput("image-1", "first".getBytes(StandardCharsets.UTF_8), "image/jpeg"),
				new AiImageInput("image-2", "second".getBytes(StandardCharsets.UTF_8), "image/png")
			)
		);

		assertThat(content).isEqualTo("{\"schemaVersion\":\"1.0\"}");
		JsonNode request = new ObjectMapper().readTree(receivedRequestBody.get());
		assertThat(request.path("messages").path(0).path("role").asString()).isEqualTo("system");
		assertThat(request.path("messages").path(0).path("content").asString()).isEqualTo("system rules");
		assertThat(request.path("messages").path(1).path("content").path(0).path("text").asString())
			.isEqualTo("quotation instruction");
		assertThat(request.path("messages").path(1).path("content").path(1).path("text").asString())
			.contains("image-1");
		assertThat(request.path("messages").path(1).path("content").path(2).path("image_url").path("url").asString())
			.startsWith("data:image/jpeg;base64,");
		assertThat(request.path("messages").path(1).path("content").path(3).path("text").asString())
			.contains("image-2");
		assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(4000);
		assertThat(request.has("max_tokens")).isFalse();
		assertThat(request.has("temperature")).isFalse();
	}

	@Test
	void rejectsCandidateImageWithoutAnExplicitSupportedContentType() {
		assertThatThrownBy(() -> service.completeJson(
			"system rules",
			"quotation instruction",
			List.of(new AiImageInput("image-1", "image".getBytes(StandardCharsets.UTF_8), null))
		))
			.isInstanceOf(AiExtractionException.class)
			.hasMessageContaining("圖片格式不可留空");
	}

	/** 把字串包成合法的 JSON 字串字面值。 */
	private static String quote(String raw) {
		return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	// 方法：建立使用本地假端點與未設定費率的獨立 AI 服務。
	private static AiExtractionService standaloneService(
		int timeoutSeconds,
		String apiUrl,
		String apiKey,
		String model
	) {
		AiExtractionService standalone = new AiExtractionService(Integer.toString(timeoutSeconds));
		standalone.configureObservability(
			new NetworkObservationLogger(),
			new AiUsageAuditService(new AiUsageCostCalculator("USD", "", "", ""))
		);
		ReflectionTestUtils.setField(standalone, "apiUrl", apiUrl);
		ReflectionTestUtils.setField(standalone, "apiKey", apiKey);
		ReflectionTestUtils.setField(standalone, "model", model);
		return standalone;
	}
}
