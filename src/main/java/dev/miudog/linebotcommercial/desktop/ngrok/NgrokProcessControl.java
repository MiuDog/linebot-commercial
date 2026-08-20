package dev.miudog.linebotcommercial.desktop.ngrok;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 定義 connector 唯一可操作的 ngrok child process 邊界。
 */
public interface NgrokProcessControl {

	// 方法：以 agent、Token 與本機 Port 啟動 ngrok child。
	void start(
		Path agent,
		String authtoken,
		int localPort
	);

	// 方法：在 timeout 內停止本 App 建立的 child。
	void stop(Duration timeout);

	// 方法：取得目前 ngrok child 狀態。
	NgrokStatus status();
}
