package dev.miudog.linebotcommercial.desktop.diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * 保存單次連線診斷的識別碼、目標與完整結果。
 */
public record ConnectionDiagnosticReport(
	String diagnosticId,
	String target,
	List<ConnectionDiagnosticStep> steps
) {

	// 方法：建立內容完整且不可變更的診斷報告。
	public ConnectionDiagnosticReport {
		diagnosticId = Objects.requireNonNull(diagnosticId, "診斷編號不可為 null");
		target = Objects.requireNonNull(target, "診斷目標不可為 null");
		steps = List.copyOf(Objects.requireNonNull(steps, "診斷步驟不可為 null"));
	}

	// 方法：依階段取得唯一診斷結果。
	public ConnectionDiagnosticStep step(ConnectionDiagnosticStage stage) {
		return steps.stream()
			.filter(step -> step.stage() == stage)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("找不到診斷階段：" + stage));
	}

	// 方法：判斷所有實際執行的連線步驟是否成功。
	public boolean successful() {
		return steps.stream().noneMatch(step -> step.status() == ConnectionDiagnosticStatus.FAILED);
	}

	// 方法：產生可直接顯示於桌面視窗的繁體中文報告。
	public String displayText() {
		StringBuilder content = new StringBuilder()
			.append("診斷編號：")
			.append(diagnosticId)
			.append(System.lineSeparator())
			.append("測試目標：")
			.append(target)
			.append(System.lineSeparator())
			.append(System.lineSeparator());

		for (ConnectionDiagnosticStep step : steps) {
			content.append(step.status().symbol())
				.append(" [")
				.append(step.status().label())
				.append("] ")
				.append(step.stage().label())
				.append(" (")
				.append(step.durationMillis())
				.append(" ms)")
				.append(System.lineSeparator())
				.append("  ")
				.append(step.detail())
				.append(System.lineSeparator());
		}

		return content.toString();
	}
}

