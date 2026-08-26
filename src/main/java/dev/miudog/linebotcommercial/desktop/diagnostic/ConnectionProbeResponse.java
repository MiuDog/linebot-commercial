package dev.miudog.linebotcommercial.desktop.diagnostic;

import java.util.Objects;

/**
 * 保存限制長度的 HTTP 診斷回應。
 */
public record ConnectionProbeResponse(
	int statusCode,
	String body
) {

	// 方法：建立不包含 null 內容的診斷回應。
	public ConnectionProbeResponse {
		body = Objects.requireNonNullElse(body, "");
	}
}

