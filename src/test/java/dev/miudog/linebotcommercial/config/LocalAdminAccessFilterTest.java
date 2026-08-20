package dev.miudog.linebotcommercial.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAdminAccessFilterTest {

	@Test
	void rejectsHostileFormPostsEvenWhenTheConnectionIsLoopback() throws Exception {
		MockHttpServletRequest request = adminPost();
		request.setContentType("application/x-www-form-urlencoded");
		request.addHeader("Origin", "https://attacker.example");
		request.addHeader("X-Local-Admin-Request", "1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter().doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void acceptsSameOriginApiPostsOnlyWithTheLocalAdminHeader() throws Exception {
		MockHttpServletRequest request = adminPost();
		request.setContentType("application/json");
		request.addHeader("Origin", "http://localhost:8088");
		request.addHeader("Sec-Fetch-Site", "same-origin");
		request.addHeader("X-Local-Admin-Request", "1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter().doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
	}

	@Test
	void rejectsUnsafeApiRequestsWithoutTheCustomHeader() throws Exception {
		MockHttpServletRequest request = adminPost();
		request.setContentType("application/json");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter().doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void acceptsDockerBridgeRequestOnlyWhenContainerHostAccessIsEnabled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/");
		request.setRemoteAddr("172.18.0.1");
		request.setServerName("127.0.0.1");
		request.setServerPort(8088);
		request.setScheme("http");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter(true).doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
	}

	@Test
	void rejectsDockerBridgeRequestWhenContainerHostAccessIsDisabled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/");
		request.setRemoteAddr("172.18.0.1");
		request.setServerName("127.0.0.1");
		request.setServerPort(8088);
		request.setScheme("http");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter(false).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void rejectsPublicSourceEvenWhenContainerHostAccessIsEnabled() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/");
		request.setRemoteAddr("203.0.113.10");
		request.setServerName("127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter(true).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void rejectsForwardedDockerBridgeRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/");
		request.setRemoteAddr("172.18.0.1");
		request.setServerName("127.0.0.1");
		request.addHeader("X-Forwarded-For", "127.0.0.1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new LocalAdminAccessFilter(true).doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(chain.getRequest()).isNull();
	}

	// 方法：建立直接連到 localhost 的管理 API POST 請求。
	private MockHttpServletRequest adminPost() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/quotation-items");
		request.setRemoteAddr("127.0.0.1");
		request.setServerName("localhost");
		request.setServerPort(8088);
		request.setScheme("http");
		return request;
	}
}
