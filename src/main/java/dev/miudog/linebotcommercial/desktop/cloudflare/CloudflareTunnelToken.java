package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 從 cloudflared 使用的遠端管理 Token 取得 Tunnel UUID，不保存或輸出其他憑證內容。
 */
public final class CloudflareTunnelToken {

	//#region 欄位

	private static final int MAXIMUM_TOKEN_LENGTH = 8192;
	private static final Pattern TUNNEL_ID = Pattern.compile("\\\"(?:t|TunnelID)\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{36})\\\"");

	//#endregion

	//#region 建構子

	// 方法：禁止建立僅負責驗證 Tunnel Token 的工具類別。
	private CloudflareTunnelToken() {
	}

	//#endregion

	//#region 方法

	// 方法：解碼 Token 並只回傳其中的標準 Tunnel UUID。
	public static UUID tunnelId(String source) {
		String token = source == null ? "" : source.trim();
		if (token.isBlank() || token.length() > MAXIMUM_TOKEN_LENGTH) throw invalidToken();

		String payload = decode(token);
		Matcher matcher = TUNNEL_ID.matcher(payload);
		if (!matcher.find()) throw invalidToken();

		try {
			UUID tunnelId = UUID.fromString(matcher.group(1));
			if (!tunnelId.toString().equalsIgnoreCase(matcher.group(1))) throw invalidToken();

			return tunnelId;
		}
		catch (IllegalArgumentException exception) {
			throw invalidToken();
		}
	}

	// 方法：依序嘗試標準與 URL-safe Base64，支援 cloudflared 可接受的 Token 編碼。
	private static String decode(String token) {
		byte[] decoded;

		try {
			// Java Base64 函式庫：解碼 cloudflared 遠端管理 Token 的憑證內容。
			decoded = Base64.getDecoder().decode(token);
		}
		catch (IllegalArgumentException standardException) {
			try {
				// Java Base64 函式庫：兼容 URL-safe Token 編碼，但仍只讀取 Tunnel UUID。
				decoded = Base64.getUrlDecoder().decode(token);
			}
			catch (IllegalArgumentException urlException) {
				throw invalidToken();
			}
		}

		return new String(decoded, StandardCharsets.UTF_8);
	}

	// 方法：建立不包含 Token 原文或解碼內容的固定安全錯誤。
	private static IllegalArgumentException invalidToken() {
		return new IllegalArgumentException("Cloudflare Tunnel Token 格式無效，請重新從 Tunnel 的 Add a replica 複製");
	}

	//#endregion
}
