package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存可安全顯示的 Tunnel、Connector 與本機電腦身分，不包含任何 Token。
 */
public record CloudflareAgentIdentity(
	String tunnelId,
	String connectorId,
	String computerName
) {

	//#region 欄位

	private static final String UUID_PATTERN = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
	private static final Pattern TUNNEL_ID = Pattern.compile("(?:tunnelID=|\\\"tunnelID\\\":\\\")" + UUID_PATTERN);
	private static final Pattern CONNECTOR_ID = Pattern.compile("Generated Connector ID:\\s*" + UUID_PATTERN);

	//#endregion

	// 方法：正規化所有可顯示欄位，避免 cloudflared 訊息帶入控制字元。
	public CloudflareAgentIdentity {
		tunnelId = safe(tunnelId);
		connectorId = safe(connectorId);
		computerName = safe(computerName);
	}

	//#region 方法

	// 方法：建立尚未收到 cloudflared 身分訊息的本機 connector 身分。
	public static CloudflareAgentIdentity empty() {
		return new CloudflareAgentIdentity("", "", computerNameFromEnvironment());
	}

	// 方法：從單行官方啟動診斷累積 Tunnel 與 Connector UUID。
	public CloudflareAgentIdentity withDiagnostic(String diagnostic) {
		String line = Objects.requireNonNullElse(diagnostic, "");
		Matcher tunnelMatcher = TUNNEL_ID.matcher(line);
		Matcher connectorMatcher = CONNECTOR_ID.matcher(line);
		String nextTunnelId = tunnelMatcher.find() ? tunnelMatcher.group(1).toLowerCase() : tunnelId;
		String nextConnectorId = connectorMatcher.find() ? connectorMatcher.group(1).toLowerCase() : connectorId;

		return new CloudflareAgentIdentity(nextTunnelId, nextConnectorId, computerName);
	}

	// 方法：建立適合非技術使用者閱讀的單行 connector 身分。
	public String displayText() {
		if (tunnelId.isBlank()) return "未啟用";

		String connector = connectorId.isBlank() ? "連線中" : connectorId;

		return computerName + "｜Tunnel " + tunnelId + "｜Connector " + connector;
	}

	// 方法：從 Windows 環境取得本機名稱，其他平台使用不含個資的後備文字。
	private static String computerNameFromEnvironment() {
		String computerName = System.getenv("COMPUTERNAME");

		return computerName == null || computerName.isBlank() ? "LOCAL-PC" : computerName;
	}

	// 方法：移除控制字元並限制 UI 身分長度。
	private static String safe(String source) {
		String value = Objects.requireNonNullElse(source, "").replaceAll("[\\p{Cntrl}]", "").trim();

		return value.length() <= 128 ? value : value.substring(0, 128);
	}

	//#endregion
}
