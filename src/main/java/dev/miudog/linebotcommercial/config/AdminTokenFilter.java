package dev.miudog.linebotcommercial.config;

import dev.miudog.linebotcommercial.config.runtime.AdminSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理 API 的第二道邊界；網路來源通過後仍必須持有管理權杖。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class AdminTokenFilter extends OncePerRequestFilter {

	private final AdminSecurityProperties properties;

	// 方法：執行此方法定義的受控處理流程。
	public AdminTokenFilter(AdminSecurityProperties properties) {
		this.properties = properties;
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/admin/");
	}

	// 方法：執行此方法定義的受控處理流程。
	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (!properties.tokenRequired()) {
			filterChain.doFilter(request, response);
			return;
		}

		String supplied = request.getHeader("X-Admin-Token");
		String authorization = request.getHeader("Authorization");
		if (
			(supplied == null || supplied.isBlank())
				&& authorization != null
				&& authorization.startsWith("Bearer ")
		) {
			supplied = authorization.substring("Bearer ".length());
		}

		if (!matches(supplied, properties.token())) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setContentType("application/json");
			response.getWriter().write(
				"{\"error\":{\"code\":\"ADMIN_TOKEN_REQUIRED\",\"message\":\"需要有效的管理憑證\"}}"
			);
			return;
		}
		filterChain.doFilter(request, response);
	}

	// 方法：執行此方法定義的受控處理流程。
	private static boolean matches(String supplied, String expected) {
		if (supplied == null || expected == null) return false;

		return MessageDigest.isEqual(
			supplied.getBytes(StandardCharsets.UTF_8),
			expected.getBytes(StandardCharsets.UTF_8)
		);
	}
}
