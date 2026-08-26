package dev.miudog.linebotcommercial.desktop.diagnostic;

/**
 * 定義連線診斷的穩定階段與中文名稱。
 */
public enum ConnectionDiagnosticStage {

	LOCAL_SERVICE("本機服務"),
	TARGET_DNS("目標 DNS"),
	TARGET_TCP("目標 TCP"),
	TARGET_TLS("目標 TLS"),
	TARGET_HTTP("目標 HTTP"),
	LINE_API("LINE Bot API"),
	AI_API("AI API");

	//#region 欄位

	private final String label;

	//#endregion

	//#region 建構子

	// 方法：建立具有中文顯示名稱的診斷階段。
	ConnectionDiagnosticStage(String label) {
		this.label = label;
	}

	//#endregion

	//#region 方法

	// 方法：取得使用者可讀的階段名稱。
	public String label() {
		return label;
	}

	//#endregion
}

