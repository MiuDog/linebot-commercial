package dev.myudog.assetsmanagerlinebot.observability;

import java.util.regex.Pattern;

/** 集中處理可進入日誌的安全字串，防止憑證與下載 token 外洩。 */
public final class SensitiveDataSanitizer {

	private static final Pattern QUOTATION_DOWNLOAD_PATH = Pattern.compile(
		"(?i)^/quotation-downloads/[^/?#]+(?:/.*)?$"
	);
	private static final Pattern MEDIA_SHARE_PATH = Pattern.compile(
		"(?i)^/media/[^/?#]+(?:/.*)?$"
	);
	private static final Pattern SECRET_PATH_SEGMENT = Pattern.compile(
		"(?i)(/(?:download|token|tokens)/)[^/?#]+"
	);
	private static final Pattern JSON_SECRET_VALUE = Pattern.compile(
		"(?i)(\\\"(?:api[_-]?key|token|secret|password|authorization|authtoken)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"
	);
	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/]+=*");

	// 方法：工具類別不允許建立執行個體。
	private SensitiveDataSanitizer() {}

	// 方法：遮罩 URL path 中具權限的 token，且完全忽略 query string。
	public static String sanitizeRequestPath(String requestPath) {
		if (requestPath == null || requestPath.isBlank()) return "/";

		String pathOnly = requestPath.split("[?#]", 2)[0];
		if (QUOTATION_DOWNLOAD_PATH.matcher(pathOnly).matches()) return "/quotation-downloads/{token}";

		if (MEDIA_SHARE_PATH.matcher(pathOnly).matches()) return "/media/{shareToken}";

		return SECRET_PATH_SEGMENT.matcher(pathOnly).replaceAll("$1[REDACTED]");
	}

	// 方法：在桌面視窗顯示前再次遮罩常見 JSON 機密欄位與 Bearer Token。
	public static String sanitizeLogLine(String line) {
		if (line == null || line.isBlank()) return "";

		String sanitized = JSON_SECRET_VALUE.matcher(line).replaceAll("$1[REDACTED]$2");

		return BEARER_TOKEN.matcher(sanitized).replaceAll("$1[REDACTED]");
	}
}
