package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.util.Locale;

/**
 * 限制 cloudflared 可使用的官方 Tunnel 傳輸協定。
 */
public enum CloudflareProtocol {

	AUTO("auto"),
	HTTP2("http2"),
	QUIC("quic");

	//#region 欄位

	private final String argument;

	//#endregion

	//#region 建構子

	// 方法：建立可安全放入 cloudflared 參數的固定協定值。
	CloudflareProtocol(String argument) {
		this.argument = argument;
	}

	//#endregion

	//#region 方法

	// 方法：取得 cloudflared 官方參數值。
	public String argument() {
		return argument;
	}

	// 方法：解析設定值，拒絕任何未列入允許清單的內容。
	public static CloudflareProtocol parse(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

		for (CloudflareProtocol protocol : values()) {
			if (protocol.argument.equals(normalized)) return protocol;
		}

		throw new IllegalArgumentException("Cloudflare 協定只允許 auto、http2 或 quic");
	}

	//#endregion
}
