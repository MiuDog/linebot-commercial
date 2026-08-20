package dev.myudog.assetsmanagerlinebot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * 防止本機管理頁透過 ngrok 等公開代理被外部存取。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LocalAdminAccessFilter extends OncePerRequestFilter {

	private final boolean containerHostAccessEnabled;

	// 方法：由部署設定決定是否接受經 Docker 本機埠轉接的私有橋接來源。
	@Autowired
	public LocalAdminAccessFilter(
		@Value("${app.admin.container-host-access-enabled:false}") boolean containerHostAccessEnabled
	) {
		this.containerHostAccessEnabled = containerHostAccessEnabled;
	}

	// 方法：提供單元測試與非 Spring 建構使用，預設維持最嚴格的 loopback 限制。
	LocalAdminAccessFilter() {
		this(false);
	}

	// 方法：只攔截本機管理頁及其 API。
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.equals("/admin")
			&& !path.startsWith("/admin/")
			&& !path.startsWith("/api/admin/");
	}

	// 方法：套用安全標頭並拒絕經公開代理轉送的管理請求。
	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		applySecurityHeaders(response);
		if (
			hasForwardedClient(request)
			|| !isAllowedDirectSource(request.getRemoteAddr())
			|| !isAllowedLocalHost(request.getServerName())
		) {
			writeForbidden(response, "LOCAL_ACCESS_ONLY", "管理頁僅允許本機直接存取");
			return;
		}
		if (isUnsafeAdminApi(request) && !isTrustedBrowserRequest(request)) {
			writeForbidden(response, "CSRF_REJECTED", "管理操作缺少可信任的同源驗證");
			return;
		}

		// 外部 API：繼續執行 Spring 的其餘 HTTP 篩選與控制器流程。
		filterChain.doFilter(request, response);
	}

	// 方法：只接受真正 loopback，或明確啟用容器模式時的私有 Docker 橋接來源。
	private boolean isAllowedDirectSource(String remoteAddress) {
		if (isLoopbackAddress(remoteAddress)) return true;

		return containerHostAccessEnabled && isPrivateAddress(remoteAddress);
	}

	// 方法：辨識會改變管理資料且必須防範跨站請求的 API 方法。
	private boolean isUnsafeAdminApi(HttpServletRequest request) {
		String method = request.getMethod();
		return request.getRequestURI().startsWith("/api/admin/")
			&& !"GET".equals(method)
			&& !"HEAD".equals(method)
			&& !"OPTIONS".equals(method);
	}

	// 方法：要求瀏覽器自訂標頭，並拒絕 hostile Origin 或 Sec-Fetch-Site。
	private boolean isTrustedBrowserRequest(HttpServletRequest request) {
		if (!"1".equals(request.getHeader("X-Local-Admin-Request"))) return false;

		String fetchSite = request.getHeader("Sec-Fetch-Site");
		if (hasText(fetchSite) && !"same-origin".equalsIgnoreCase(fetchSite)) return false;

		String origin = request.getHeader("Origin");
		return !hasText(origin) || isSameLocalOrigin(request, origin);
	}

	// 方法：解析 Origin 並要求協定、loopback 主機及有效連接埠與目前請求完全相同。
	private boolean isSameLocalOrigin(HttpServletRequest request, String value) {
		try {
			URI origin = URI.create(value);
			if (origin.getUserInfo() != null
				|| origin.getPath() != null && !origin.getPath().isEmpty()
				|| origin.getQuery() != null
				|| origin.getFragment() != null) {
				return false;
			}

			int originPort = origin.getPort() < 0 ? defaultPort(origin.getScheme()) : origin.getPort();
			int requestPort = request.getServerPort() < 0
				? defaultPort(request.getScheme())
				: request.getServerPort();
			return request.getScheme().equalsIgnoreCase(origin.getScheme())
				&& isAllowedLocalHost(origin.getHost())
				&& originPort == requestPort;
		}
		catch (RuntimeException exception) {
			return false;
		}
	}

	// 方法：取得 HTTP 與 HTTPS 未明示時的標準連接埠。
	private int defaultPort(String scheme) {
		if ("http".equalsIgnoreCase(scheme)) return 80;

		return "https".equalsIgnoreCase(scheme) ? 443 : -1;
	}

	// 方法：加入管理頁需要的瀏覽器安全標頭。
	private void applySecurityHeaders(HttpServletResponse response) {
		// 外部 API：透過 Servlet 回應限制內容來源、嵌入與 MIME 推測。
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("X-Frame-Options", "DENY");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Cache-Control", "no-store");
	}

	// 方法：判斷請求是否帶有公開代理常用的轉送來源標頭。
	private boolean hasForwardedClient(HttpServletRequest request) {
		return hasText(request.getHeader("Forwarded"))
			|| hasText(request.getHeader("X-Forwarded-For"));
	}

	// 方法：只接受作業系統判定為 loopback 的直接連線來源。
	private boolean isLoopbackAddress(String remoteAddress) {
		if (!isIpLiteral(remoteAddress)) return false;

		try {
			return InetAddress.getByName(remoteAddress).isLoopbackAddress();
		}
		catch (UnknownHostException exception) {
			return false;
		}
	}

	// 方法：辨識不會由公網直接路由的私有 IP，搭配主機 loopback 綁定供 Docker Desktop 使用。
	private boolean isPrivateAddress(String remoteAddress) {
		if (!isIpLiteral(remoteAddress)) return false;

		try {
			return InetAddress.getByName(remoteAddress).isSiteLocalAddress();
		}
		catch (UnknownHostException exception) {
			return false;
		}
	}

	// 方法：只接受 localhost 或純 loopback IP，避免惡意網域利用 DNS rebinding 存取本機管理頁。
	private boolean isAllowedLocalHost(String serverName) {
		if (serverName == null || serverName.isBlank()) return false;

		if ("localhost".equalsIgnoreCase(serverName)) return true;

		return isLoopbackAddress(serverName);
	}

	// 方法：確認位址只包含 IPv4 或 IPv6 字元，避免 InetAddress 解析任意主機名稱。
	private boolean isIpLiteral(String value) {
		if (value == null || value.isBlank()) return false;

		return value.matches("[0-9.]+") || value.matches("[0-9A-Fa-f:]+");
	}

	// 方法：判斷標頭內容是否有實際文字。
	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	// 方法：以固定 JSON 格式回覆僅限本機存取。
	private void writeForbidden(HttpServletResponse response, String code, String message) throws IOException {
		String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";

		// 外部 API：透過 Servlet 寫入不含內部資訊的拒絕回應。
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType("application/json");
		response.getWriter().write(body);
	}
}
