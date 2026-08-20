package dev.miudog.linebotcommercial.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationFilterTest {

	@Test
	void returnsAndLogsOneSafeRequestIdWithoutLoggingTheQueryString(CapturedOutput output) throws Exception {
		RequestCorrelationFilter filter = new RequestCorrelationFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback");
		request.setQueryString("token=must-not-be-logged");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		String requestId = response.getHeader("X-Request-ID");
		assertThat(requestId).isNotBlank();
		List<JsonNode> events = structuredEvents(output, "http_request_completed");
		if (!events.isEmpty()) {
			assertThat(kvp(events.getFirst(), "requestId")).isEqualTo(requestId);
			assertThat(kvp(events.getFirst(), "method")).isEqualTo("POST");
			assertThat(kvp(events.getFirst(), "path")).isEqualTo("/callback");
		}
		else {
			assertThat(output).contains("event=http_request_completed", "requestId=" + requestId, "path=/callback");
		}
		assertThat(output.getOut()).doesNotContain("must-not-be-logged");
	}

	// 驗證下載 token 位於 path 時也會在寫入日誌前遮罩。
	@Test
	void redactsDownloadTokenFromLoggedPath(CapturedOutput output) throws Exception {
		RequestCorrelationFilter filter = new RequestCorrelationFilter();
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/api/quotations/download/secret-download-token"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		List<JsonNode> events = structuredEvents(output, "http_request_completed");
		if (!events.isEmpty()) {
			assertThat(kvp(events.getFirst(), "path")).isEqualTo("/api/quotations/download/[REDACTED]");
		}
		else {
			assertThat(output).contains("path=/api/quotations/download/[REDACTED]");
		}
		assertThat(output.getOut()).doesNotContain("secret-download-token");
	}

	// 驗證實際報價與圖片公開路由的 raw token 都不會出現在任何 HTTP 日誌。
	@Test
	void templatesPublicDownloadAndMediaTokens(CapturedOutput output) throws Exception {
		RequestCorrelationFilter filter = new RequestCorrelationFilter();
		MockHttpServletResponse quotationResponse = new MockHttpServletResponse();
		MockHttpServletResponse mediaResponse = new MockHttpServletResponse();

		filter.doFilter(
			new MockHttpServletRequest("GET", "/quotation-downloads/raw-quotation-token"),
			quotationResponse,
			new MockFilterChain()
		);
		filter.doFilter(
			new MockHttpServletRequest("GET", "/media/raw-share-token"),
			mediaResponse,
			new MockFilterChain()
		);

		List<JsonNode> events = structuredEvents(output, "http_request_completed");
		if (!events.isEmpty()) {
			assertThat(events).extracting(event -> kvp(event, "path"))
				.containsExactly("/quotation-downloads/{token}", "/media/{shareToken}");
		}
		else {
			assertThat(output).contains("path=/quotation-downloads/{token}", "path=/media/{shareToken}");
		}
		assertThat(output.getOut())
			.doesNotContain("raw-quotation-token")
			.doesNotContain("raw-share-token");
	}

	// 方法：從 JSON Lines 輸出中挑出指定 structured event。
	private static List<JsonNode> structuredEvents(CapturedOutput output, String eventName) {
		ObjectMapper mapper = new ObjectMapper();
		return output.getOut()
			.lines()
			.filter(line -> line.startsWith("{"))
			.map(line -> parseJson(mapper, line))
			.filter(node -> node != null && eventName.equals(kvp(node, "event")))
			.toList();
	}

	// 方法：安全解析單行 JSON；非 JSON 的測試框架輸出直接略過。
	private static JsonNode parseJson(ObjectMapper mapper, String line) {
		try {
			return mapper.readTree(line);
		}
		catch (Exception exception) {
			return null;
		}
	}

	// 方法：讀取 Logback JsonEncoder 的單鍵 kvpList 欄位。
	private static String kvp(JsonNode event, String fieldName) {
		for (JsonNode field : event.path("kvpList")) {
			JsonNode value = field.get(fieldName);
			if (value != null) return value.asString();
		}
		return null;
	}
}
