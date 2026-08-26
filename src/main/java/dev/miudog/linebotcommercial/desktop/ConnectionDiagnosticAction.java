package dev.miudog.linebotcommercial.desktop;

import dev.miudog.linebotcommercial.desktop.diagnostic.ConnectionDiagnosticReport;
import java.util.function.Consumer;

/**
 * 定義不阻塞 Swing EDT 的連線診斷桌面操作。
 */
@FunctionalInterface
public interface ConnectionDiagnosticAction {

	// 方法：在背景測試指定網域，完成後回傳診斷報告。
	void run(
		String target,
		Consumer<ConnectionDiagnosticReport> completion
	);
}

