package dev.miudog.linebotcommercial.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 【HTTP 共同事件起點】為每個 HTTP 請求建立安全的關聯識別碼並記錄結果。
 *
 * <p><b>事件呼叫鏈：</b>
 * {@code HTTP client → doFilterInternal → resolveRequestId
 * → MDC + X-Request-ID → FilterChain → Controller／Actuator
 * → http_request_completed}。
 *
 * <p>LINE webhook、媒體下載和健康檢查都先經過本 Filter；
 * 健康檢查仍取得 Request ID，但不寫完成日誌，避免固定探測造成洗版。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

	static final String REQUEST_ID_HEADER = "X-Request-ID";

	private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	// 方法：執行 doFilterInternal 方法的處理流程。
	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
		long startedAt = System.nanoTime();
		MDC.put("requestId", requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			if (!request.getRequestURI().startsWith("/actuator/")) {
				// 日誌：記錄 HTTP 請求結果與處理耗時。
				String safePath = SensitiveDataSanitizer.sanitizeRequestPath(request.getRequestURI());
				long durationMs = elapsedMilliseconds(startedAt);
				log.atInfo()
					.addKeyValue("event", "http_request_completed")
					.addKeyValue("requestId", requestId)
					.addKeyValue("method", request.getMethod())
					.addKeyValue("path", safePath)
					.addKeyValue("statusClass", response.getStatus() / 100 + "xx")
					.addKeyValue("status", response.getStatus())
					.addKeyValue("durationMs", durationMs)
					.log(
					"event=http_request_completed requestId={} method={} path={} "
					+ "status={} durationMs={}",
					requestId,
					request.getMethod(),
					safePath,
					response.getStatus(),
					durationMs
					);
			}
			MDC.remove("requestId");
		}
	}

	// 方法：執行 resolveRequestId 方法的處理流程。
	private static String resolveRequestId(String suppliedRequestId) {
		if (suppliedRequestId != null && SAFE_REQUEST_ID.matcher(suppliedRequestId).matches()) return suppliedRequestId;

		// 外部呼叫：使用 UUID 產生無法沿用安全識別碼時的新請求識別碼。
		return UUID.randomUUID().toString();
	}

	// 方法：執行 elapsedMilliseconds 方法的處理流程。
	private static long elapsedMilliseconds(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
