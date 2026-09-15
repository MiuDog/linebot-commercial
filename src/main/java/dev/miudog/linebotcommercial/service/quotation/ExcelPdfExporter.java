package dev.miudog.linebotcommercial.service.quotation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 將可信的暫存 XLSX 交給受控 headless office 程序匯出為 PDF。
 */
public interface ExcelPdfExporter {

	// 方法：以固定程序將指定 XLSX 匯出至同一報價目錄的 PDF。
	void export(Path workbook, Path pdf, Duration timeout) throws IOException;
}
