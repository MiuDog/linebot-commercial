package dev.miudog.linebotcommercial.desktop.control;

/**
 * 保存目前 service 的 loopback Port 與每次啟動 nonce。
 */
public record ServiceControlEndpoint(int port, String nonce) {

	// 方法：拒絕無效 Port 或空白 nonce，避免發布無認證控制端點。
	public ServiceControlEndpoint {
		if (port < 1 || port > 65535) throw new IllegalArgumentException("Service 控制 Port 無效");

		if (nonce == null || nonce.isBlank()) throw new IllegalArgumentException("Service 控制 nonce 不可為空白");
	}
}
