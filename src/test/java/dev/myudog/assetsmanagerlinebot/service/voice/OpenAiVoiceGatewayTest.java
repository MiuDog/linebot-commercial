package dev.myudog.assetsmanagerlinebot.service.voice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.myudog.assetsmanagerlinebot.observability.AiUsageAuditService;
import dev.myudog.assetsmanagerlinebot.observability.AiUsageCostCalculator;
import dev.myudog.assetsmanagerlinebot.observability.NetworkObservationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiVoiceGatewayTest {

	HttpServer server;
	AtomicReference<String> transcriptionRequest = new AtomicReference<>();
	AtomicReference<String> responseRequest = new AtomicReference<>();
	AtomicInteger responseStatus = new AtomicInteger(200);

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/audio/transcriptions", exchange -> respond(
			exchange,
			transcriptionRequest,
			"{\"model\":\"gpt-transcribe-snapshot\",\"text\":\"小定，圖片取出 ZD12345 八月十日\","
				+ "\"usage\":{\"input_tokens\":80,\"output_tokens\":20,"
				+ "\"input_tokens_details\":{\"cached_tokens\":10}}}"
		));
		server.createContext("/responses", exchange -> respond(
			exchange,
			responseRequest,
			"""
			{"model":"gpt-task-snapshot","usage":{"input_tokens":100,"output_tokens":30,
			 "input_tokens_details":{"cached_tokens":15}},"output":[{
			  "type":"mcp_call",
			  "name":"retrieve_images",
			  "output":{"imageCount":2},
			  "error":null
			}]}
			"""
		));
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void transcribesAudioAndLetsTheResponsesApiExecuteTheAllowlistedMcpTool(CapturedOutput output) throws Exception {
		OpenAiVoiceGateway gateway = gateway();

		String transcript = gateway.transcribe("audio".getBytes(StandardCharsets.UTF_8), "audio/mp4");
		VoiceAiGateway.TaskDecision decision = gateway.analyzeAndExecute(
			transcript,
			"ticket-1",
			LocalDate.of(2026, 8, 11)
		);

		assertThat(transcript).isEqualTo("小定，圖片取出 ZD12345 八月十日");
		assertThat(decision.toolCalled()).isTrue();
		assertThat(transcriptionRequest.get())
			.contains("name=\"model\"")
			.contains("gpt-transcribe")
			.contains("name=\"languages[]\"")
			.contains("zh-tw")
			.contains("小定");
		assertThat(responseRequest.get())
			.contains("\"type\":\"mcp\"")
			.contains("\"authorization\":\"mcp-secret\"")
			.contains("\"allowed_tools\":[\"retrieve_images\"]")
			.contains("ticket-1");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("operation=voice_transcription")
			.contains("model=gpt-transcribe-snapshot")
			.contains("inputTokens=80")
			.contains("operation=voice_task_response")
			.contains("model=gpt-task-snapshot")
			.contains("cachedInputTokens=15")
			.doesNotContain("openai-key")
			.doesNotContain("mcp-secret");
	}

	// 驗證語音 OpenAI 非成功回應仍留下安全且完整的失敗稽核。
	@Test
	void auditsRejectedVoiceAiAttempt(CapturedOutput output) {
		responseStatus.set(429);
		OpenAiVoiceGateway gateway = gateway();

		assertThatThrownBy(() -> gateway.transcribe("audio".getBytes(StandardCharsets.UTF_8), "audio/mp4"))
			.isInstanceOf(VoiceAiException.class)
			.hasMessageContaining("rejected");
		assertThat(output)
			.contains("event=ai_attempt_audited")
			.contains("operation=voice_transcription")
			.contains("status=HTTP_ERROR")
			.contains("inputTokens=null")
			.contains("priceStatus=CONFIGURED")
			.doesNotContain("openai-key");
	}

	// 方法：建立指向本機假 OpenAI 端點的語音閘道。
	private OpenAiVoiceGateway gateway() {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return new OpenAiVoiceGateway(
			new NetworkObservationLogger(),
			new AiUsageAuditService(new AiUsageCostCalculator("USD", "2", "0.5", "8")),
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
			new ObjectMapper(),
			baseUrl,
			"openai-key",
			"gpt-transcribe",
			"gpt-5.6-terra",
			"https://assets.example.com/mcp",
			"mcp-secret",
			10
		);
	}

	// 方法：記錄請求本文並回傳測試 JSON。
	private void respond(
		HttpExchange exchange,
		AtomicReference<String> requestBody,
		String responseBody
	) throws IOException {
		requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		int status = responseStatus.get();
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}
}
