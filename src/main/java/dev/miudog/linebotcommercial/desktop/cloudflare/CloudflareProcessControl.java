package dev.miudog.linebotcommercial.desktop.cloudflare;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 定義 connector 唯一可操作的 cloudflared child process 邊界。
 */
public interface CloudflareProcessControl {

	// 方法：以 agent 與 Token 啟動 cloudflared child。
	void start(
		Path agent,
		String tunnelToken,
		CloudflareProtocol protocol
	);

	// 方法：等待 cloudflared readiness endpoint 確認至少一條 Edge 連線。
	boolean awaitReady(Duration timeout);

	// 方法：取得已清理的最後一筆診斷，供非技術使用者判斷網路阻擋原因。
	String diagnostic();

	// 方法：取得 cloudflared 已回報且不含 Token 的 connector 身分。
	CloudflareAgentIdentity identity();

	// 方法：在 timeout 內停止本 App 建立的 child。
	void stop(Duration timeout);

	// 方法：取得目前 cloudflared child 狀態。
	CloudflareStatus status();
}
