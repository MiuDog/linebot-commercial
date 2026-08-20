package dev.miudog.linebotcommercial.service.quotation;

import java.io.IOException;

/**
 * 表示 Microsoft Excel COM 啟動、逾時或匯出失敗。
 */
public class ExcelPdfExportException extends IOException {

	// 方法：建立可安全向上游描述的 Excel PDF 匯出錯誤。
	public ExcelPdfExportException(String message) {
		super(message);
	}

	// 方法：建立保留內部根因的 Excel PDF 匯出錯誤。
	public ExcelPdfExportException(String message, Throwable cause) {
		super(message, cause);
	}
}
