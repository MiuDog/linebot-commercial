package dev.miudog.linebotcommercial.desktop.diagnostic;

/**
 * 表示單一連線診斷階段的執行結果。
 */
public enum ConnectionDiagnosticStatus {

	PASSED("成功", "✓"),
	FAILED("失敗", "✗"),
	SKIPPED("略過", "-");

	//#region 欄位

	private final String label;
	private final String symbol;

	//#endregion

	//#region 建構子

	// 方法：建立具有文字與非色彩識別符號的診斷狀態。
	ConnectionDiagnosticStatus(
		String label,
		String symbol
	) {
		this.label = label;
		this.symbol = symbol;
	}

	//#endregion

	//#region 方法

	// 方法：取得使用者可讀的狀態名稱。
	public String label() {
		return label;
	}

	// 方法：取得不依賴色彩的狀態符號。
	public String symbol() {
		return symbol;
	}

	//#endregion
}

