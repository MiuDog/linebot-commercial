package dev.myudog.assetsmanagerlinebot.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
		assertThat(output)
			.contains("event=http_request_completed")
			.contains("requestId=" + requestId)
			.contains("method=POST")
			.contains("path=/callback")
			.doesNotContain("must-not-be-logged");
	}
}
