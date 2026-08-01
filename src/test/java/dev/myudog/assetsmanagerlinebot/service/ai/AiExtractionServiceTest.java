package dev.myudog.assetsmanagerlinebot.service.ai;

import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

	@Autowired
	AiExtractionService service;

	@BeforeAll
	static void startStubServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
				byte[] body;
				int status = statusCode.get();
				if (status == 200) {
				// 模擬 OpenAI 相容的回應外殼
					body = ("""
                        {"choices":[{"message":{"role":"assistant","content":%s}}]}
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
		server.start();
	}

	@AfterAll
	static void stopStubServer() {
		server.stop(0);
	}

	@DynamicPropertySource
	static void aiEndpoint(DynamicPropertyRegistry registry) {
		registry.add("app.ai.api-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
	}

	@Test
	void parsesJsonResponseIntoFields(CapturedOutput output) {
		statusCode.set(200);
		modelContent.set("{\"品名\": \"不鏽鋼支架\", \"數量\": \"12 組\"}");

		ExtractedSpec spec = service.extract("fake-image".getBytes(StandardCharsets.UTF_8), "image/jpeg");

		assertThat(spec.text("品名")).isEqualTo("不鏽鋼支架");
		// 數字混在單位文字裡也要抓得出來
		assertThat(spec.number("數量")).isEqualByComparingTo("12");
		assertThat(output)
			.contains("event=ai_extraction_started")
			.contains("event=ai_extraction_completed")
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
		assertThat(output).contains("event=ai_extraction_failed").doesNotContain("\"error\":\"boom\"");
	}

	/** 把字串包成合法的 JSON 字串字面值。 */
	private static String quote(String raw) {
		return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}
}
