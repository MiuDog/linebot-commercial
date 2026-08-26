package dev.miudog.linebotcommercial.desktop.diagnostic;

import java.util.Objects;

/**
 * 保存單一診斷階段的狀態、說明與耗時。
 */
public record ConnectionDiagnosticStep(
	ConnectionDiagnosticStage stage,
	ConnectionDiagnosticStatus status,
	String detail,
	long durationMillis
) {

	// 方法：建立內容完整且耗時不會為負數的診斷步驟。
	public ConnectionDiagnosticStep {
		Objects.requireNonNull(stage, "診斷階段不可為 null");
		Objects.requireNonNull(status, "診斷狀態不可為 null");
		detail = Objects.requireNonNullElse(detail, "");
		durationMillis = Math.max(0L, durationMillis);
	}
}

