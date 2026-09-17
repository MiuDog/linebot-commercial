package dev.miudog.linebotcommercial.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiStructuredCompletionTest {

	// 方法：錯填官方網站時在送出憑證前中止，保留可判讀的端點錯誤。
	@Test
	void rejectsWebsiteEndpointsBeforeSendingCredentials() {
		for (String host : List.of("openai.com", "www.openai.com", "platform.openai.com", "chatgpt.com", "chat.openai.com")) {
			AiExtractionService service = new AiExtractionService("5").profile("https://" + host, "test-key", "test-model", 4000, 1);
			assertThatThrownBy(() -> service.completeJson("rules", "input", List.of(), new ObjectMapper().readTree("{}")))
				.isInstanceOf(AiCompletionException.class)
				.hasMessage("AI_ENDPOINT_INVALID");
		}
	}

	@Test
	void sendsStrictSchemaAndRejectsIncompleteOrRefusedCompletions() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		AtomicReference<String> request = new AtomicReference<>();
		AtomicReference<String> response = new AtomicReference<>(
			"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}"
		);

		// 使用本機 HTTP 邊界確認真正傳送的契約，避免測試只覆誦請求建構細節。
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chat/completions", exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var body = exchange.getResponseBody()) {
				body.write(bytes);
			}
		});
		server.start();
		try {
			AiExtractionService service = new AiExtractionService("5");
			ReflectionTestUtils.setField(service, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
			ReflectionTestUtils.setField(service, "apiKey", "test-key");
			ReflectionTestUtils.setField(service, "model", "test-model");
			var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
			assertThat(service.completeJson("rules", "input", List.of(), schema)).isEqualTo("{}");
			var sent = mapper.readTree(request.get());
			assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_schema");
			assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
			assertThat(sent.path("response_format").path("json_schema").path("schema")).isEqualTo(schema);

			response.set("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");
			assertThatThrownBy(() -> service.completeJson("rules", "input", List.of(), schema))
				.isInstanceOf(AiCompletionException.class)
				.hasMessageContaining("AI_OUTPUT_TRUNCATED");
			response.set("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"private reason\"}}]}");
			assertThatThrownBy(() -> service.completeJson("rules", "input", List.of(), schema))
				.isInstanceOf(AiCompletionException.class)
				.hasMessageContaining("AI_REFUSED")
				.hasMessageNotContaining("private reason");
		}
		finally {
			server.stop(0);
		}
	}
}
